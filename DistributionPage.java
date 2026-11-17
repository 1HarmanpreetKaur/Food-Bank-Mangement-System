import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

final class DistributionPage {
    private static final String VOLUNTEER = "/volunteer/distributions";
    private static final String RECIPIENT = "/recipient/distributions";
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm");

    static void register(HttpServer server) {
        server.createContext(VOLUNTEER, DistributionPage::handle);
        server.createContext(RECIPIENT, DistributionPage::handle);
    }

    private static void handle(HttpExchange request) throws IOException {
        String path = request.getRequestURI().getPath();
        boolean volunteer = path.equals(VOLUNTEER);
        if (!volunteer && !path.equals(RECIPIENT)) {
            Login.send(request, 404, "<p>Page not found.</p>");
            return;
        }

        String method = request.getRequestMethod();
        boolean post = volunteer && method.equals("POST");
        if (!post && !method.equals("GET")) {
            request.getResponseHeaders().set("Allow", volunteer ? "GET, POST" : "GET");
            Login.send(request, 405, "<p>Method not allowed.</p>");
            return;
        }

        String token = Login.sessionToken(request);
        var account = token == null ? null : Login.SESSIONS.get(token);
        if (account == null) {
            Login.redirect(request, "/");
            return;
        }
        String role = volunteer ? "Volunteer" : "Recipient";
        if (!role.equals(account.role())) {
            Login.send(request, 403, "<p>This page is restricted to the " + role + " role.</p>");
            return;
        }

        int pickupId = 0;
        try (Connection db = Login.openDatabase()) {
            var actor = Login.readProfile(db, account.userId());
            if (actor == null || !role.equals(actor.role())) {
                Login.SESSIONS.remove(token, account);
                Login.redirect(request, "/");
                return;
            }

            String csrf = Login.csrfToken(token);
            if (post) {
                var fields = Login.readForm(request);
                if (!csrf.equals(fields.get("csrf"))) {
                    Login.send(request, 403, "<p>Reopen the distribution page and try again.</p>");
                    return;
                }
                pickupId = Login.positiveId(Login.value(fields, "pickup"), "Pickup");
                if (DistributionStore.apply(db, actor.volunteerId(), pickupId, fields)) {
                    Login.redirect(request, VOLUNTEER + "?pickup=" + pickupId + "&saved=1");
                } else {
                    rejectAssignment(request);
                }
                return;
            }

            var query = readQuery(request);
            String selection = Login.value(query, "pickup");
            if (volunteer && !selection.isEmpty()) {
                pickupId = Login.positiveId(selection, "Pickup");
                if (!DistributionStore.assigned(db, actor.volunteerId(), pickupId, false)) {
                    rejectAssignment(request);
                    return;
                }
                Login.send(request, 200, pickupPage(db, pickupId, csrf, "1".equals(query.get("saved"))));
                return;
            }

            String page = "<h1>" + (volunteer ? "Manage pickups and distributions" : "My distribution history")
                    + "</h1><p><a href='/dashboard'>Back to dashboard</a></p>";
            if (volunteer) {
                page += pickupList(db, actor.volunteerId());
            }
            page += history(db, actor, query, volunteer);
            Login.send(request, 200, page);
        } catch (Login.FormError error) {
            String back = pickupId == 0 ? path : VOLUNTEER + "?pickup=" + pickupId;
            Login.send(request, 400, "<p>" + Login.escape(error.getMessage()) + "</p>"
                    + "<p><a href='" + back + "'>Back</a></p>");
        } catch (SQLException error) {
            System.err.println("Distributions: " + error.getMessage());
            Login.send(request, 503, "<p>Could not load or save distributions. Check the server terminal.</p>");
        }
    }

    private static Map<String, String> readQuery(HttpExchange request) {
        try {
            return Login.parseFields(request.getRequestURI().getRawQuery());
        } catch (IllegalArgumentException error) {
            throw new Login.FormError("The search or page link is invalid.");
        }
    }

    private static void rejectAssignment(HttpExchange request) throws IOException {
        Login.send(request, 403, "<p>Only a volunteer with an active assignment for this pickup can manage it.</p>"
                + "<p><a href='" + VOLUNTEER + "'>Back to distributions</a></p>");
    }

