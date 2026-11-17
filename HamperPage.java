import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

final class HamperPage {
    private static final String PATH = "/recipient/hamper";

    static void register(HttpServer server) {
        server.createContext(PATH, HamperPage::handle);
    }

    private static void handle(HttpExchange request) throws IOException {
        if (!request.getRequestURI().getPath().equals(PATH)) {
            Login.send(request, 404, "<p>Page not found.</p>");
            return;
        }

        String method = request.getRequestMethod();
        boolean post = method.equals("POST");
        if (!post && !method.equals("GET")) {
            request.getResponseHeaders().set("Allow", "GET, POST");
            Login.send(request, 405, "<p>Method not allowed.</p>");
            return;
        }

        String token = Login.sessionToken(request);
        Login.Account account = token == null ? null : Login.SESSIONS.get(token);
        if (account == null) {
            Login.redirect(request, "/");
            return;
        }
        if (!account.role().equals("Recipient")) {
            Login.send(request, 403, "<p>Only recipients can request a hamper.</p>");
            return;
        }

        try (Connection db = Login.openDatabase()) {
            String csrf = Login.csrfToken(token);
            if (post) {
                var fields = Login.readForm(request);
                if (!csrf.equals(fields.get("csrf"))) {
                    Login.send(request, 403, "<p>Reopen the hamper request form and try again.</p>");
                    return;
                }
                if (schedule(db, account.userId(), Login.value(fields, "pickup_at"))) {
                    Login.redirect(request, PATH + "?scheduled=1");
                    return;
                }
                showEligibility(request, findRecipient(db, account.userId(), false));
                return;
            }

            Recipient recipient = findRecipient(db, account.userId(), false);
            if (recipient == null || !recipient.isApproved()) {
                showEligibility(request, recipient);
                return;
            }

            String notice = "scheduled=1".equals(request.getRequestURI().getRawQuery())
                    ? "<p>Hamper requested and pickup scheduled. Check your dashboard for details.</p>" : "";
            Login.send(request, 200, "<h1>Request a hamper</h1>" + notice
                    + "<p><a href='/dashboard'>Back to dashboard</a></p>"
                    + "<form method='post' action='" + PATH + "'>"
                    + Login.hidden("csrf", csrf)
                    + Login.field("Pickup date and time (local time)", "pickup_at", "datetime-local", "", "required")
                    + "<button>Request hamper and schedule pickup</button></form>");
        } catch (Login.FormError error) {
            Login.send(request, 400, "<p>" + Login.escape(error.getMessage()) + "</p>"
                    + "<p><a href='" + PATH + "'>Back to hamper request</a></p>");
        } catch (SQLException error) {
            System.err.println("Hamper scheduling: " + error.getMessage());
            Login.send(request, 503, "<p>Could not open or save the hamper request. Check the server terminal.</p>");
        }
    }

    private static void showEligibility(HttpExchange request, Recipient recipient) throws IOException {
        String message;
        if (recipient == null) {
            message = "An active recipient account is required.";
        } else if (recipient.status().equals("Pending")) {
            message = "Your registration is pending. A coordinator must approve it before you can request a hamper.";
        } else if (recipient.status().equals("Rejected")) {
            message = "Your registration was rejected. Contact a coordinator for help.";
        } else {
            message = "Your account status changed while scheduling. Reopen the form and try again.";
        }
        Login.send(request, 403, "<h1>Request a hamper</h1><p>" + message
                + "</p><p><a href='/dashboard'>Back to dashboard</a></p>");
    }

    private record Recipient(int id, String status) {
        boolean isApproved() {
            return "Approved".equals(status);
        }
    }

    private static Recipient findRecipient(Connection db, int userId, boolean lock) throws SQLException {
        String sql = """
            SELECT r.recipient_id, r.approval_status
            FROM recipients r JOIN users u ON u.user_id = r.user_id
            WHERE r.user_id = ? AND u.role = 'Recipient' AND u.status = 'Active'
            """ + (lock ? " FOR UPDATE" : "");
        try (var query = db.prepareStatement(sql)) {
            query.setInt(1, userId);
            try (var rows = query.executeQuery()) {
                return rows.next() ? new Recipient(rows.getInt("recipient_id"), rows.getString("approval_status"))
                        : null;
            }
        }
    }

    private static boolean schedule(Connection db, int userId, String input) throws SQLException {
        db.setAutoCommit(false);
        try {
            Recipient recipient = findRecipient(db, userId, true);
            if (recipient == null || !recipient.isApproved()) {
                db.rollback();
                return false;
            }

            LocalDateTime now = LocalDateTime.now();
            LocalDateTime pickup = parsePickup(input, now);
            int requestId;
            try (var insert = db.prepareStatement("""
                    INSERT INTO hamper_requests (recipient_id, request_date, status)
                    VALUES (?, ?, 'Scheduled')
                    """, Statement.RETURN_GENERATED_KEYS)) {
                Login.bind(insert, recipient.id(), now.toLocalDate());
                if (insert.executeUpdate() != 1) {
                    throw new SQLException("The hamper request was not saved.");
                }
                try (var keys = insert.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("The hamper request did not return an ID.");
                    }
                    requestId = keys.getInt(1);
                }
            }

            if (Login.update(db, """
                    INSERT INTO pickups (request_id, scheduled_at, status)
                    VALUES (?, ?, 'Pending')
                    """, requestId, pickup) != 1) {
                throw new SQLException("The pickup was not saved.");
            }
            db.commit();
            return true;
        } catch (SQLException | RuntimeException error) {
            Login.rollback(db, error);
            throw error;
        }
    }

    private static LocalDateTime parsePickup(String input, LocalDateTime now) {
        LocalDateTime pickup;
        try {
            if (input.length() != 16) {
                throw new Login.FormError("Choose a valid pickup date and time.");
            }
            pickup = LocalDateTime.parse(input);
            if (pickup.getYear() < 1000 || pickup.getYear() > 9999) {
                throw new Login.FormError("Choose a valid pickup date and time.");
            }
        } catch (DateTimeParseException error) {
            throw new Login.FormError("Choose a valid pickup date and time.");
        }
        if (!pickup.isAfter(now)) {
            throw new Login.FormError("Choose a pickup date and time in the future.");
        }
        return pickup;
    }
}
