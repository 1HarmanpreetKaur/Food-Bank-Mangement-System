import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

final class InventoryPage {
    private static final String INTAKE = "/volunteer/intake";
    private static final String INVENTORY = "/volunteer/inventory";
    private static final String DONATIONS = "/donor/donations";
    private static final String CATEGORIES = "/coordinator/categories";
    private static boolean categoriesReady;

    static void register(HttpServer server) {
        for (String path : new String[] {INTAKE, INVENTORY, DONATIONS, CATEGORIES}) {
            server.createContext(path, InventoryPage::handle);
        }
    }

    private static void handle(HttpExchange request) throws IOException {
        String path = request.getRequestURI().getPath();
        String role = switch (path) {
            case INTAKE, INVENTORY -> "Volunteer";
            case DONATIONS -> "Donor";
            case CATEGORIES -> "Coordinator";
            default -> "";
        };
        if (role.isEmpty()) {
            Login.send(request, 404, "<p>Page not found.</p>");
            return;
        }

        String method = request.getRequestMethod();
        boolean post = method.equals("POST") && !path.equals(DONATIONS);
        if (!post && !method.equals("GET")) {
            request.getResponseHeaders().set("Allow", path.equals(DONATIONS) ? "GET" : "GET, POST");
            Login.send(request, 405, "<p>Method not allowed.</p>");
            return;
        }
        String token = Login.sessionToken(request);
        var account = token == null ? null : Login.SESSIONS.get(token);
        if (account == null) {
            Login.redirect(request, "/");
            return;
        }
        if (!account.role().equals(role)) {
            Login.send(request, 403, "<p>This page is restricted to the " + role + " role.</p>");
            return;
        }

        try (var db = Login.openDatabase()) {
            var actor = Login.readProfile(db, account.userId());
            if (actor == null || !actor.role().equals(role)) {
                Login.SESSIONS.remove(token, account);
                Login.redirect(request, "/");
                return;
            }
            if (role.equals("Volunteer") && actor.volunteerId() == 0) {
                Login.send(request, 403, "<p>This account is missing its volunteer profile.</p>");
                return;
            }

            ensureCategories(db);
            String csrf = Login.csrfToken(token);
            if (post) {
                var form = Login.readForm(request);
                if (!csrf.equals(form.get("csrf"))) {
                    Login.send(request, 403, "<p>Reopen the form and try again.</p>");
                    return;
                }
                switch (path) {
                    case INTAKE -> {
                        saveIntake(db, actor.volunteerId(), form);
                        Login.redirect(request, INTAKE + "?saved=1");
                    }
                    case INVENTORY -> {
                        changeStock(db, form);
                        Login.redirect(request, INVENTORY + "?saved=1");
                    }
                    case CATEGORIES -> {
                        changeCategory(db, form);
                        Login.redirect(request, CATEGORIES + "?saved=1");
                    }
                    default -> throw new IllegalStateException("Unexpected route");
                }
                return;
            }

            Map<String, String> query;
            try {
                query = Login.parseFields(request.getRequestURI().getRawQuery());
            } catch (IllegalArgumentException error) {
                throw new Login.FormError("The search or page link is invalid.");
            }
            String page = switch (path) {
                case INTAKE -> intakeForm(db, query, csrf);
                case INVENTORY -> inventoryPage(db, query, csrf);
                case DONATIONS -> donationHistory(db, actor.id(), query);
                case CATEGORIES -> categoryPage(db, query, csrf);
                default -> throw new IllegalStateException("Unexpected route");
            };
            Login.send(request, 200, page);
        } catch (Login.FormError error) {
            Login.send(request, 400, "<p>" + Login.escape(error.getMessage()) + "</p>"
                    + "<p><a href='" + path + "'>Back</a></p>");
        } catch (SQLException error) {
            System.err.println("Inventory: " + error.getMessage());
            String message = error.getErrorCode() == 1062
                    ? "That category or record already exists."
                    : "Could not complete the inventory operation. Check the server terminal.";
            Login.send(request, error.getErrorCode() == 1062 ? 409 : 503,
                    "<p>" + message + "</p><p><a href='" + path + "'>Back</a></p>");
        }
    }