    private static String pickupList(Connection db, int volunteer) throws SQLException {
        var page = new StringBuilder("<h2>Assigned pickups</h2><ul>");
        try (var query = db.prepareStatement("""
                SELECT DISTINCT p.pickup_id, p.scheduled_at, p.status, u.name
                FROM volunteer_assignments a
                JOIN volunteer_assignment_pickups ap ON ap.assignment_id = a.assignment_id
                JOIN pickups p ON p.pickup_id = ap.pickup_id
                JOIN hamper_requests h ON h.request_id = p.request_id
                JOIN recipients r ON r.recipient_id = h.recipient_id JOIN users u ON u.user_id = r.user_id
                WHERE a.volunteer_id = ? AND a.status = 'Assigned'
                ORDER BY p.scheduled_at, p.pickup_id
                """)) {
            query.setInt(1, volunteer);
            try (var rows = query.executeQuery()) {
                boolean found = false;
                while (rows.next()) {
                    found = true;
                    int id = rows.getInt("pickup_id");
                    page.append("<li><a href='").append(VOLUNTEER).append("?pickup=").append(id)
                            .append("'>Pickup #").append(id).append("</a> — ")
                            .append(Login.escape(rows.getString("name"))).append(" — ")
                            .append(Login.escape(rows.getString("scheduled_at"))).append(" — ")
                            .append(Login.escape(rows.getString("status"))).append("</li>");
                }
                if (!found) {
                    page.append("<li>No pickups linked to your active assignments.</li>");
                }
            }
        }
        return page.append("</ul>").toString();
    }

    private static String pickupPage(Connection db, int id, String csrf, boolean saved) throws SQLException {
        var pickup = DistributionStore.pickup(db, id, false);
        var previous = DistributionStore.distribution(db, id);
        var page = new StringBuilder("<h1>Pickup #").append(id).append("</h1>")
                .append("<p><a href='").append(VOLUNTEER).append("'>Back to distributions</a></p>")
                .append("<p>Recipient: ").append(Login.escape(pickup.recipient()))
                .append("<br>Scheduled: ").append(Login.escape(pickup.scheduled().format(MINUTE).replace('T', ' ')))
                .append("<br>Status: ").append(Login.escape(pickup.status())).append("</p>");
        if (saved) {
            page.append("<p>Pickup updated.</p>");
        }
        if ("Cancelled".equals(pickup.status()) || "Cancelled".equals(pickup.requestStatus())) {
            return page.append("<p>This pickup has been cancelled.</p>").toString();
        }

        page.append(actionForm(id, "status", csrf)).append("<p><label>Pickup status <select name='status' required>")
                .append("<option value=''>Select a status</option><option>Fulfilled</option>");
        if (previous == null) {
            page.append("<option>Missed</option><option>Delayed</option>");
        }
        page.append("</select></label> <button>Update status</button></p></form>");

        page.append(previous == null ? "<h2>Record distribution</h2>" : "<h2>Edit distribution</h2>");
        LocalDateTime time = previous == null ? LocalDateTime.now() : previous.time();
        page.append(actionForm(id, "save", csrf))
                .append(Login.hidden("distribution_id", previous == null ? "" : Integer.toString(previous.id())))
                .append(Login.field("Distribution date and time (local time)", "distribution_at", "datetime-local",
                        time.format(MINUTE), "required"))
                .append("<p>Enter quantities for the items handed out. Leave the other quantities blank.</p>");
        int options = inventoryFields(db, previous, page);
        if (options > 0) {
            page.append("<p><button>Save distribution</button></p>");
        } else {
            page.append("<p>No unexpired stock is available.</p>");
        }
        page.append("</form>");
        if (previous != null) {
            page.append("<details><summary>Delete distribution</summary>")
                    .append("<p>This returns the recorded quantities to inventory and resets the pickup to Pending.</p>")
                    .append(actionForm(id, "delete", csrf))
                    .append(Login.hidden("distribution_id", Integer.toString(previous.id())))
                    .append("<button>Confirm deletion</button></form></details>");
        }
        // Omit unused quantity fields so a long inventory list does not fill the request body.
        return page.append("""
            <script>
            for (const form of document.forms) {
                form.addEventListener('formdata', ({formData}) => {
                    for (const name of Array.from(formData.keys())) {
                        if (name.startsWith('item_') && formData.get(name) === '') formData.delete(name);
                    }
                });
            }
            </script>
            """).toString();
    }

    private static String actionForm(int pickup, String action, String csrf) {
        return "<form method='post' action='" + VOLUNTEER + "'>" + Login.hidden("csrf", csrf)
                + Login.hidden("pickup", Integer.toString(pickup)) + Login.hidden("action", action);
    }

