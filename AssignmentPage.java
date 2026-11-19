import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

final class AssignmentPage {
    private static final String COORDINATOR = "/coordinator/assignments";
    private static final String VOLUNTEER = "/volunteer/assignments";

    static void register(HttpServer server) {
        server.createContext(COORDINATOR, AssignmentPage::handle);
        server.createContext(VOLUNTEER, AssignmentPage::handle);
    }

    private static void handle(HttpExchange request) throws IOException {
        String path = request.getRequestURI().getPath();
        boolean coordinator = path.equals(COORDINATOR);
        if (!coordinator && !path.equals(VOLUNTEER)) {
            Login.send(request, 404, "<p>Page not found.</p>");
            return;
        }

        String method = request.getRequestMethod();
        boolean post = coordinator && method.equals("POST");
        if (!post && !method.equals("GET")) {
            request.getResponseHeaders().set("Allow", coordinator ? "GET, POST" : "GET");
            Login.send(request, 405, "<p>Method not allowed.</p>");
            return;
        }

        String token = Login.sessionToken(request);
        Login.Account account = token == null ? null : Login.SESSIONS.get(token);
        if (account == null) {
            Login.redirect(request, "/");
            return;
        }
        String role = coordinator ? "Coordinator" : "Volunteer";
        if (!role.equals(account.role())) {
            Login.send(request, 403, "<p>This page is restricted to the " + role + " role.</p>");
            return;
        }

        try (var db = Login.openDatabase()) {
            Login.Profile actor = Login.readProfile(db, account.userId());
            if (actor == null || !role.equals(actor.role())) {
                Login.SESSIONS.remove(token, account);
                Login.redirect(request, "/");
                return;
            }

            String csrf = Login.csrfToken(token);
            if (post) {
                var fields = Login.readForm(request);
                if (!csrf.equals(fields.get("csrf"))) {
                    Login.send(request, 403, "<p>Reopen the assignment page and try again.</p>");
                    return;
                }

                switch (Login.value(fields, "action")) {
                    case "assign" -> createAssignment(db, fields);
                    case "cancel" -> cancelAssignment(db, fields);
                    default -> throw new Login.FormError("Choose an assignment action.");
                }
                Login.redirect(request, COORDINATOR + "?saved=1");
                return;
            }

            String page = coordinator
                    ? coordinatorPage(db, csrf, "saved=1".equals(request.getRequestURI().getRawQuery()))
                    : "<h1>My assignments</h1><p><a href='/dashboard'>Back to dashboard</a></p>"
                        + assignments(db, actor.volunteerId(), null);
            Login.send(request, 200, page);
        } catch (Login.FormError error) {
            Login.send(request, 400, "<p>" + Login.escape(error.getMessage()) + "</p>"
                    + "<p><a href='" + path + "'>Back to assignments</a></p>");
        } catch (SQLException error) {
            System.err.println("Assignments: " + error.getMessage());
            String message = error.getErrorCode() == 1146
                    ? "The assignment tables are missing. Run step07_assignments.sql in MySQL Workbench."
                    : "Could not load or save assignments. Check the server terminal.";
            Login.send(request, 503, "<p>" + message + "</p>");
        }
    }

