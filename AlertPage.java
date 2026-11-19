import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.sql.SQLException;

final class AlertPage {
    private static final String PATH = "/alerts";

    static void register(HttpServer server) {
        server.createContext(PATH, AlertPage::handle);
    }

    private static void handle(HttpExchange request) throws IOException {
        if (!PATH.equals(request.getRequestURI().getPath())) {
            Login.send(request, 404, "<p>Page not found.</p>");
            return;
        }
        String token = Login.sessionToken(request);
        var session = token == null ? null : Login.SESSIONS.get(token);
        if (session == null) {
            Login.redirect(request, "/");
            return;
        }
        if (!"Coordinator".equals(session.role()) && !"Volunteer".equals(session.role())) {
            Login.send(request, 403, "<p>This account cannot view alerts.</p>");
            return;
        }

        try (var db = Login.openDatabase()) {
            var profile = Login.readProfile(db, session.userId());
            if (profile == null || !session.role().equals(profile.role())) {
                Login.redirect(request, "/");
                return;
            }
            String csrf = Login.csrfToken(token);
            switch (request.getRequestMethod()) {
                case "POST" -> {
                    var fields = Login.readForm(request);
                    if (!csrf.equals(fields.get("csrf"))) {
                        Login.send(request, 403, "<p>Reopen the alerts page and try again.</p>");
                        return;
                    }
                    int id = Login.positiveId(Login.value(fields, "alert"), "Alert");
                    switch (Login.value(fields, "action")) {
                        case "read" -> Login.update(db, "UPDATE alerts SET status = 'Read' WHERE alert_id = ? AND user_id = ?",
                                id, profile.id());
                        case "dismiss" -> Login.update(db, """
                                INSERT IGNORE INTO dismissed_alerts (alert_id)
                                SELECT alert_id FROM alerts WHERE alert_id = ? AND user_id = ?
                                """, id, profile.id());
                        default -> throw new Login.FormError("Choose an alert action.");
                    }
                    Login.redirect(request, PATH);
                }
                case "GET" -> {
                    var page = new StringBuilder("<h1>Alerts</h1><ul class='activity-list'>");
                    try (var query = db.prepareStatement("""
                            SELECT a.alert_id, a.message, a.alert_type, a.status, a.created_at
                            FROM alerts a LEFT JOIN dismissed_alerts d USING (alert_id)
                            WHERE a.user_id = ? AND d.alert_id IS NULL
                            ORDER BY a.created_at DESC, a.alert_id DESC
                            """)) {
                        query.setInt(1, profile.id());
                        try (var rows = query.executeQuery()) {
                            if (!rows.isBeforeFirst()) {
                                page.append("<li>No alerts.</li>");
                            }
                            while (rows.next()) {
                                int id = rows.getInt("alert_id");
                                String status = rows.getString("status");
                                page.append("<li><strong>").append(Login.escape(rows.getString("alert_type")))
                                        .append("</strong><span>").append(Login.escape(rows.getString("message")))
                                        .append("</span><small>").append(Login.escape(rows.getString("created_at")))
                                        .append(" · ").append(Login.escape(status)).append("</small>")
                                        .append("<form method='post' action='").append(PATH).append("'>")
                                        .append(Login.hidden("csrf", csrf))
                                        .append(Login.hidden("alert", Integer.toString(id)));
                                if ("Unread".equals(status)) {
                                    page.append("<button name='action' value='read'>Mark as read</button> ");
                                }
                                page.append("<button name='action' value='dismiss'>Dismiss</button></form></li>");
                            }
                        }
                    }
                    Login.send(request, 200, page.append("</ul>").toString());
                }
                default -> {
                    request.getResponseHeaders().set("Allow", "GET, POST");
                    Login.send(request, 405, "<p>Method not allowed.</p>");
                }
            }
        } catch (Login.FormError error) {
            Login.send(request, 400, "<p>" + Login.escape(error.getMessage()) + "</p>");
        } catch (SQLException error) {
            System.err.println("Alerts page: " + error.getMessage());
            Login.send(request, 503, "<p>Alerts are temporarily unavailable.</p>");
        }
    }
}