    // The first visit installs the category list without modifying existing tables.
    private static synchronized void ensureCategories(Connection db) throws SQLException {
        if (categoriesReady) {
            return;
        }

        try (var statement = db.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS inventory_categories (
                    name VARCHAR(60) NOT NULL PRIMARY KEY,
                    active BOOLEAN NOT NULL DEFAULT TRUE
                ) ENGINE=InnoDB
                """);
            statement.executeUpdate("""
                INSERT IGNORE INTO inventory_categories (name)
                SELECT category FROM donations
                UNION
                SELECT category FROM inventory
                """);
        }
        categoriesReady = true;
    }

    private record Intake(int id, int donorId, String item, String category, int quantity,
                          String unit, LocalDate donationDate, LocalDate expiryDate) {
        static Intake fromForm(Map<String, String> fields) {
            return new Intake(optionalId(fields), Login.positiveId(Login.value(fields, "donor_id"), "Donor"),
                    Login.required(fields, "item_name", "Item name", 120),
                    Login.required(fields, "category", "Category", 60),
                    Login.positiveId(Login.value(fields, "quantity"), "Quantity"),
                    Login.required(fields, "unit", "Unit", 30),
                    parseDate(Login.required(fields, "donation_date", "Donation date", 10), "Donation date"),
                    parseDate(Login.value(fields, "expiry_date"), "Expiry date"));
        }
    }

    private record Stock(int id, int quantity, int wasted, LocalDate wasteDate,
                         LocalDate expiry, boolean distributed) {
        boolean hasHistory() {
            return distributed || wasted > 0;
        }
    }

    private static int optionalId(Map<String, String> fields) {
        String id = Login.value(fields, "id");
        return id.isEmpty() ? 0 : Login.positiveId(id, "Donation ID");
    }

    private static LocalDate parseDate(String input, String label) {
        if (input.isEmpty()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(input);
            if (date.getYear() >= 1000 && date.getYear() <= 9999) {
                return date;
            }
        } catch (DateTimeParseException error) {
            // Use the same message for invalid and out-of-range dates.
        }
        throw new Login.FormError(label + " must be a valid date.");
    }

    private static Intake loadIntake(Connection db, int id, boolean forUpdate) throws SQLException {
        try (var query = db.prepareStatement("SELECT * FROM donations WHERE donation_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""))) {
            query.setInt(1, id);
            try (var row = query.executeQuery()) {
                if (!row.next()) {
                    throw new Login.FormError("Donation not found.");
                }
                return new Intake(id, row.getInt("donor_id"), row.getString("item_name"),
                        row.getString("category"), row.getInt("quantity"), row.getString("unit"),
                        row.getObject("donation_date", LocalDate.class), row.getObject("expiry_date", LocalDate.class));
            }
        }
    }

    private static String intakeForm(Connection db, Map<String, String> query, String csrf) throws SQLException {
        int id = optionalId(query);
        Intake intake = id == 0
                ? new Intake(0, 0, "", "", 0, "", LocalDate.now(), null)
                : loadIntake(db, id, false);
        var page = new StringBuilder(id == 0 ? "<h1>Record a donation</h1>" : "<h1>Edit donation intake</h1>");
        page.append("<p><a href='/dashboard'>Dashboard</a> | <a href='")
                .append(INVENTORY).append("'>Inventory history</a></p>");
        if ("1".equals(query.get("saved"))) {
            page.append("<p>Donation saved.</p>");
        }

        var donors = new StringBuilder();
        try (var statement = db.prepareStatement("""
                SELECT d.donor_id, u.name, u.email FROM donors d JOIN users u ON u.user_id = d.user_id
                WHERE (u.status = 'Active' AND u.role = 'Donor') OR d.donor_id = ?
                ORDER BY u.name, d.donor_id
                """)) {
            statement.setInt(1, intake.donorId());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    int donor = rows.getInt("donor_id");
                    donors.append(option(Integer.toString(donor),
                            rows.getString("name") + " (" + rows.getString("email") + ")",
                            donor == intake.donorId()));
                }
            }
        }
        String categories = categoryOptions(db, intake.category(), true);
        if (donors.isEmpty() || categories.isEmpty()) {
            return page + "<p>Intake requires an active donor and a category. "
                    + "A coordinator can add or activate categories.</p>";
        }

        page.append("<form method='post' action='").append(INTAKE).append("'>")
                .append(Login.hidden("csrf", csrf));
        if (id != 0) {
            page.append(Login.hidden("id", Integer.toString(id)));
        }

        return page.append("<p><label>Donor <select name='donor_id' required>")
                .append(option("", "Select a donor", intake.donorId() == 0)).append(donors)
                .append("</select></label></p><p><label>Category <select name='category' required>")
                .append(option("", "Select a category", intake.category().isEmpty())).append(categories)
                .append("</select></label></p>")
                .append(Login.field("Item name", "item_name", "text", intake.item(), "required maxlength='120'"))
                .append(Login.field("Quantity", "quantity", "number",
                        intake.quantity() == 0 ? "" : Integer.toString(intake.quantity()), "required min='1'"))
                .append(Login.field("Unit", "unit", "text", intake.unit(), "required maxlength='30'"))
                .append(Login.field("Donation date", "donation_date", "date", intake.donationDate().toString(), "required"))
                .append(Login.field("Expiry date, if applicable", "expiry_date", "date", dateText(intake.expiryDate()), ""))
                .append("<button>Save donation</button></form>").toString();
    }

    private static void saveIntake(Connection db, int volunteerId, Map<String, String> form) throws SQLException {
        Intake intake = Intake.fromForm(form);
        db.setAutoCommit(false);
        try {
            Intake previous = intake.id() == 0 ? null : loadIntake(db, intake.id(), false);
            String category = lockCategory(db, intake.category(), previous == null ? "" : previous.category());
            if (previous != null) {
                previous = loadIntake(db, intake.id(), true);
                if (!previous.category().equals(category)) {
                    lockCategory(db, category, "");
                }
            }
            requireDonor(db, intake.donorId(), previous == null ? 0 : previous.donorId());
            if (previous == null) {
                int donationId = insertDonation(db, intake, category, volunteerId);
                Login.update(db, """
                    INSERT INTO inventory (donation_id, item_name, category, quantity, unit, expiry_date, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, donationId, intake.item(), category, intake.quantity(), intake.unit(), intake.expiryDate(),
                        stockStatus(intake.quantity(), intake.expiryDate()));
            } else {
                Stock stock = lockStock(db, intake.id());
                if (stock.hasHistory() || stock.quantity() != previous.quantity()) {
                    throw new Login.FormError("This intake has stock history and cannot be edited.");
                }
                Login.update(db, """
                    UPDATE donations SET donor_id = ?, item_name = ?, category = ?, quantity = ?, unit = ?,
                        donation_date = ?, expiry_date = ? WHERE donation_id = ?
                    """, intake.donorId(), intake.item(), category, intake.quantity(), intake.unit(),
                        intake.donationDate(), intake.expiryDate(), intake.id());
                Login.update(db, """
                    UPDATE inventory SET item_name = ?, category = ?, quantity = ?, unit = ?, expiry_date = ?,
                        status = ? WHERE inventory_id = ?
                    """, intake.item(), category, intake.quantity(), intake.unit(), intake.expiryDate(),
                        stockStatus(intake.quantity(), intake.expiryDate()), stock.id());
            }
            db.commit();
        } catch (SQLException | RuntimeException error) {
            Login.rollback(db, error);
            throw error;
        }
    }

    private static void requireDonor(Connection db, int donorId, int previousDonor) throws SQLException {
        try (var query = db.prepareStatement("""
                SELECT d.donor_id FROM donors d JOIN users u ON u.user_id = d.user_id
                WHERE d.donor_id = ? AND ((u.status = 'Active' AND u.role = 'Donor') OR d.donor_id = ?)
                FOR UPDATE
                """)) {
            Login.bind(query, donorId, previousDonor);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new Login.FormError("Select an active donor.");
                }
            }
        }
    }

    private static int insertDonation(Connection db, Intake intake, String category, int volunteerId) throws SQLException {
        try (var insert = db.prepareStatement("""
                INSERT INTO donations (donor_id, entered_by_volunteer_id, item_name, category, quantity,
                    unit, donation_date, expiry_date)
                SELECT ?, v.volunteer_id, ?, ?, ?, ?, ?, ? FROM volunteers v
                JOIN users u ON u.user_id = v.user_id
                WHERE v.volunteer_id = ? AND u.status = 'Active' AND u.role = 'Volunteer'
                """, Statement.RETURN_GENERATED_KEYS)) {
            Login.bind(insert, intake.donorId(), intake.item(), category, intake.quantity(), intake.unit(),
                    intake.donationDate(), intake.expiryDate(), volunteerId);
            if (insert.executeUpdate() != 1) {
                throw new Login.FormError("Sign in as an active volunteer to record intake.");
            }
            try (var keys = insert.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("The donation did not return an ID.");
                }
                return keys.getInt(1);
            }
        }
    }

    private static Stock lockStock(Connection db, int donationId) throws SQLException {
        try (var query = db.prepareStatement("""
                SELECT inventory_id, quantity, wasted_quantity, waste_date, expiry_date,
                    EXISTS (SELECT 1 FROM distribution_items di WHERE di.inventory_id = i.inventory_id) AS distributed
                FROM inventory i WHERE donation_id = ? FOR UPDATE
                """)) {
            query.setInt(1, donationId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new Login.FormError("The linked inventory record was not found.");
                }
                return new Stock(rows.getInt("inventory_id"), rows.getInt("quantity"), rows.getInt("wasted_quantity"),
                        rows.getObject("waste_date", LocalDate.class), rows.getObject("expiry_date", LocalDate.class),
                        rows.getBoolean("distributed"));
            }
        }
    }

    private static void changeStock(Connection db, Map<String, String> form) throws SQLException {
        int donationId = Login.positiveId(Login.value(form, "id"), "Donation ID");
        db.setAutoCommit(false);
        try {
            Intake intake = loadIntake(db, donationId, true);
            Stock stock = lockStock(db, donationId);
            switch (Login.value(form, "action")) {
                case "delete" -> {
                    if (stock.hasHistory() || intake.quantity() != stock.quantity()) {
                        throw new Login.FormError("This intake has stock history and cannot be deleted.");
                    }
                    Login.update(db, "DELETE FROM inventory WHERE inventory_id = ?", stock.id());
                    Login.update(db, "DELETE FROM donations WHERE donation_id = ?", donationId);
                }
                case "waste" -> recordWaste(db, stock, form);
                default -> throw new Login.FormError("Choose an inventory action.");
            }
            db.commit();
        } catch (SQLException | RuntimeException error) {
            Login.rollback(db, error);
            throw error;
        }
    }

    private static void recordWaste(Connection db, Stock stock, Map<String, String> form) throws SQLException {
        int amount = Login.positiveId(Login.value(form, "amount"), "Waste quantity");
        LocalDate date = parseDate(Login.required(form, "waste_date", "Waste date", 10), "Waste date");
        if (amount > stock.quantity()) {
            throw new Login.FormError("Waste quantity exceeds remaining stock.");
        }
        if (date.isAfter(LocalDate.now())) {
            throw new Login.FormError("Waste date cannot be in the future.");
        }
        if (stock.wasteDate() != null && !stock.wasteDate().equals(date)) {
            throw new Login.FormError("This inventory record already has waste on another date.");
        }

        int remaining = stock.quantity() - amount;
        String status = stockStatus(remaining, stock.expiry());
        Login.update(db, """
            UPDATE inventory SET quantity = ?, status = ?, waste_date = ?, wasted_quantity = wasted_quantity + ?
            WHERE inventory_id = ?
            """, remaining, status, date, amount, stock.id());
    }

    private static String stockStatus(int quantity, LocalDate expiry) {
        if (expiry != null && expiry.isBefore(LocalDate.now())) {
            return "Expired";
        }
        return quantity == 0 ? "Depleted" : "Available";
    }

    private static String dateText(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    private static String option(String value, String label, boolean selected) {
        return "<option value='" + Login.escape(value) + "'" + (selected ? " selected" : "")
                + ">" + Login.escape(label) + "</option>";
    }

    private static String categoryOptions(Connection db, String selected, boolean activeOnly) throws SQLException {
        var options = new StringBuilder();
        try (var query = db.prepareStatement("""
                SELECT name, active FROM inventory_categories WHERE ? = FALSE OR active OR name = ? ORDER BY name
                """)) {
            Login.bind(query, activeOnly, selected);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString("name");
                    options.append(option(name, name + (rows.getBoolean("active") ? "" : " (archived)"),
                            name.equalsIgnoreCase(selected)));
                }
            }
        }
        return options.toString();
    }

    private static String lockCategory(Connection db, String name, String previous) throws SQLException {
        try (var query = db.prepareStatement("""
                SELECT name, active FROM inventory_categories WHERE name = ? FOR UPDATE
                """)) {
            query.setString(1, name);
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    String canonical = rows.getString("name");
                    if (rows.getBoolean("active") || canonical.equalsIgnoreCase(previous)) {
                        return canonical;
                    }
                }
                throw new Login.FormError("Select an active category.");
            }
        }
    }

    private static String inventoryPage(Connection db, Map<String, String> query, String csrf) throws SQLException {
        String item = Login.value(query, "item");
        String category = Login.value(query, "category");
        LocalDate date = parseDate(Login.value(query, "date"), "Donation date");

        var page = new StringBuilder("""
            <h1>Inventory history</h1>
            <p><a href='/dashboard'>Dashboard</a> | <a href='/volunteer/intake'>Record a donation</a></p>
            <form method='get' action='/volunteer/inventory'>
            """);
        page.append(Login.field("Item name contains", "item", "search", item, "maxlength='120'"))
                .append(filterFields(db, category, date))
                .append("<button>Search</button> <a href='").append(INVENTORY).append("'>Clear filters</a></form>");
        if ("1".equals(query.get("saved"))) {
            page.append("<p>Inventory updated.</p>");
        }
        page.append("""
            <table>
            <thead><tr><th>Item</th><th>Category</th><th>Donation date</th><th>Expiry date</th>
            <th>Received</th><th>Remaining</th><th>Status</th><th>Waste</th><th>Actions</th></tr></thead>
            <tbody>
            """);
        try (var statement = db.prepareStatement("""
                SELECT d.donation_id, d.item_name, d.category, d.donation_date, d.expiry_date,
                       d.quantity AS donated, i.quantity AS remaining, d.unit, i.waste_date,
                       i.wasted_quantity, i.status,
                       EXISTS (SELECT 1 FROM distribution_items di WHERE di.inventory_id = i.inventory_id) AS distributed
                FROM inventory i JOIN donations d ON d.donation_id = i.donation_id
                WHERE (? = '' OR LOCATE(LOWER(?), LOWER(d.item_name)) > 0)
                    AND (? = '' OR d.category = ?) AND (? IS NULL OR d.donation_date = ?)
                ORDER BY d.donation_date DESC, d.donation_id DESC
                """)) {
            Login.bind(statement, item, item, category, category, date, date);
            try (var rows = statement.executeQuery()) {
                boolean found = false;
                while (rows.next()) {
                    found = true;
                    inventoryRow(page, rows, csrf);
                }
                if (!found) {
                    page.append("<tr><td colspan='9'>No matching inventory records.</td></tr>");
                }
            }
        }
        return page.append("</tbody></table>").toString();
    }

    private static void inventoryRow(StringBuilder page, ResultSet row, String csrf) throws SQLException {
        int id = row.getInt("donation_id");
        int donated = row.getInt("donated");
        int remaining = row.getInt("remaining");
        int wasted = row.getInt("wasted_quantity");
        String unit = row.getString("unit");
        LocalDate wasteDate = row.getObject("waste_date", LocalDate.class);
        LocalDate expiry = row.getObject("expiry_date", LocalDate.class);
        String waste = wasted == 0 ? "None" : wasted + " " + unit + " on " + dateText(wasteDate);
        boolean editable = !row.getBoolean("distributed") && wasted == 0 && donated == remaining;
        page.append("<tr>")
                .append(cell(row.getString("item_name"))).append(cell(row.getString("category")))
                .append(cell(row.getString("donation_date"))).append(cell(dateText(expiry)))
                .append(cell(donated + " " + unit)).append(cell(remaining + " " + unit))
                .append(cell(expiry != null && expiry.isBefore(LocalDate.now()) ? "Expired" : row.getString("status")))
                .append(cell(waste)).append("<td>");
        if (editable) {
            page.append("<a href='").append(INTAKE).append("?id=").append(id).append("'>Edit</a>")
                    .append("<details><summary>Delete intake</summary>")
                    .append("<p>Remove this donation and its inventory record?</p>")
                    .append(stockForm("delete", id, csrf)).append("<button>Confirm delete</button></form></details>");
        }
        if (remaining > 0) {
            page.append("<details><summary>Record waste</summary>").append(stockForm("waste", id, csrf))
                    .append(Login.field("Quantity", "amount", "number", "",
                            "required min='1' max='" + remaining + "'"))
                    .append(Login.field("Date", "waste_date", "date",
                            wasteDate == null ? LocalDate.now().toString() : wasteDate.toString(), "required"))
                    .append("<button>Save waste</button></form></details>");
        }
        page.append("</td></tr>");
    }

    private static String stockForm(String action, int id, String csrf) {
        return "<form method='post' action='" + INVENTORY + "'>" + Login.hidden("csrf", csrf)
                + Login.hidden("action", action) + Login.hidden("id", Integer.toString(id));
    }

    private static String filterFields(Connection db, String category, LocalDate date) throws SQLException {
        return "<p><label>Category <select name='category'>" + option("", "All categories", category.isEmpty())
                + categoryOptions(db, category, false) + "</select></label></p>"
                + Login.field("Donation date", "date", "date", dateText(date), "");
    }

    private static String donationHistory(Connection db, int userId, Map<String, String> query) throws SQLException {
        String category = Login.value(query, "category");
        LocalDate date = parseDate(Login.value(query, "date"), "Donation date");
        var page = new StringBuilder("""
            <h1>My donation history</h1>
            <p><a href='/dashboard'>Dashboard</a></p>
            <form method='get' action='/donor/donations'>
            """);
        page.append(filterFields(db, category, date))
                .append("<button>Search</button> <a href='/donor/donations'>Clear filters</a></form>")
                .append("<table><thead><tr><th>Item</th><th>Category</th><th>Quantity</th><th>Donation date</th>")
                .append("</tr></thead><tbody>");
        try (var statement = db.prepareStatement("""
                SELECT d.item_name, d.category, d.quantity, d.unit, d.donation_date
                FROM donations d JOIN donors donor ON donor.donor_id = d.donor_id
                WHERE donor.user_id = ? AND (? = '' OR d.category = ?)
                    AND (? IS NULL OR d.donation_date = ?)
                ORDER BY d.donation_date DESC, d.donation_id DESC
                """)) {
            Login.bind(statement, userId, category, category, date, date);
            try (var rows = statement.executeQuery()) {
                boolean found = false;
                while (rows.next()) {
                    found = true;
                    page.append("<tr>").append(cell(rows.getString("item_name")))
                            .append(cell(rows.getString("category")))
                            .append(cell(rows.getInt("quantity") + " " + rows.getString("unit")))
                            .append(cell(rows.getString("donation_date"))).append("</tr>");
                }
                if (!found) {
                    page.append("<tr><td colspan='4'>No matching donations.</td></tr>");
                }
            }
        }
        return page.append("</tbody></table>").toString();
    }

    private static String cell(String text) {
        return "<td>" + Login.escape(text) + "</td>";
    }

    private static String categoryPage(Connection db, Map<String, String> query, String csrf) throws SQLException {
        var page = new StringBuilder("<h1>Manage inventory categories</h1><p><a href='/dashboard'>Dashboard</a></p>");
        if ("1".equals(query.get("saved"))) {
            page.append("<p>Category updated.</p>");
        }
        page.append(categoryForm("add", "", csrf))
                .append(Login.field("New category", "new_name", "text", "", "required maxlength='60'"))
                .append("<button>Add category</button></form><ul>");
        try (var statement = db.prepareStatement("SELECT name, active FROM inventory_categories ORDER BY name");
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                String name = rows.getString("name");
                boolean active = rows.getBoolean("active");
                page.append("<li><strong>").append(Login.escape(name)).append("</strong>")
                        .append(active ? " (active)" : " (archived)")
                        .append(categoryForm("rename", name, csrf))
                        .append(Login.field("Change name", "new_name", "text", name, "required maxlength='60'"))
                        .append("<button>Rename</button></form>")
                        .append(categoryForm(active ? "archive" : "activate", name, csrf))
                        .append(active ? "<button>Archive</button>" : "<button>Reactivate</button>")
                        .append("</form></li>");
            }
        }
        return page.append("</ul>").toString();
    }

    private static String categoryForm(String action, String name, String csrf) {
        return "<form method='post' action='" + CATEGORIES + "'>"
                + Login.hidden("csrf", csrf) + Login.hidden("action", action) + Login.hidden("name", name);
    }

    private static void changeCategory(Connection db, Map<String, String> form) throws SQLException {
        String action = Login.value(form, "action");
        if (action.equals("add")) {
            Login.update(db, "INSERT INTO inventory_categories (name) VALUES (?)",
                    Login.required(form, "new_name", "Category name", 60));
            return;
        }

        String name = Login.required(form, "name", "Category", 60);
        db.setAutoCommit(false);
        try {
            switch (action) {
                case "archive", "activate" -> {
                    lockCategory(db, name, name);
                    Login.update(db, "UPDATE inventory_categories SET active = ? WHERE name = ?",
                            action.equals("activate"), name);
                }
                case "rename" -> {
                    String newName = Login.required(form, "new_name", "Category name", 60);
                    lockCategory(db, name, name);
                    Login.update(db, "UPDATE inventory_categories SET name = ? WHERE name = ?", newName, name);
                    Login.update(db, "UPDATE donations SET category = ? WHERE category = ?", newName, name);
                    Login.update(db, "UPDATE inventory SET category = ? WHERE category = ?", newName, name);
                }
                default -> throw new Login.FormError("Choose a category action.");
            }
            db.commit();
        } catch (SQLException | RuntimeException error) {
            Login.rollback(db, error);
            throw error;
        }
    }
}