    private static String coordinatorPage(Connection db, String csrf, boolean saved) throws SQLException {
        LocalDateTime now = LocalDateTime.now();
        var page = new StringBuilder("<h1>Assign volunteers</h1><p><a href='/dashboard'>Back to dashboard</a></p>");
        if (saved) {
            page.append("<p>Assignment updated.</p>");
        }
        page.append("<form method='post' action='" + COORDINATOR + "'>")
                .append(Login.hidden("csrf", csrf)).append(Login.hidden("action", "assign"))
                .append("<p><label>Volunteer <select name='volunteer_id' required>")
                .append("<option value=''>Select a volunteer</option>");
        try (var statement = db.prepareStatement("""
                SELECT v.volunteer_id, u.name, u.email FROM volunteers v JOIN users u ON u.user_id = v.user_id
                WHERE u.status = 'Active' AND u.role = 'Volunteer' ORDER BY u.name, v.volunteer_id
                """); var rows = statement.executeQuery()) {
            while (rows.next()) {
                page.append("<option value='").append(rows.getInt("volunteer_id")).append("'>")
                        .append(Login.escape(rows.getString("name") + " (" + rows.getString("email") + ")"))
                        .append("</option>");
            }
        }
        page.append("</select></label></p>")
                .append(Login.field("Shift starts (local time)", "start", "datetime-local", "", "required"))
                .append(Login.field("Shift ends (local time)", "end", "datetime-local", "", "required"))
                .append(Login.field("Task", "task", "text", "", "required maxlength='255'"))
                .append("<fieldset><legend>Pickups covered by this shift (optional)</legend>")
                .append("<p>Select pickups that fall within the shift. Leave them blank for a general task.</p>")
                .append("<p><label><input type='checkbox' name='distribution_shift' value='1'> Include this shift in expiry alerts even without linked pickups</label></p>");
        try (var statement = db.prepareStatement("""
                SELECT p.pickup_id, p.scheduled_at, u.name
                FROM pickups p JOIN hamper_requests h ON h.request_id = p.request_id
                JOIN recipients r ON r.recipient_id = h.recipient_id JOIN users u ON u.user_id = r.user_id
                WHERE p.status IN ('Pending', 'Delayed') AND h.status = 'Scheduled' AND p.scheduled_at >= ?
                ORDER BY p.scheduled_at, p.pickup_id
                """)) {
            statement.setObject(1, now);
            try (var rows = statement.executeQuery()) {
                boolean available = false;
                while (rows.next()) {
                    available = true;
                    int id = rows.getInt("pickup_id");
                    page.append("<p><label><input type='checkbox' name='pickup_").append(id)
                            .append("' value='1'> #").append(id).append(" ")
                            .append(Login.escape(rows.getString("name"))).append(" — ")
                            .append(Login.escape(rows.getString("scheduled_at"))).append("</label></p>");
                }
                if (!available) {
                    page.append("<p>No upcoming pickups are available to link.</p>");
                }
            }
        }
        page.append("</fieldset><p><button>Save assignment</button></p></form>")
                .append("<h2>Volunteer availability</h2>")
                .append("<p>Use <a href='/coordinator/accounts'>Manage user accounts</a> to add a volunteer's availability.</p>")
                .append("<ul>");
        try (var statement = db.prepareStatement("""
                SELECT u.name, u.email, a.available_date, a.start_time, a.end_time
                FROM volunteer_availability a JOIN volunteers v ON v.volunteer_id = a.volunteer_id
                JOIN users u ON u.user_id = v.user_id WHERE u.status = 'Active' AND a.available_date >= ?
                ORDER BY a.available_date, a.start_time, u.name
                """)) {
            statement.setObject(1, now.toLocalDate());
            try (var rows = statement.executeQuery()) {
                boolean available = false;
                while (rows.next()) {
                    available = true;
                    page.append("<li>").append(Login.escape(rows.getString("name") + " (" + rows.getString("email") + ")"))
                            .append(" — ").append(Login.escape(rows.getString("available_date")))
                            .append(" ").append(Login.escape(rows.getString("start_time")))
                            .append(" to ").append(Login.escape(rows.getString("end_time"))).append("</li>");
                }
                if (!available) {
                    page.append("<li>No upcoming availability has been recorded.</li>");
                }
            }
        }
        return page.append("</ul><h2>Assignments</h2>").append(assignments(db, null, csrf)).toString();
    }

