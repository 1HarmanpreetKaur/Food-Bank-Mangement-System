import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class EmailService {
    private static final String SENDER = "alerts@foodbank.test";

    static void start() {
        var scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "alert-email-delivery");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try (Connection db = Login.openDatabase()) {
                deliverPending(db);
            } catch (SQLException | RuntimeException error) {
                System.err.println("Alert email check will retry: " + error.getMessage());
            }
        }, 0, 10, TimeUnit.SECONDS);
    }

    private static void deliverPending(Connection db) throws SQLException {
        var ids = new ArrayList<Integer>();
        try (var query = db.prepareStatement("""
                SELECT a.alert_id
                FROM alerts a JOIN users u ON u.user_id = a.user_id
                LEFT JOIN alert_email_deliveries d ON d.alert_id = a.alert_id
                WHERE d.alert_id IS NULL AND u.status = 'Active'
                ORDER BY a.alert_id
                """);
             var rows = query.executeQuery()) {
            while (rows.next()) {
                ids.add(rows.getInt(1));
            }
        }
        db.setAutoCommit(false);
        for (int id : ids) {
            try {
                deliverOne(db, id);
                db.commit();
            } catch (IOException error) {
                Login.rollback(db, error);
                System.err.println("Alert #" + id + " remains queued: " + error.getMessage());
            } catch (SQLException | RuntimeException error) {
                Login.rollback(db, error);
                throw error;
            }
        }
    }

    private static void deliverOne(Connection db, int id) throws SQLException, IOException {
        // Serialize delivery of this alert if another app process is running.
        try (var query = db.prepareStatement("SELECT alert_id FROM alerts WHERE alert_id = ? FOR UPDATE")) {
            query.setInt(1, id);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    return;
                }
            }
        }
        try (var query = db.prepareStatement("""
                SELECT a.alert_type, a.message, u.email
                FROM alerts a JOIN users u ON u.user_id = a.user_id
                LEFT JOIN alert_email_deliveries d ON d.alert_id = a.alert_id
                WHERE a.alert_id = ? AND d.alert_id IS NULL AND u.status = 'Active'
                """)) {
            query.setInt(1, id);
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    String address = rows.getString("email");
                    if (!address.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+")) {
                        System.err.println("Alert #" + id + " has an unsupported email address.");
                        return;
                    }
                    sendAlertEmail(id, address, rows.getString("alert_type"), rows.getString("message"));
                    Login.update(db, """
                            INSERT INTO alert_email_deliveries (alert_id, recipient_email) VALUES (?, ?)
                            """, id, address);
                }
            }
        }
    }

    // Local SMTP only. Mailpit captures the message for the coursework demo.
    static void sendAlertEmail(int id, String address, String subject, String body) throws IOException {
        int port = mailboxPort(address, "1".equals(System.getenv("FOOD_BANK_SEPARATE_MAILBOXES")));
        if (port == 0) {
            throw new IOException("No separate Mailpit inbox is configured for " + address);
        }
        String encodedBody = Base64.getMimeEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(3000);
            var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            var output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));

            expect(input, 220);
            command(output, "EHLO foodbank.test");
            expect(input, 250);
            command(output, "MAIL FROM:<" + SENDER + ">");
            expect(input, 250);
            command(output, "RCPT TO:<" + address + ">");
            expect(input, 250);
            command(output, "DATA");
            expect(input, 354);
            command(output, "From: " + SENDER + "\r\n"
                    + "To: " + address + "\r\n"
                    + "Subject: Food Bank - " + subject + "\r\n"
                    + "Date: " + DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now()) + "\r\n"
                    + "Message-ID: <foodbank-alert-" + id + "@foodbank.test>\r\n"
                    + "MIME-Version: 1.0\r\n"
                    + "Content-Type: text/plain; charset=UTF-8\r\n"
                    + "Content-Transfer-Encoding: base64\r\n\r\n"
                    + encodedBody + "\r\n.");
            expect(input, 250);
        }
    }

    static int mailboxPort(String address, boolean separateMailboxes) {
        if (!separateMailboxes) {
            return 1025;
        }
        return switch (address.toLowerCase(Locale.ROOT)) {
            case "coordinator1@test.com" -> 1026;
            case "coordinator2@test.com" -> 1027;
            case "volunteer1@test.com" -> 1028;
            case "volunteer2@test.com" -> 1029;
            default -> 0;
        };
    }

    private static void command(BufferedWriter output, String line) throws IOException {
        output.write(line);
        output.write("\r\n");
        output.flush();
    }

    private static void expect(BufferedReader input, int expected) throws IOException {
        String code = Integer.toString(expected);
        String line;
        do {
            line = input.readLine();
            if (line == null || !line.startsWith(code) || line.length() < 4) {
                throw new IOException("Mailpit SMTP expected " + code + " but received " + line);
            }
        } while (line.charAt(3) == '-');
        if (line.charAt(3) != ' ') {
            throw new IOException("Unexpected Mailpit SMTP response: " + line);
        }
    }
}
