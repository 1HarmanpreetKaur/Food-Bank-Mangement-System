import com.mysql.cj.xdevapi.JsonParser;
import com.mysql.cj.xdevapi.JsonString;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
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

    private static final String ADDRESS_PATTERN = "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private static String accessToken;
    private static Instant refreshAt = Instant.EPOCH;

    private static boolean gmailApiMode() {
        return "gmail-api".equalsIgnoreCase(System.getenv("FOOD_BANK_EMAIL_MODE"));
    }

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
                    if (!address.matches(ADDRESS_PATTERN)) {
                        System.err.println("Alert #" + id + " has an unsupported email address.");
                        return;
                    }
                    if ((gmailMode() || gmailApiMode()) && mailboxPort(address, true) != 1025) {
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
        if (gmailApiMode()) {
            sendViaGmailApi(id, address, subject, body);
            return;
        }
        boolean gmail = gmailMode();
        String sender = gmail ? System.getenv().getOrDefault("FOOD_BANK_GMAIL_USER", "").strip() : DEMO_SENDER;
        String password = gmail ? System.getenv().getOrDefault("FOOD_BANK_GMAIL_APP_PASSWORD", "")
                .replace(" ", "") : "";
        if (gmail && (!sender.matches(ADDRESS_PATTERN)
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
        command(output, mimeMessage(id, sender, address, subject, encodedBody) + "\r\n.");
        expect(input, 250);
    }

    private static String mimeMessage(int id, String sender, String address, String subject, String encodedBody) {
        return "From: " + sender + "\r\n"
                + "To: " + address + "\r\n"
                + "Subject: Food Bank - " + subject + "\r\n"
                + "Date: " + DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now()) + "\r\n"
                + "Message-ID: <foodbank-alert-" + id + "@foodbank.test>\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\n"
                + encodedBody;
    }

    private static void sendViaGmailApi(int id, String address, String subject, String body) throws IOException {
        String sender = setting("FOOD_BANK_GMAIL_USER");
        if (!sender.matches(ADDRESS_PATTERN) || !address.matches(ADDRESS_PATTERN)) {
            throw new IOException("Invalid Gmail sender or recipient address.");
        }
        String encodedBody = Base64.getMimeEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        String message = mimeMessage(id, sender, address, subject, encodedBody) + "\r\n";
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(message.getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create("https://gmail.googleapis.com/gmail/v1/users/me/messages/send"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + gmailAccessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"raw\":\"" + raw + "\"}"))
                .build();
        var response = execute(request);
        if (response.statusCode() == 401) {
            synchronized (EmailService.class) {
                refreshAt = Instant.EPOCH;
            }
        }
        if (response.statusCode() != 200) {
            throw new IOException("Gmail API send failed (HTTP " + response.statusCode() + ").");
        }
    }

    private static synchronized String gmailAccessToken() throws IOException {
        if (Instant.now().isBefore(refreshAt)) {
            return accessToken;
        }
        String form = "grant_type=refresh_token"
                + "&client_id=" + encode(setting("FOOD_BANK_GMAIL_CLIENT_ID"))
                + "&client_secret=" + encode(setting("FOOD_BANK_GMAIL_CLIENT_SECRET"))
                + "&refresh_token=" + encode(setting("FOOD_BANK_GMAIL_REFRESH_TOKEN"));
        var request = HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        var response = execute(request);
        if (response.statusCode() != 200) {
            throw new IOException("Google OAuth token refresh failed (HTTP " + response.statusCode() + ").");
        }
        try {
            if (JsonParser.parseDoc(response.body()).get("access_token") instanceof JsonString token
                    && !token.getString().isBlank()) {
                accessToken = token.getString();
                refreshAt = Instant.now().plusSeconds(300);
                return accessToken;
            }
        } catch (RuntimeException error) {
            throw new IOException("Google returned an unreadable OAuth token response.");
        }
        throw new IOException("Google did not return an OAuth access token.");
    }

    private static HttpResponse<String> execute(HttpRequest request) throws IOException {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Gmail API request was interrupted.");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String setting(String name) throws IOException {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IOException("Set " + name + " for Gmail API mode.");
        }
        return value.strip();
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