    private static String assignments(Connection db, Integer volunteerId, String csrf) throws SQLException {
        String filter = volunteerId == null ? "" : " WHERE a.volunteer_id = ? ";
        String sql = """
            SELECT a.assignment_id, a.shift_at, w.shift_ends_at, a.task_description, a.status,
                   v_user.name AS volunteer_name, p.pickup_id, p.scheduled_at, r_user.name AS recipient_name
            FROM volunteer_assignments a JOIN volunteers v ON v.volunteer_id = a.volunteer_id
            JOIN users v_user ON v_user.user_id = v.user_id
            LEFT JOIN volunteer_assignment_windows w ON w.assignment_id = a.assignment_id
            LEFT JOIN volunteer_assignment_pickups ap ON ap.assignment_id = a.assignment_id
            LEFT JOIN pickups p ON p.pickup_id = ap.pickup_id
            LEFT JOIN hamper_requests h ON h.request_id = p.request_id
            LEFT JOIN recipients r ON r.recipient_id = h.recipient_id
            LEFT JOIN users r_user ON r_user.user_id = r.user_id
            """ + filter + " ORDER BY a.shift_at DESC, a.assignment_id DESC, p.scheduled_at, p.pickup_id";
        var page = new StringBuilder("<ul>");
        try (var statement = db.prepareStatement(sql)) {
            if (volunteerId != null) {
                statement.setInt(1, volunteerId);
            }
            try (var rows = statement.executeQuery()) {
                int lastId = 0;
                while (rows.next()) {
                    int id = rows.getInt("assignment_id");
                    if (lastId != id) {
                        if (lastId != 0) {
                            page.append("</ul></li>");
                        }
                        lastId = id;
                        String status = rows.getString("status");
                        page.append("<li><p><strong>").append(Login.escape(rows.getString("task_description")))
                                .append("</strong> — ").append(Login.escape(status)).append("</p><p>")
                                .append(Login.escape(rows.getString("volunteer_name"))).append(" — ")
                                .append(Login.escape(rows.getString("shift_at")));
                        String end = rows.getString("shift_ends_at");
                        if (end != null) {
                            page.append(" to ").append(Login.escape(end));
                        }
                        page.append("</p>");
                        if (csrf != null && status.equals("Assigned")) {
                            page.append("<form method='post' action='" + COORDINATOR + "'>")
                                    .append(Login.hidden("csrf", csrf)).append(Login.hidden("action", "cancel"))
                                    .append(Login.hidden("assignment_id", Integer.toString(id)))
                                    .append("<button>Cancel assignment</button></form>");
                        }
                        page.append("<ul>");
                        if (rows.getString("pickup_id") == null) {
                            page.append("<li>No pickups linked.</li>");
                        } else if (volunteerId != null && !status.equals("Assigned")) {
                            page.append("<li>Pickup details are shown for active assignments.</li>");
                        }
                    }
                    if (rows.getString("pickup_id") != null
                            && (volunteerId == null || rows.getString("status").equals("Assigned"))) {
                        page.append("<li>Pickup #").append(rows.getInt("pickup_id")).append(" — ")
                                .append(Login.escape(rows.getString("recipient_name"))).append(" — ")
                                .append(Login.escape(rows.getString("scheduled_at"))).append("</li>");
                    }
                }
                if (lastId == 0) {
                    page.append("<li>No assignments yet.</li>");
                } else {
                    page.append("</ul></li>");
                }
            }
        }
        return page.append("</ul>").toString();
    }

    private static void createAssignment(Connection db, Map<String, String> form) throws SQLException {
        int volunteer = Login.positiveId(Login.value(form, "volunteer_id"), "Volunteer");
        String task = Login.required(form, "task", "Task", 255);
        LocalDateTime start = parseDateTime(Login.value(form, "start"), "Shift start");
        LocalDateTime end = parseDateTime(Login.value(form, "end"), "Shift end");
        LocalDateTime now = LocalDateTime.now();
        validateWindow(start, end, now);

        var pickups = new java.util.TreeSet<Integer>();
        for (var entry : form.entrySet()) {
            if (entry.getKey().startsWith("pickup_") && "1".equals(entry.getValue())) {
                pickups.add(Login.positiveId(entry.getKey().substring(7), "Pickup"));
            }
        }

        db.setAutoCommit(false);
        try {
            // Serialize assignments for a volunteer so simultaneous requests cannot double-book them.
            lockVolunteer(db, volunteer);
            requireAvailability(db, volunteer, start, end);
            checkOverlap(db, volunteer, start, end);
            for (int pickup : pickups) {
                lockPickup(db, pickup, start, end);
            }

            int id = insertAssignment(db, volunteer, task, start, now);
            Login.update(db, "INSERT INTO volunteer_assignment_windows VALUES (?, ?)", id, end);
            if ("1".equals(Login.value(form, "distribution_shift"))) {
                Login.update(db, "INSERT INTO expiry_distribution_shifts (assignment_id) VALUES (?)", id);
            }
            for (int pickup : pickups) {
                Login.update(db, "INSERT INTO volunteer_assignment_pickups VALUES (?, ?)", id, pickup);
            }
            db.commit();
        } catch (SQLException | RuntimeException error) {
            Login.rollback(db, error);
            throw error;
        }
    }

