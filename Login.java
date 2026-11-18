import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Login {
    private static final String SESSION_COOKIE = "food_bank_session";

    static final ConcurrentHashMap<String, Account> SESSIONS =
            new ConcurrentHashMap<>();
        
    private static final String FORM = """
        <h1>Food Bank Login</h1>
        <form method="post" action="/">
          <p><label>Email <input name="email" type="email" required></label></p>
          <p><label>Password <input name="password" type="password" required></label></p>
          <p><label>Role
            <select name="role" required>
              <option value="">Select a role</option>
              <option>Donor</option>
              <option>Recipient</option>
              <option>Volunteer</option>
              <option>Coordinator</option>
            </select>
          </label></p>
          <button type="submit">Log in</button>
        </form>
        <p><a href="/register/donor">Create a donor account</a></p>
        <p><a href="/register/recipient">Create a recipient account</a></p>
        """;

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
        server.createContext("/", Login::handleLogin);
        server.createContext("/dashboard", Login::handleDashboard);
        server.createContext("/logout", Login::handleLogout);
        server.createContext("/register/donor", Login::handleAccounts);
        server.createContext("/register/recipient", Login::handleAccounts);
        server.createContext("/profile", Login::handleAccounts);
        server.createContext("/coordinator/accounts", Login::handleAccounts);
        server.createContext("/coordinator/recipients", Login::handleAccounts);
        InventoryPage.register(server);
        HamperPage.register(server);
        AssignmentPage.register(server);
        DistributionPage.register(server);
        server.start();
        AnalyzerEngine.start();
        PickupAlerts.start();
        EmailService.start();
        System.out.println("Open http://127.0.0.1:8080");
    }

    private static void handleLogin(HttpExchange request) throws IOException {
        if (!request.getRequestURI().getPath().equals("/")) {
            send(request, 404, "Page not found.");
            return;
        }
        if (request.getRequestMethod().equals("GET")) {
            send(request, 200, FORM);
            return;
        }
        if (!request.getRequestMethod().equals("POST")) {
            request.getResponseHeaders().set("Allow", "GET, POST");
            send(request, 405, "Method not allowed.");
            return;
        }

        var fields = new HashMap<String, String>();
        try {
            String body = new String(request.getRequestBody().readNBytes(4097), StandardCharsets.UTF_8);
            if (body.length() > 4096) {
                send(request, 413, "Login form is too large.");
                return;
            }
            for (String field : body.split("&")) {
                String[] pair = field.split("=", 2);
                if (pair.length == 2) {
                    fields.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                }
            }
            String role = fields.getOrDefault("role", "");
            if (role.equals("Donor") || role.equals("Recipient")
                    || role.equals("Volunteer") || role.equals("Coordinator")) {
                Account account = authenticate(
                        fields.getOrDefault("email", "").strip().toLowerCase(Locale.ROOT),
                        fields.getOrDefault("password", ""), role);

                if (account != null) {
                    String oldToken = sessionToken(request);
                    if (oldToken != null) {
                        SESSIONS.remove(oldToken);
                    }

                    String token = UUID.randomUUID().toString();
                    SESSIONS.put(token, account);
                    request.getResponseHeaders().set(
                            "Set-Cookie",
                            SESSION_COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Lax");
                    redirect(request, "/dashboard");
                    return;
                }
            }
        } catch (IllegalArgumentException invalidForm) {
            // Treat malformed form data as an invalid login.
        } catch (SQLException databaseError) {
            System.err.println(databaseError.getMessage());
            send(request, 503, "<p>Could not connect to the login database. Check the terminal.</p>" + FORM);
            return;
        }
        send(request, 200, "<p>Invalid email, password, or role.</p>" + FORM);
    }

    private static void handleDashboard(HttpExchange request) throws IOException {
        if (!request.getRequestURI().getPath().equals("/dashboard")) {
            send(request, 404, "Page not found.");
            return;
        }

        if (!request.getRequestMethod().equals("GET")) {
            request.getResponseHeaders().set("Allow", "GET");
            send(request, 405, "Method not allowed.");
            return;
        }

        String token = sessionToken(request);
        Account account = token == null ? null : SESSIONS.get(token);
        if (account == null) {
            redirect(request, "/");
            return;
        }

        try {
            String dashboard = buildDashboard(account.userId(), account.role());
            if (dashboard == null) {
                SESSIONS.remove(token);
                redirect(request, "/");
                return;
            }
            String links = switch (account.role()) {
                case "Donor" -> """
                        <p><a href="/profile">Edit my profile</a></p>
                        <p><a href="/donor/donations">Search my donations</a></p>
                        """;
                case "Recipient" -> """
                        <p><a href="/profile">Edit my profile</a></p>
                        <p><a href="/recipient/hamper">Request a hamper and schedule pickup</a></p>
                        <p><a href="/recipient/distributions">View my distribution history</a></p>
                        """;
                case "Volunteer" -> """
                        <p><a href="/volunteer/intake">Record a donation</a></p>
                        <p><a href="/volunteer/inventory">View inventory history</a></p>
                        <p><a href="/volunteer/assignments">View my assignments</a></p>
                        <p><a href="/volunteer/distributions">Manage pickups and distributions</a></p>
                        """;
                case "Coordinator" -> """
                        <p><a href="/coordinator/recipients">Review recipient registrations</a></p>
                        <p><a href="/coordinator/accounts">Manage user accounts</a></p>
                        <p><a href="/coordinator/categories">Manage inventory categories</a></p>
                        <p><a href="/coordinator/assignments">Assign volunteers</a></p>
                        """;
                default -> "";
            };
            send(request, 200, "<p>Logged in as " + account.role() + "</p>"
                    + links + dashboard + """
                    <form method="post" action="/logout">
                      <button type="submit">Log out</button>
                    </form>
                    """);
        } catch (SQLException error) {
            System.err.println(error.getMessage());
            send(request, 503, "<p>Could not load the dashboard. Check the terminal.</p>");
        }
    }

    private static void handleLogout(HttpExchange request) throws IOException {
        if (!request.getRequestURI().getPath().equals("/logout")) {
            send(request, 404, "Page not found.");
            return;
        }

        if (!request.getRequestMethod().equals("POST")) {
            request.getResponseHeaders().set("Allow", "POST");
            send(request, 405, "Method not allowed.");
            return;
        }

        String token = sessionToken(request);
        if (token != null) {
            SESSIONS.remove(token);
        }

        request.getResponseHeaders().set(
                "Set-Cookie",
                SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
        redirect(request, "/");
    }

    static String sessionToken(HttpExchange request) {
        String cookies = request.getRequestHeaders().getFirst("Cookie");
        if (cookies == null) {
            return null;
        }

        String prefix = SESSION_COOKIE + "=";
        for (String cookie : cookies.split(";")) {
            String value = cookie.strip();
            if (value.startsWith(prefix)) {
                return value.substring(prefix.length());
            }
        }

        return null;
    }

    static void redirect(HttpExchange request, String location) throws IOException {
        request.getResponseHeaders().set("Location", location);
        request.getResponseHeaders().set("Cache-Control", "no-store");
        request.sendResponseHeaders(303, -1);
        request.close();
    }    
    record Account(int userId, String role) {}

    static Connection openDatabase() throws SQLException {
        String user = System.getenv().getOrDefault("FOOD_BANK_DB_USER", "root");
        String password = System.getenv().getOrDefault("FOOD_BANK_DB_PASSWORD", "");

        return DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/food_bank_login_demo", user, password);
    }

    private static Account authenticate(String email, String password, String role)
            throws SQLException {
        String sql = """
                SELECT user_id, role, failed_attempts,
                       locked_until > CURRENT_TIMESTAMP AS is_locked,
                       locked_until IS NOT NULL AS had_lock,
                       password_hash = SHA2(?, 256) AS password_matches
                FROM users WHERE email = ? AND status = 'Active' FOR UPDATE
                """;
        try (Connection connection = openDatabase()) {
            connection.setAutoCommit(false);
            try {
                int userId;
                String savedRole;
                int failures;
                boolean valid;
                try (var query = connection.prepareStatement(sql)) {
                    query.setString(1, password);
                    query.setString(2, email);
                    try (var rows = query.executeQuery()) {
                        if (!rows.next() || rows.getBoolean("is_locked")) {
                            connection.rollback();
                            return null;
                        }
                        userId = rows.getInt("user_id");
                        savedRole = rows.getString("role");
                        valid = rows.getBoolean("password_matches") && savedRole.equals(role);
                        failures = valid ? 0 : (rows.getBoolean("had_lock")
                                ? 1 : rows.getInt("failed_attempts") + 1);
                    }
                }
                update(connection, """
                        UPDATE users SET failed_attempts = ?, locked_until =
                            CASE WHEN ? >= 5 THEN DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 15 MINUTE)
                                 ELSE NULL END
                        WHERE user_id = ?
                        """, failures, failures, userId);
                connection.commit();
                return valid ? new Account(userId, savedRole) : null;
            } catch (SQLException error) {
                rollback(connection, error);
                throw error;
            }
        }
    }

    record Profile(int id, String role, String name, String email, String phone,
            int householdSize, String dietary, String emergency, int volunteerId, String image) {}

    static final class FormError extends RuntimeException {
        FormError(String message) {
            super(message);
        }
    }

    private static void handleAccounts(HttpExchange request) throws IOException {
        String path = request.getRequestURI().getPath();
        String registrationRole = switch (path) {
            case "/register/donor" -> "Donor";
            case "/register/recipient" -> "Recipient";
            default -> null;
        };
        boolean coordinatorPage = path.equals("/coordinator/accounts") || path.equals("/coordinator/recipients");
        if (registrationRole == null && !coordinatorPage && !path.equals("/profile")) {
            send(request, 404, "Page not found.");
            return;
        }

        boolean post = request.getRequestMethod().equals("POST");
        if (!post && !request.getRequestMethod().equals("GET")) {
            request.getResponseHeaders().set("Allow", "GET, POST");
            send(request, 405, "Method not allowed.");
            return;
        }

        String token = sessionToken(request);
        Account account = token == null ? null : SESSIONS.get(token);
        if (registrationRole == null && account == null) {
            redirect(request, "/");
            return;
        }

        if (registrationRole != null && !post) {
            send(request, 200, accountForm(path, registrationRole, null, null));
            return;
        }

        try (Connection connection = openDatabase()) {
            if (registrationRole != null) {
                createAccount(connection, registrationRole, readForm(request));
                send(request, 201, registrationRole.equals("Recipient") ? """
                        <h1>Registration submitted</h1>
                        <p>A coordinator must approve your account before you can request a hamper.</p>
                        <p><a href="/">Back to login</a></p>
                        """ : """
                        <h1>Donor account created</h1>
                        <p><a href="/">Log in</a></p>
                        """);
                return;
            }

            Profile actor = readProfile(connection, account.userId());
            if (actor == null || !actor.role().equals(account.role())) {
                SESSIONS.remove(token);
                redirect(request, "/");
                return;
            }
            if (coordinatorPage && !actor.role().equals("Coordinator")) {
                send(request, 403, "<p>Only coordinators can access this page.</p>");
                return;
            }
            if (!coordinatorPage && !actor.role().equals("Donor") && !actor.role().equals("Recipient")) {
                send(request, 403, "<p>This page is for donor and recipient profile editing.</p>");
                return;
            }

            String csrf = csrfToken(token);
            if (post) {
                var fields = readForm(request);
                if (!csrf.equals(fields.get("csrf"))) {
                    send(request, 403, "<p>The form expired. Reopen the page and try again.</p>");
                    return;
                }
                if (path.equals("/profile")) {
                    saveProfile(connection, actor, fields);
                } else if (path.equals("/coordinator/recipients")) {
                    saveDecision(connection, fields);
                } else {
                    manageAccount(connection, fields);
                }
                redirect(request, path);
                return;
            }

            String page = switch (path) {
                case "/profile" -> accountForm(path, actor.role(), actor, csrf);
                case "/coordinator/recipients" -> pendingRecipients(connection, csrf);
                default -> accountManagementPage(request, connection, csrf);
            };
            send(request, 200, page);
        } catch (FormError error) {
            send(request, 400, "<p>" + escape(error.getMessage()) + "</p>"
                    + "<p><a href='" + path + "'>Go back</a></p>");
        } catch (SQLException error) {
            if (error.getErrorCode() == 1062) {
                send(request, 409, "<p>An account already uses that email address.</p>"
                        + "<p><a href='" + path + "'>Go back</a></p>");
                return;
            }
            System.err.println(error.getMessage());
            send(request, 503, "<p>Could not complete the account operation. Check the terminal.</p>");
        }
    }

    static String csrfToken(String sessionToken) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(
                    ("account-form:" + sessionToken).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    static Map<String, String> readForm(HttpExchange request) throws IOException {
        byte[] body = request.getRequestBody().readNBytes(16385);
        if (body.length > 16384) {
            throw new FormError("The submitted form is too large.");
        }
        try {
            return parseFields(new String(body, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException error) {
            throw new FormError("The submitted form was invalid.");
        }
    }

    static Map<String, String> parseFields(String form) {
        var fields = new HashMap<String, String>();
        if (form != null) {
            for (String field : form.split("&")) {
                String[] pair = field.split("=", 2);
                if (pair.length == 2) {
                    fields.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                            URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                }
            }
        }
        return fields;
    }

    static String value(Map<String, String> fields, String key) {
        return fields.getOrDefault(key, "").strip();
    }

    static String required(Map<String, String> fields, String key, String label, int max) {
        String value = value(fields, key);
        if (value.isEmpty() || value.length() > max) {
            throw new FormError(label + " is required and must be at most " + max + " characters.");
        }
        return value;
    }

    private static String email(Map<String, String> fields) {
        String email = required(fields, "email", "Email", 120).toLowerCase(Locale.ROOT);
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new FormError("Enter a valid email address.");
        }
        return email;
    }

    static int positiveId(String input, String label) {
        try {
            int number = Integer.parseInt(input);
            if (number > 0) {
                return number;
            }
        } catch (NumberFormatException ignored) {
            // The same message covers text, zero, and negative numbers.
        }
        throw new FormError(label + " must be a positive whole number.");
    }

    static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            statement.setObject(i + 1, values[i]);
        }
    }

    static int update(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            return statement.executeUpdate();
        }
    }

    static void rollback(Connection connection, Exception error) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            error.addSuppressed(rollbackError);
        }
    }

    private static void createAccount(Connection connection, String role, Map<String, String> fields)
            throws SQLException {
        String name = required(fields, "name", "Name", 100);
        String email = email(fields);
        String phone = required(fields, "phone", "Phone", 25);
        String password = fields.getOrDefault("password", "");
        if (password.isBlank()) {
            throw new FormError("Enter a password.");
        }

        connection.setAutoCommit(false);
        try {
            int userId;
            try (var insert = connection.prepareStatement("""
                    INSERT INTO users (name, email, phone_number, password_hash, role)
                    VALUES (?, ?, ?, SHA2(?, 256), ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                bind(insert, name, email, phone, password, role);
                insert.executeUpdate();
                try (var keys = insert.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("New user ID was not returned.");
                    }
                    userId = keys.getInt(1);
                }
            }

            switch (role) {
                case "Donor" -> update(connection, "INSERT INTO donors (user_id) VALUES (?)", userId);
                case "Recipient" -> update(connection, """
                        INSERT INTO recipients
                            (user_id, household_size, dietary_restrictions, emergency_contact, approval_status)
                        VALUES (?, ?, ?, ?, 'Pending')
                        """, userId, positiveId(value(fields, "household_size"), "Household size"),
                        value(fields, "dietary_restrictions"),
                        required(fields, "emergency_contact", "Emergency contact", 100));
                case "Volunteer" -> {
                    int volunteerId;
                    try (var insert = connection.prepareStatement(
                            "INSERT INTO volunteers (user_id, profile_image) VALUES (?, ?)",
                            Statement.RETURN_GENERATED_KEYS)) {
                        bind(insert, userId, imageUrl(fields));
                        insert.executeUpdate();
                        try (var keys = insert.getGeneratedKeys()) {
                            if (!keys.next()) {
                                throw new SQLException("New volunteer ID was not returned.");
                            }
                            volunteerId = keys.getInt(1);
                        }
                    }
                    addAvailability(connection, volunteerId, fields);
                }
                default -> throw new FormError("Unsupported account type.");
            }
            connection.commit();
        } catch (SQLException | FormError error) {
            rollback(connection, error);
            throw error;
        }
    }

    static Profile readProfile(Connection connection, int userId) throws SQLException {
        String sql = """
                SELECT u.user_id, u.role, u.name, u.email, u.phone_number, r.household_size,
                       r.dietary_restrictions, r.emergency_contact, v.volunteer_id, v.profile_image
                FROM users u
                LEFT JOIN recipients r ON r.user_id = u.user_id
                LEFT JOIN volunteers v ON v.user_id = u.user_id
                WHERE u.user_id = ? AND u.status = 'Active'
                """;
        try (var query = connection.prepareStatement(sql)) {
            query.setInt(1, userId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new Profile(userId, rows.getString("role"), rows.getString("name"),
                        rows.getString("email"), rows.getString("phone_number"), rows.getInt("household_size"),
                        rows.getString("dietary_restrictions"), rows.getString("emergency_contact"),
                        rows.getInt("volunteer_id"), rows.getString("profile_image"));
            }
        }
    }

    private static void saveProfile(Connection connection, Profile profile, Map<String, String> fields)
            throws SQLException {
        String name = required(fields, "name", "Name", 100);
        String email = email(fields);
        String phone = required(fields, "phone", "Phone", 25);
        connection.setAutoCommit(false);
        try {
            try (var lock = connection.prepareStatement(
                    "SELECT user_id FROM users WHERE user_id = ? AND status = 'Active' FOR UPDATE")) {
                lock.setInt(1, profile.id());
                try (var rows = lock.executeQuery()) {
                    if (!rows.next()) {
                        throw new FormError("That account is no longer available.");
                    }
                }
            }
            update(connection, "UPDATE users SET name = ?, email = ?, phone_number = ? WHERE user_id = ?",
                    name, email, phone, profile.id());

            if (profile.role().equals("Recipient")) {
                update(connection, """
                        UPDATE recipients SET household_size = ?, dietary_restrictions = ?, emergency_contact = ?
                        WHERE user_id = ?
                        """, positiveId(value(fields, "household_size"), "Household size"),
                        value(fields, "dietary_restrictions"),
                        required(fields, "emergency_contact", "Emergency contact", 100), profile.id());
            }

            if (profile.role().equals("Volunteer")) {
                String image = value(fields, "profile_image");
                if (!image.isEmpty() && !image.equals(profile.image())) {
                    update(connection, "UPDATE volunteers SET profile_image = ? WHERE user_id = ?",
                            imageUrl(fields), profile.id());
                }
                if (!value(fields, "available_date").isEmpty() || !value(fields, "start_time").isEmpty()
                        || !value(fields, "end_time").isEmpty()) {
                    addAvailability(connection, profile.volunteerId(), fields);
                }
            }
            connection.commit();
        } catch (SQLException | FormError error) {
            rollback(connection, error);
            throw error;
        }
    }

    private static String imageUrl(Map<String, String> fields) {
        String image = required(fields, "profile_image", "Profile image URL", 255);
        try {
            var uri = java.net.URI.create(image);
            if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null) {
                return image;
            }
        } catch (IllegalArgumentException ignored) {
            // Report the same validation message for malformed URLs.
        }
        throw new FormError("Enter an HTTP or HTTPS profile image URL.");
    }

    private static void addAvailability(Connection connection, int volunteerId,
            Map<String, String> fields) throws SQLException {
        try {
            LocalDate date = LocalDate.parse(value(fields, "available_date"));
            LocalTime start = LocalTime.parse(value(fields, "start_time"));
            LocalTime end = LocalTime.parse(value(fields, "end_time"));
            if (!end.isAfter(start)) {
                throw new FormError("Availability end time must be after its start time.");
            }
            update(connection, """
                    INSERT INTO volunteer_availability (volunteer_id, available_date, start_time, end_time)
                    SELECT ?, ?, ?, ?
                    WHERE NOT EXISTS (
                        SELECT 1 FROM volunteer_availability
                        WHERE volunteer_id = ? AND available_date = ? AND start_time = ? AND end_time = ?
                    )
                    """, volunteerId, date, start, end, volunteerId, date, start, end);
        } catch (DateTimeParseException error) {
            throw new FormError("Enter the availability date, start time, and end time.");
        }
    }

    static String field(String label, String key, String type, String value, String attributes) {
        return "<p><label>" + label + " <input name='" + key + "' type='" + type + "' value='"
                + escape(value) + "' " + attributes + "></label></p>";
    }

    static String hidden(String name, String value) {
        return "<input type='hidden' name='" + name + "' value='" + escape(value) + "'>";
    }

    private static String accountForm(String path, String role, Profile profile, String csrf) {
        boolean creating = profile == null;
        String back = csrf == null ? "/" : "/dashboard";
        String title = creating ? "Create " + role.toLowerCase(Locale.ROOT) + " account" : "Edit profile";
        var html = new StringBuilder("<h1>").append(title).append("</h1><form method='post' action='")
                .append(path).append("'>");
        if (csrf != null) {
            html.append(hidden("csrf", csrf));
        }
        if (path.equals("/coordinator/accounts")) {
            html.append(hidden("action", creating ? "create-volunteer" : "edit"));
            if (!creating) {
                html.append(hidden("user_id", Integer.toString(profile.id())));
            }
        }

        html.append(field("Name", "name", "text", creating ? "" : profile.name(), "required maxlength='100'"))
                .append(field("Email", "email", "email", creating ? "" : profile.email(), "required maxlength='120'"))
                .append(field("Phone", "phone", "tel", creating ? "" : profile.phone(), "required maxlength='25'"));

        if (creating) {
            html.append(field("Password", "password", "password", "", "required autocomplete='new-password'"));
        }
        if (role.equals("Recipient")) {
            html.append(field("Household size", "household_size", "number",
                            creating ? "" : Integer.toString(profile.householdSize()), "required min='1'"))
                    .append("<p><label>Dietary restrictions or allergies <textarea name='dietary_restrictions'>")
                    .append(creating ? "" : escape(profile.dietary())).append("</textarea></label></p>")
                    .append(field("Emergency contact", "emergency_contact", "text",
                            creating ? "" : profile.emergency(), "required maxlength='100'"));
        }
        if (role.equals("Volunteer")) {
            String required = creating ? "required" : "";
            if (!creating) {
                html.append("<p>Volunteer ID: ").append(profile.volunteerId()).append("</p>");
            }
            html.append(field("Profile image URL", "profile_image", "url",
                            creating ? "" : profile.image(), required + " maxlength='255'"))
                    .append("<fieldset><legend>").append(creating ? "Availability" : "Add availability (optional)")
                    .append("</legend>")
                    .append(field("Date", "available_date", "date", "", required))
                    .append(field("Start time", "start_time", "time", "", required))
                    .append(field("End time", "end_time", "time", "", required)).append("</fieldset>");
        }
        return html.append("<p><button>").append(creating ? "Create account" : "Save changes")
                .append("</button></p></form><p><a href='").append(back).append("'>Back</a></p>").toString();
    }

    private static String pendingRecipients(Connection connection, String csrf) throws SQLException {
        var page = new StringBuilder("""
                <h1>Pending recipient registrations</h1>
                <p><a href="/dashboard">Back to dashboard</a></p>
                """);
        String sql = """
                SELECT r.recipient_id, u.name, u.email, r.household_size
                FROM recipients r JOIN users u ON u.user_id = r.user_id
                WHERE r.approval_status = 'Pending' AND u.status = 'Active'
                ORDER BY r.recipient_id
                """;
        try (var query = connection.prepareStatement(sql); var rows = query.executeQuery()) {
            if (!rows.next()) {
                return page.append("<p>There are no pending registrations.</p>").toString();
            }
            do {
                page.append("<section><h2>").append(escape(rows.getString("name"))).append("</h2><p>")
                        .append(escape(rows.getString("email"))).append(" | Household size: ")
                        .append(rows.getInt("household_size")).append("</p>")
                        .append("<form method='post' action='/coordinator/recipients'>")
                        .append(hidden("csrf", csrf))
                        .append(hidden("recipient_id", Integer.toString(rows.getInt("recipient_id"))))
                        .append("<button name='decision' value='Approved'>Approve</button> ")
                        .append("<button name='decision' value='Rejected'>Reject</button></form></section>");
            } while (rows.next());
        }
        return page.toString();
    }

    private static void saveDecision(Connection connection, Map<String, String> fields) throws SQLException {
        int id = positiveId(value(fields, "recipient_id"), "Recipient ID");
        String decision = value(fields, "decision");
        if (!decision.equals("Approved") && !decision.equals("Rejected")) {
            throw new FormError("Select Approve or Reject.");
        }
        if (update(connection, """
                UPDATE recipients r JOIN users u ON u.user_id = r.user_id
                SET r.approval_status = ?
                WHERE r.recipient_id = ? AND r.approval_status = 'Pending' AND u.status = 'Active'
                """, decision, id) == 0) {
            throw new FormError("That registration is no longer pending.");
        }
    }

    private static String accountManagementPage(HttpExchange request, Connection connection,
            String csrf) throws SQLException {
        String selected;
        try {
            selected = parseFields(request.getRequestURI().getRawQuery()).get("edit");
        } catch (IllegalArgumentException error) {
            throw new FormError("Invalid account selection.");
        }
        if (selected != null) {
            Profile profile = readProfile(connection, positiveId(selected, "Account ID"));
            if (profile == null) {
                throw new FormError("That account is no longer available.");
            }
            String page = accountForm("/coordinator/accounts", profile.role(), profile, csrf);
            if (profile.role().equals("Volunteer")) {
                page += availabilityList(connection, profile.volunteerId(), profile.id(), csrf);
            }
            return page + "<p><a href='/coordinator/accounts'>Back to account list</a></p>";
        }

        var page = new StringBuilder("""
                <h1>Manage user accounts</h1><p><a href="/dashboard">Back to dashboard</a></p>
                <table border="1"><thead><tr><th>Name</th><th>Email</th><th>Role</th><th>Actions</th></tr></thead><tbody>
                """);
        String sql = "SELECT user_id, name, email, role FROM users WHERE status = 'Active' ORDER BY role, name, user_id";
        try (var query = connection.prepareStatement(sql); var rows = query.executeQuery()) {
            while (rows.next()) {
                int id = rows.getInt("user_id");
                page.append("<tr><td>").append(escape(rows.getString("name"))).append("</td><td>")
                        .append(escape(rows.getString("email"))).append("</td><td>")
                        .append(escape(rows.getString("role"))).append("</td><td>")
                        .append("<a href='/coordinator/accounts?edit=").append(id).append("'>Edit</a> ");
                page.append("<form method='post' action='/coordinator/accounts'>").append(hidden("csrf", csrf))
                        .append(hidden("action", "delete")).append(hidden("user_id", Integer.toString(id)))
                        .append("<label><input type='checkbox' name='confirm' value='yes' required> Confirm deletion</label> ")
                        .append("<button>Delete</button></form>");
                page.append("</td></tr>");
            }
        }
        return page.append("</tbody></table>")
                .append(accountForm("/coordinator/accounts", "Volunteer", null, csrf)).toString();
    }

    private static String availabilityList(Connection connection, int volunteerId, int userId,
            String csrf) throws SQLException {
        var page = new StringBuilder("<h2>Saved availability</h2><ul>");
        try (var query = connection.prepareStatement("""
                SELECT availability_id, available_date, start_time, end_time
                FROM volunteer_availability WHERE volunteer_id = ? ORDER BY available_date, start_time
                """)) {
            query.setInt(1, volunteerId);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    page.append("<li>").append(escape(rows.getString("available_date"))).append(" ")
                            .append(escape(rows.getString("start_time"))).append(" to ")
                            .append(escape(rows.getString("end_time")))
                            .append("<form method='post' action='/coordinator/accounts'>")
                            .append(hidden("csrf", csrf)).append(hidden("action", "remove-availability"))
                            .append(hidden("user_id", Integer.toString(userId)))
                            .append(hidden("availability_id", Integer.toString(rows.getInt("availability_id"))))
                            .append("<button>Remove time slot</button></form></li>");
                }
            }
        }
        return page.append("</ul>").toString();
    }

    private static void manageAccount(Connection connection, Map<String, String> fields)
            throws SQLException {
        String action = value(fields, "action");
        if (action.equals("create-volunteer")) {
            createAccount(connection, "Volunteer", fields);
            return;
        }

        int id = positiveId(value(fields, "user_id"), "Account ID");
        Profile target = readProfile(connection, id);
        if (target == null) {
            throw new FormError("That account is no longer available.");
        }
        switch (action) {
            case "edit" -> saveProfile(connection, target, fields);
            case "delete" -> {
                if (!value(fields, "confirm").equals("yes")) {
                    throw new FormError("Confirm account deletion.");
                }
                update(connection, "UPDATE users SET status = 'Deleted' WHERE user_id = ? AND status = 'Active'", id);
                SESSIONS.entrySet().removeIf(entry -> entry.getValue().userId() == id);
            }
            case "remove-availability" -> update(connection, """
                    DELETE FROM volunteer_availability WHERE availability_id = ? AND volunteer_id = ?
                    """, positiveId(value(fields, "availability_id"), "Availability ID"), target.volunteerId());
            default -> throw new FormError("Select a valid account action.");
        }
    }

    private static String buildDashboard(int userId, String role) throws SQLException {
        String sql = """
            SELECT u.name, u.email, u.phone_number, r.approval_status
            FROM users u LEFT JOIN recipients r ON r.user_id = u.user_id
            WHERE u.user_id = ? AND u.status = 'Active' AND u.role = ?
            """;

        try (var connection = openDatabase()) {
            String profile;

            try (var query = connection.prepareStatement(sql)) {
                query.setInt(1, userId);
                query.setString(2, role);

                try (var rows = query.executeQuery()) {
                    if (!rows.next()) {
                        return null;
                    }

                    profile = "<p>Name: " + escape(rows.getString("name"))
                            + "<br>Email: " + escape(rows.getString("email"))
                            + "<br>Phone: " + escape(rows.getString("phone_number")) + "</p>";
                    if (role.equals("Recipient")) {
                        profile += "<p>Registration status: " + escape(rows.getString("approval_status")) + "</p>";
                    }
                }
            }


            String heading;
            String recordsSql;
            switch (role) {
                case "Donor" -> {
                    heading = "Donations";
                    recordsSql = """
                        SELECT CONCAT(d.item_name, ' | ', d.quantity, ' ', d.unit, ' | ', d.donation_date)
                        FROM donations d JOIN donors p ON p.donor_id = d.donor_id
                        WHERE p.user_id = ? ORDER BY d.donation_date DESC, d.donation_id
                        """;
                }
                case "Recipient" -> {
                    heading = "Hamper requests";
                    recordsSql = """
                        SELECT CONCAT(h.request_date, ' | ', h.status,
                            COALESCE(CONCAT(' | Pickup: ', p.scheduled_at, ' (', p.status, ')'), ''))
                        FROM hamper_requests h JOIN recipients r ON r.recipient_id = h.recipient_id
                        LEFT JOIN pickups p ON p.request_id = h.request_id
                        WHERE r.user_id = ? ORDER BY h.request_date DESC, h.request_id
                        """;
                }
                case "Volunteer" -> {
                    heading = "Assigned shifts";
                    recordsSql = """
                        SELECT CONCAT(a.shift_at, ' | ', a.task_description, ' | ', a.status)
                        FROM volunteer_assignments a JOIN volunteers v ON v.volunteer_id = a.volunteer_id
                        WHERE v.user_id = ? ORDER BY a.shift_at DESC, a.assignment_id
                        """;
                }
                case "Coordinator" -> {
                    heading = "Alerts";
                    recordsSql = """
                        SELECT CONCAT(alert_type, ' | ', message, ' | ', status)
                        FROM alerts WHERE user_id = ? ORDER BY created_at DESC, alert_id
                        """;
                }
                default -> throw new IllegalArgumentException("Unknown role");
            }

            var page = new StringBuilder(profile).append("<h2>").append(heading).append("</h2>");
            try (var records = connection.prepareStatement(recordsSql)) {
                records.setInt(1, userId);
                try (var rows = records.executeQuery()) {
                    if (!rows.next()) {
                        return page.append("<p>No records yet.</p>").toString();
                    }
                    page.append("<ul>");
                    do {
                        page.append("<li>").append(escape(rows.getString(1))).append("</li>");
                    } while (rows.next());
                    return page.append("</ul>").toString();
                }
            }
        }
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    static void send(HttpExchange request, int status, String content) throws IOException {
        String page = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<title>Food Bank Login</title></head><body>" + content + "</body></html>";
        byte[] bytes = page.getBytes(StandardCharsets.UTF_8);
        request.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        request.getResponseHeaders().set("Cache-Control", "no-store");
        request.sendResponseHeaders(status, bytes.length);
        try (var output = request.getResponseBody()) {
            output.write(bytes);
        }
    }
}

