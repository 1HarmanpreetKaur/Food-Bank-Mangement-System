import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Login {
    private static final String SESSION_COOKIE = "food_bank_session";

    private static final ConcurrentHashMap<String, Account> SESSIONS =
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
        """;

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
        server.createContext("/", Login::handleLogin);
        server.createContext("/dashboard", Login::handleDashboard);
        server.createContext("/logout", Login::handleLogout);
        server.start();
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
            send(request, 200, "<p>Logged in as " + account.role() + "</p>"
                    + dashboard + """
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

    private static String sessionToken(HttpExchange request) {
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

    private static void redirect(HttpExchange request, String location) throws IOException {
        request.getResponseHeaders().set("Location", location);
        request.getResponseHeaders().set("Cache-Control", "no-store");
        request.sendResponseHeaders(303, -1);
        request.close();
    }    
        private record Account(int userId, String role) {}

    private static java.sql.Connection openDatabase() throws SQLException {
        String user = System.getenv().getOrDefault("FOOD_BANK_DB_USER", "root");
        String password = System.getenv().getOrDefault("FOOD_BANK_DB_PASSWORD", "");

        return DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/food_bank_login_demo", user, password);
    }

    private static Account authenticate(String email, String password, String role)
            throws SQLException {
        String lookupSql = """
            SELECT user_id, role, failed_attempts,
                   locked_until > CURRENT_TIMESTAMP AS is_locked,
                   locked_until IS NOT NULL AS had_lock,
                   password_hash = SHA2(?, 256) AS password_matches
            FROM users
            WHERE email = ? AND status = 'Active'
            FOR UPDATE
            """;

        String updateSql = """
            UPDATE users
            SET failed_attempts = ?,
                locked_until = CASE
                    WHEN ? >= 5 THEN DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 15 MINUTE)
                    ELSE NULL
                END
            WHERE user_id = ?
            """;

        try (var connection = openDatabase()) {
            connection.setAutoCommit(false);

            try {
                int userId;
                String databaseRole;
                int previousFailures;
                boolean validLogin;

                try (var lookup = connection.prepareStatement(lookupSql)) {
                    lookup.setString(1, password);
                    lookup.setString(2, email);

                    try (var rows = lookup.executeQuery()) {
                        if (!rows.next() || rows.getBoolean("is_locked")) {
                            connection.rollback();
                            return null;
                        }

                        userId = rows.getInt("user_id");
                        databaseRole = rows.getString("role");
                        previousFailures = rows.getBoolean("had_lock")
                                ? 0 : rows.getInt("failed_attempts");
                        validLogin = rows.getBoolean("password_matches")
                                && databaseRole.equals(role);
                    }
                }

                int failedAttempts = validLogin ? 0 : previousFailures + 1;

                try (var update = connection.prepareStatement(updateSql)) {
                    update.setInt(1, failedAttempts);
                    update.setInt(2, failedAttempts);
                    update.setInt(3, userId);
                    update.executeUpdate();
                }

                connection.commit();
                return validLogin ? new Account(userId, databaseRole) : null;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    private static String buildDashboard(int userId, String role) throws SQLException {
        String sql = """
            SELECT name, email, phone_number
            FROM users
            WHERE user_id = ? AND status = 'Active'
            """;

        try (var connection = openDatabase()) {
            String profile;

            try (var query = connection.prepareStatement(sql)) {
                query.setInt(1, userId);

                try (var rows = query.executeQuery()) {
                    if (!rows.next()) {
                        return "<p>Account unavailable.</p>";
                    }

                    profile = "<p>Name: " + escape(rows.getString("name"))
                            + "<br>Email: " + escape(rows.getString("email"))
                            + "<br>Phone: " + escape(rows.getString("phone_number")) + "</p>";
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

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void send(HttpExchange request, int status, String content) throws IOException {
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