    private static void validateWindow(LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        if (start.isBefore(now.withSecond(0).withNano(0))) {
            throw new Login.FormError("Choose a shift that starts now or later.");
        }
        if (!end.isAfter(start) || !end.toLocalDate().equals(start.toLocalDate())) {
            throw new Login.FormError("The shift must start and end on the same date, with the end after the start.");
        }
    }

    private static LocalDateTime parseDateTime(String input, String label) {
        if (input.length() == 16) {
            try {
                LocalDateTime value = LocalDateTime.parse(input);
                if (value.getYear() >= 1000 && value.getYear() <= 9999) {
                    return value;
                }
            } catch (DateTimeParseException error) {
                // Use the same message for all invalid dates.
            }
        }
        throw new Login.FormError(label + " must be a valid local date and time.");
    }

    static void lockVolunteer(Connection db, int volunteer) throws SQLException {
        try (var statement = db.prepareStatement("""
                SELECT v.volunteer_id FROM volunteers v JOIN users u ON u.user_id = v.user_id
                WHERE v.volunteer_id = ? AND u.role = 'Volunteer' AND u.status = 'Active' FOR UPDATE
                """)) {
            statement.setInt(1, volunteer);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new Login.FormError("Select an active volunteer.");
                }
            }
        }
    }

    private static void requireAvailability(Connection db, int volunteer, LocalDateTime start, LocalDateTime end)
            throws SQLException {
        try (var statement = db.prepareStatement("""
                SELECT availability_id FROM volunteer_availability
                WHERE volunteer_id = ? AND available_date = ? AND start_time <= ? AND end_time >= ?
                LIMIT 1 FOR UPDATE
                """)) {
            Login.bind(statement, volunteer, start.toLocalDate(), start.toLocalTime(), end.toLocalTime());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new Login.FormError("The volunteer has no saved availability covering the full shift.");
                }
            }
        }
    }

    private static void checkOverlap(Connection db, int volunteer, LocalDateTime start, LocalDateTime end)
            throws SQLException {
        try (var statement = db.prepareStatement("""
                SELECT a.assignment_id FROM volunteer_assignments a
                LEFT JOIN volunteer_assignment_windows w ON w.assignment_id = a.assignment_id
                WHERE a.volunteer_id = ? AND a.status = 'Assigned' AND a.shift_at < ?
                  AND ((w.shift_ends_at IS NULL AND a.shift_at >= ?) OR w.shift_ends_at > ?)
                LIMIT 1
                """)) {
            Login.bind(statement, volunteer, end, start, start);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    throw new Login.FormError("This volunteer already has an overlapping assignment.");
                }
            }
        }
    }

    private static void lockPickup(Connection db, int pickup, LocalDateTime start, LocalDateTime end)
            throws SQLException {
        try (var statement = db.prepareStatement("""
                SELECT p.pickup_id FROM pickups p JOIN hamper_requests h ON h.request_id = p.request_id
                WHERE p.pickup_id = ? AND p.status IN ('Pending', 'Delayed') AND h.status = 'Scheduled'
                  AND p.scheduled_at >= ? AND p.scheduled_at < ? FOR UPDATE
                """)) {
            Login.bind(statement, pickup, start, end);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new Login.FormError("Pickup #" + pickup
                            + " must still be scheduled and fall within the shift.");
                }
            }
        }
    }

    private static int insertAssignment(Connection db, int volunteer, String task, LocalDateTime start,
                                        LocalDateTime now) throws SQLException {
        try (var statement = db.prepareStatement("""
                INSERT INTO volunteer_assignments (volunteer_id, assignment_date, shift_at, task_description)
                VALUES (?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            Login.bind(statement, volunteer, now.toLocalDate(), start, task);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("The assignment was not saved.");
            }
            try (var keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("The assignment did not return an ID.");
                }
                return keys.getInt(1);
            }
        }
    }

    private static void cancelAssignment(Connection db, Map<String, String> form) throws SQLException {
        int id = Login.positiveId(Login.value(form, "assignment_id"), "Assignment");
        if (Login.update(db, """
                UPDATE volunteer_assignments SET status = 'Cancelled'
                WHERE assignment_id = ? AND status = 'Assigned'
                """, id) != 1) {
            throw new Login.FormError("Only an active assignment can be cancelled.");
        }
    }
}
