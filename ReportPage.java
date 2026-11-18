import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class ReportPage {
    private static final String PATH = "/coordinator/reports";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH);

    record Period(LocalDate start, LocalDate end) {
        static Period of(int year, int month) {
            if (year < 1000 || year > 9998 || month < 0 || month > 12) {
                throw new Login.FormError("Choose a valid year and month.");
            }
            LocalDate start = LocalDate.of(year, month == 0 ? 1 : month, 1);
            return new Period(start, month == 0 ? start.plusYears(1) : start.plusMonths(1));
        }
    }

    static void register(HttpServer server) {
        server.createContext(PATH, ReportPage::handle);
    }

    private static void handle(HttpExchange request) throws IOException {
        if (!PATH.equals(request.getRequestURI().getPath())) {
            Login.send(request, 404, "<p>Page not found.</p>");
            return;
        }
        if (!"GET".equals(request.getRequestMethod())) {
            request.getResponseHeaders().set("Allow", "GET");
            Login.send(request, 405, "<p>Use GET to view reports.</p>");
            return;
        }
        String token = Login.sessionToken(request);
        var account = token == null ? null : Login.SESSIONS.get(token);
        if (account == null) {
            Login.redirect(request, "/");
            return;
        }
        if (!"Coordinator".equals(account.role())) {
            Login.send(request, 403, "<p>Reports are available to coordinators.</p>");
            return;
        }

        try (Connection db = Login.openDatabase()) {
            var profile = Login.readProfile(db, account.userId());
            if (profile == null || !"Coordinator".equals(profile.role())) {
                Login.SESSIONS.remove(token, account);
                Login.redirect(request, "/");
                return;
            }
            LocalDate today = LocalDate.now();
            String rawQuery = request.getRequestURI().getRawQuery();
            var options = Login.parseFields(rawQuery == null ? "" : rawQuery);
            String report = options.getOrDefault("report", "summary");
            if (!"summary".equals(report) && !"waste".equals(report)) {
                throw new Login.FormError("Choose one of the two reports.");
            }
            int year = Integer.parseInt(options.getOrDefault("year", Integer.toString(today.getYear())));
            int month = Integer.parseInt(options.getOrDefault("month", "0"));
            Period period = Period.of(year, month);

            db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            db.setAutoCommit(false);
            String result = "summary".equals(report) ? summary(db, period) : waste(db, period, today);
            db.commit();
            Login.send(request, 200, selector(report, year, month) + result);
        } catch (IllegalArgumentException | Login.FormError error) {
            Login.send(request, 400, "<p>Choose a valid report, year, and month.</p>"
                    + "<p><a href='" + PATH + "'>Back to reports</a></p>");
        } catch (SQLException error) {
            System.err.println("Coordinator reports: " + error.getMessage());
            Login.send(request, 503, "<p>Could not load reports. Try again shortly.</p>");
        }
    }

    private static String selector(String report, int year, int month) {
        StringBuilder months = new StringBuilder(option("0", "Full year", month == 0));
        for (int number = 1; number <= 12; number++) {
            months.append(option(Integer.toString(number), MONTH.format(YearMonth.of(year, number)),
                    number == month));
        }
        return """
                <h1>Coordinator reports</h1>
                <p><a href="/dashboard">Back to dashboard</a></p>
                <form method="get" action="/coordinator/reports">
                  <label>Report <select name="report">%s%s</select></label>
                  <label>Year <input name="year" type="number" min="1000" max="9998" value="%d" required></label>
                  <label>Month <select name="month">%s</select></label>
                  <button type="submit">View report</button>
                </form>
                """.formatted(option("summary", "Donation & Distribution Summary", "summary".equals(report)),
                option("waste", "Inventory Waste Summary", "waste".equals(report)), year, months);
    }

    private static String option(String value, String label, boolean selected) {
        return "<option value=\"" + value + "\"" + (selected ? " selected" : "") + ">"
                + Login.escape(label) + "</option>";
    }

    private static String summary(Connection db, Period period) throws SQLException {
        String donations = table(db, """
                SELECT category, unit, SUM(quantity) AS units_received
                FROM donations WHERE donation_date >= ? AND donation_date < ?
                GROUP BY category, unit ORDER BY category, unit
                """, new String[]{"Category", "Unit", "Quantity received"}, period.start(), period.end());

        long hampers;
        try (var query = db.prepareStatement("""
                SELECT COUNT(*) FROM distributions WHERE distribution_at >= ? AND distribution_at < ?
                """)) {
            Login.bind(query, period.start().atStartOfDay(), period.end().atStartOfDay());
            try (var rows = query.executeQuery()) {
                rows.next();
                hampers = rows.getLong(1);
            }
        }

        String pickups = table(db, """
                SELECT u.name, r.recipient_id,
                       SUM(CASE WHEN p.status = 'Missed' THEN 1 ELSE 0 END) AS missed_count,
                       SUM(CASE WHEN p.status = 'Delayed' THEN 1 ELSE 0 END) AS delayed_count,
                       COUNT(*) AS total
                FROM pickups p
                JOIN hamper_requests h ON h.request_id = p.request_id
                JOIN recipients r ON r.recipient_id = h.recipient_id
                JOIN users u ON u.user_id = r.user_id
                WHERE p.scheduled_at >= ? AND p.scheduled_at < ? AND p.status IN ('Missed', 'Delayed')
                GROUP BY r.recipient_id, u.name ORDER BY total DESC, u.name, r.recipient_id
                """, new String[]{"Recipient", "Recipient ID", "Missed", "Delayed", "Total"},
                period.start().atStartOfDay(), period.end().atStartOfDay());

        return "<h2>Donation &amp; Distribution Summary</h2><h3>Donations received</h3>"
                + donations + "<p><strong>Hampers distributed:</strong> " + hampers + "</p>"
                + "<h3>Missed or delayed pickups by recipient</h3>"
                + "<p>Counts use current pickup status and the scheduled pickup date.</p>" + pickups;
    }

    private static String waste(Connection db, Period period, LocalDate today) throws SQLException {
        String totals = table(db, """
                SELECT category, unit, SUM(quantity_lost) AS total
                FROM (
                    SELECT category, unit, quantity AS quantity_lost, expiry_date AS loss_date
                    FROM inventory WHERE quantity > 0 AND expiry_date < ?
                    UNION ALL
                    SELECT category, unit, wasted_quantity,
                           CASE WHEN expiry_date < waste_date THEN expiry_date ELSE waste_date END
                    FROM inventory WHERE wasted_quantity > 0 AND waste_date IS NOT NULL
                ) losses
                WHERE loss_date >= ? AND loss_date < ?
                GROUP BY category, unit ORDER BY unit, total DESC, category
                """, new String[]{"Category", "Unit", "Expired or wasted quantity"},
                today, period.start(), period.end());
        return "<h2>Inventory Waste Summary</h2>"
                + "<p>Remaining stock is counted on its expiry date once that date has passed. "
                + "Recorded waste uses the waste date, or the expiry date if the waste was recorded later. "
                + "Quantities are ranked within each unit.</p>" + totals;
    }

    private static String table(Connection db, String sql, String[] headings, Object... parameters)
            throws SQLException {
        try (var query = db.prepareStatement(sql)) {
            Login.bind(query, parameters);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    return "<p>No records for this period.</p>";
                }
                var html = new StringBuilder("<table><thead><tr>");
                for (String heading : headings) {
                    html.append("<th scope=\"col\">").append(Login.escape(heading)).append("</th>");
                }
                html.append("</tr></thead><tbody>");
                do {
                    html.append("<tr>");
                    for (int column = 1; column <= headings.length; column++) {
                        html.append("<td>").append(Login.escape(rows.getString(column))).append("</td>");
                    }
                    html.append("</tr>");
                } while (rows.next());
                return html.append("</tbody></table>").toString();
            }
        }
    }
}
