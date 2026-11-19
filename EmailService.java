import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
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
    private static final String DEMO_SENDER = "alerts@foodbank.test";
    private static final String GMAIL_HOST = "smtp.gmail.com";

    private static boolean gmailMode() {
        return "gmail".equalsIgnoreCase(System.getenv("FOOD_BANK_EMAIL_MODE"));
    }

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
                    if (gmailMode() && mailboxPort(address, true) != 1025) {
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

    static void sendAlertEmail(int id, String address, String subject, String body) throws IOException {
        boolean gmail = gmailMode();
        String sender = gmail ? System.getenv().getOrDefault("FOOD_BANK_GMAIL_USER", "").strip() : DEMO_SENDER;
        String password = gmail ? System.getenv().getOrDefault("FOOD_BANK_GMAIL_APP_PASSWORD", "")
                .replace(" ", "") : "";
        if (gmail && (!sender.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+")
                || !password.matches("[A-Za-z0-9]{16}"))) {
            throw new IOException("Set FOOD_BANK_GMAIL_USER and a 16-character Gmail app password.");
        }

        String encodedBody = Base64.getMimeEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        try (Socket socket = new Socket()) {
            if (gmail) {
                socket.connect(new InetSocketAddress(GMAIL_HOST, 465), 5000);
            } else {
                int port = mailboxPort(address, "1".equals(System.getenv("FOOD_BANK_SEPARATE_MAILBOXES")));
                socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            }
            socket.setSoTimeout(gmail ? 10000 : 3000);
            if (gmail) {
                var factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                try (var secured = (SSLSocket) factory.createSocket(socket, GMAIL_HOST, 465, true)) {
                    var parameters = secured.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    secured.setSSLParameters(parameters);
                    secured.setSoTimeout(10000);
                    secured.startHandshake();
                    sendMessage(secured, id, sender, password, address, subject, encodedBody);
                }
            } else {
                sendMessage(socket, id, sender, null, address, subject, encodedBody);
            }
        }
    }

    private static void sendMessage(Socket socket, int id, String sender, String password, String address,
                                    String subject, String encodedBody) throws IOException {
        var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        var output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));

        expect(input, 220);
        command(output, "EHLO foodbank.test");
        expect(input, 250);
        if (password != null) {
            command(output, "AUTH LOGIN");
            expect(input, 334);
            command(output, Base64.getEncoder().encodeToString(sender.getBytes(StandardCharsets.UTF_8)));
            expect(input, 334);
            command(output, Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)));
            expect(input, 235);
        }
        command(output, "MAIL FROM:<" + sender + ">");
        expect(input, 250);
        command(output, "RCPT TO:<" + address + ">");
        expect(input, 250);
        command(output, "DATA");
        expect(input, 354);
        command(output, "From: " + sender + "\r\n"
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

    static int mailboxPort(String address, boolean separateMailboxes) {
        if (!separateMailboxes) {
            return 1025;
        }
        return switch (address.toLowerCase(Locale.ROOT)) {
            case "coordinator1@test.com" -> 1026;
            case "coordinator2@test.com" -> 1027;
            case "volunteer1@test.com" -> 1028;
            case "volunteer2@test.com" -> 1029;
            default -> 1025;
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
                String responseCode = line != null && line.matches("[0-9]{3}.*") ? line.substring(0, 3) : "no reply";
                throw new IOException("SMTP expected " + code + " but received " + responseCode);
            }
        } while (line.charAt(3) == '-');
        if (line.charAt(3) != ' ') {
            throw new IOException("Unexpected SMTP response format.");
        }
    }
}