    private static int inventoryFields(Connection db, DistributionStore.Distribution previous, StringBuilder page)
            throws SQLException {
        var original = DistributionStore.items(db, previous);
        LocalDate today = LocalDate.now();
        int options = 0;
        try (var query = db.prepareStatement("""
                SELECT i.inventory_id, i.item_name, i.quantity, i.unit, i.expiry_date, i.status
                FROM inventory i LEFT JOIN distribution_items di
                  ON di.inventory_id = i.inventory_id AND di.distribution_id = ?
                WHERE (i.quantity > 0 AND i.status = 'Available' AND (i.expiry_date IS NULL OR i.expiry_date >= ?))
                   OR di.distribution_item_id IS NOT NULL
                ORDER BY i.item_name, i.expiry_date, i.inventory_id
                """)) {
            Login.bind(query, previous == null ? 0 : previous.id(), today);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    int id = rows.getInt("inventory_id");
                    int initial = original.getOrDefault(id, 0);
                    LocalDate expiry = rows.getObject("expiry_date", LocalDate.class);
                    boolean expired = (expiry != null && expiry.isBefore(today))
                            || "Expired".equals(rows.getString("status"));
                    long available = expired ? initial : (long) rows.getInt("quantity") + initial;
                    String label = Login.escape(rows.getString("item_name") + " (#" + id + ") — "
                            + (expired ? "expired, keep or reduce" : "up to " + available + " " + rows.getString("unit"))
                            + (expiry == null ? "" : ", expiry " + expiry));
                    page.append(Login.field(label, "item_" + id, "number",
                            initial == 0 ? "" : Integer.toString(initial), "min='1' step='1' max='" + available + "'"));
                    options++;
                }
            }
        }
        return options;
    }

    private static String history(Connection db, Login.Profile actor, Map<String, String> fields, boolean volunteer)
            throws SQLException {
        String dateText = Login.value(fields, "date");
        LocalDate date = historyDate(dateText);
        String name = volunteer ? Login.value(fields, "recipient") : "";
        String path = volunteer ? VOLUNTEER : RECIPIENT;
        var page = new StringBuilder(volunteer ? "<h2>Distribution history</h2>" : "");
        page.append("<form method='get' action='").append(path).append("'>");
        if (volunteer) {
            page.append(Login.field("Recipient name", "recipient", "search", name, "maxlength='100'"));
        }
        page.append(Login.field("Distribution date", "date", "date", dateText, ""))
                .append("<button>Search</button> <a href='").append(path).append("'>Clear filters</a></form>")
                .append("<table><thead><tr><th>Distribution</th><th>Date and time</th>");
        if (volunteer) {
            page.append("<th>Recipient</th>");
        }
        page.append("<th>Item</th><th>Quantity</th></tr></thead><tbody>");

        String filter = volunteer ? """
                (d.volunteer_id = ? OR EXISTS (
                    SELECT 1 FROM volunteer_assignments a
                    JOIN volunteer_assignment_pickups ap ON ap.assignment_id = a.assignment_id
                    WHERE a.volunteer_id = ? AND a.status = 'Assigned' AND ap.pickup_id = d.pickup_id
                )) AND (? = '' OR LOCATE(LOWER(?), LOWER(u.name)) > 0)
                """ : "r.user_id = ?";
        String sql = """
                SELECT d.distribution_id, d.distribution_at, u.name, i.item_name, di.quantity_given, i.unit
                FROM distributions d JOIN recipients r ON r.recipient_id = d.recipient_id
                JOIN users u ON u.user_id = r.user_id
                JOIN distribution_items di ON di.distribution_id = d.distribution_id
                JOIN inventory i ON i.inventory_id = di.inventory_id
                WHERE
                """ + filter + """
                  AND (? IS NULL OR DATE(d.distribution_at) = ?)
                ORDER BY d.distribution_at DESC, d.distribution_id DESC, i.item_name, i.inventory_id
                """;
        try (var query = db.prepareStatement(sql)) {
            if (volunteer) {
                Login.bind(query, actor.volunteerId(), actor.volunteerId(), name, name, date, date);
            } else {
                Login.bind(query, actor.id(), date, date);
            }
            try (var rows = query.executeQuery()) {
                boolean found = false;
                while (rows.next()) {
                    found = true;
                    page.append("<tr>").append(cell("#" + rows.getInt("distribution_id")))
                            .append(cell(rows.getString("distribution_at")));
                    if (volunteer) {
                        page.append(cell(rows.getString("name")));
                    }
                    page.append(cell(rows.getString("item_name")))
                            .append(cell(rows.getInt("quantity_given") + " " + rows.getString("unit")))
                            .append("</tr>");
                }
                if (!found) {
                    page.append(volunteer ? "<tr><td colspan='5'>" : "<tr><td colspan='4'>")
                            .append("No matching distributions.</td></tr>");
                }
            }
        }
        return page.append("</tbody></table>").toString();
    }

    private static String cell(String value) {
        return "<td>" + Login.escape(value) + "</td>";
    }

    private static LocalDate historyDate(String input) {
        if (input.isEmpty()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(input);
            if (date.getYear() >= 1000 && date.getYear() <= 9999) {
                return date;
            }
        } catch (DateTimeParseException error) {
            // Use the same message for all invalid dates.
        }
        throw new Login.FormError("Choose a valid distribution date.");
    }

    static LocalDateTime distributionTime(String input) {
        if (input.length() == 16) {
            try {
                LocalDateTime value = LocalDateTime.parse(input);
                if (value.getYear() >= 1000 && value.getYear() <= 9999) {
                    return value;
                }
            } catch (DateTimeParseException error) {
                // Use the same message for all invalid dates.
            }
        }
        throw new Login.FormError("Choose a valid distribution date and time.");
    }
}
