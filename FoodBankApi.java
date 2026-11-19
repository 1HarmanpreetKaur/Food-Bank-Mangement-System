import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;

final class FoodBankApi {
    private static final String PREFIX = "/api/v1/";

    static void register(HttpServer server) {
        server.createContext(PREFIX, FoodBankApi::handle);
    }

    private static void handle(HttpExchange request) throws IOException {
        if (!"GET".equals(request.getRequestMethod())) {
            request.getResponseHeaders().set("Allow", "GET");
            send(request, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        String path = request.getRequestURI().getPath();
        if (!path.equals(PREFIX + "session") && !path.equals(PREFIX + "activity")) {
            send(request, 404, "{\"error\":\"Endpoint not found\"}");
            return;
        }

        String token = Login.sessionToken(request);
        var account = token == null ? null : Login.SESSIONS.get(token);
        if (account == null) {
            send(request, 401, "{\"error\":\"Sign in to continue\"}");
            return;
        }
        try (var db = Login.openDatabase()) {
            var profile = Login.readProfile(db, account.userId());
            if (profile == null || !account.role().equals(profile.role())) {
                Login.SESSIONS.remove(token, account);
                send(request, 401, "{\"error\":\"Sign in to continue\"}");
            } else if (path.endsWith("/session")) {
                send(request, 200, "{\"name\":" + quote(profile.name()) + ",\"role\":"
                        + quote(profile.role()) + "}");
            } else {
                send(request, 200, activity(db, profile));
            }
        } catch (SQLException error) {
            System.err.println("Dashboard API: " + error.getMessage());
            send(request, 503, "{\"error\":\"Activity is temporarily unavailable\"}");
        }
    }

    private static String activity(Connection db, Login.Profile profile) throws SQLException {
        String sql = switch (profile.role()) {
            case "Donor" -> """
                    SELECT d.item_name AS label, CONCAT(d.quantity, ' ', d.unit) AS detail,
                           d.donation_date AS activity_date
                    FROM donations d JOIN donors o USING (donor_id)
                    WHERE o.user_id = ? ORDER BY d.donation_date DESC, d.donation_id DESC
                    """;
            case "Recipient" -> """
                    SELECT CONCAT('Pickup #', p.pickup_id) AS label, p.status AS detail,
                           p.scheduled_at AS activity_date
                    FROM pickups p JOIN hamper_requests h USING (request_id)
                    JOIN recipients r USING (recipient_id)
                    WHERE r.user_id = ? ORDER BY p.scheduled_at DESC, p.pickup_id DESC
                    """;
            case "Volunteer" -> """
                    SELECT a.task_description AS label, a.status AS detail, a.shift_at AS activity_date
                    FROM volunteer_assignments a JOIN volunteers v USING (volunteer_id)
                    WHERE v.user_id = ? ORDER BY a.shift_at DESC, a.assignment_id DESC
                    """;
            case "Coordinator" -> """
                    SELECT alert_type AS label, message AS detail, created_at AS activity_date
                    FROM alerts a LEFT JOIN dismissed_alerts d USING (alert_id)
                    WHERE a.user_id = ? AND d.alert_id IS NULL ORDER BY a.created_at DESC, a.alert_id DESC
                    """;
            default -> throw new IllegalStateException("Unknown account role");
        };
        var result = new StringBuilder("{\"items\":[");
        try (var query = db.prepareStatement(sql + " LIMIT 5")) {
            query.setInt(1, profile.id());
            try (var rows = query.executeQuery()) {
                boolean first = true;
                while (rows.next()) {
                    if (!first) {
                        result.append(',');
                    }
                    first = false;
                    result.append("{\"label\":").append(quote(rows.getString("label")))
                            .append(",\"detail\":").append(quote(rows.getString("detail")))
                            .append(",\"date\":").append(quote(rows.getString("activity_date")))
                            .append('}');
                }
            }
        }
        return result.append("]}").toString();
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        var json = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                default -> {
                    if (ch < 32) {
                        json.append(String.format("\\u%04x", (int) ch));
                    } else {
                        json.append(ch);
                    }
                }
            }
        }
        return json.append('"').toString();
    }

    private static void send(HttpExchange request, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        request.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        request.getResponseHeaders().set("Cache-Control", "no-store");
        request.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        request.sendResponseHeaders(status, body.length);
        try (var output = request.getResponseBody()) {
            output.write(body);
        }
    }
}
