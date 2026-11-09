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

public class Login {
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
                String account = findAccount(
                        fields.getOrDefault("email", "").strip().toLowerCase(Locale.ROOT),
                        fields.getOrDefault("password", ""), role);
                if (account != null) {
                    send(request, 200, "<p>Logged in as " + role + "</p>" + account);
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

    private static String findAccount(String email, String password, String role) throws SQLException {
        String sql = """
            SELECT user_id, name, email, phone_number
            FROM users
            WHERE email = ? AND password_hash = SHA2(?, 256) AND role = ? AND status = 'Active'
            """;
        String user = System.getenv().getOrDefault("FOOD_BANK_DB_USER", "root");
        String dbPassword = System.getenv().getOrDefault("FOOD_BANK_DB_PASSWORD", "");
        try (var connection = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/food_bank_login_demo", user, dbPassword);
             var query = connection.prepareStatement(sql)) {
            query.setString(1, email);
            query.setString(2, password);
            query.setString(3, role);

            int userId;
            String profile;
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                userId = rows.getInt("user_id");
                profile = "<p>Name: " + escape(rows.getString("name"))
                        + "<br>Email: " + escape(rows.getString("email"))
                        + "<br>Phone: " + escape(rows.getString("phone_number")) + "</p>";
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
