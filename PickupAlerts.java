import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class PickupAlerts {
    private static final int DELAY_MINUTES = 30;
    private static final int MISSED_HOURS = 2;
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    static void start() {
        var scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "pickup-status-check");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try (Connection db = Login.openDatabase()) {
                checkOverdue(db, LocalDateTime.now());
            } catch (SQLException | RuntimeException error) {
                System.err.println("Pickup check could not complete: " + error.getMessage());
            }
        }, 0, 10, TimeUnit.SECONDS);
    }

    static String overdueStatus(String status, LocalDateTime scheduled, LocalDateTime now) {
        if (!"Pending".equals(status) && !"Delayed".equals(status)) {
            return status;
        }
        if (!now.isBefore(scheduled.plusHours(MISSED_HOURS))) {
            return "Missed";
        }
        if ("Pending".equals(status) && !now.isBefore(scheduled.plusMinutes(DELAY_MINUTES))) {
            return "Delayed";
        }
        return status;
    }

    static void checkOverdue(Connection db, LocalDateTime now) throws SQLException {
        var ids = new ArrayList<Integer>();
        try (var query = db.prepareStatement("""
                SELECT p.pickup_id
                FROM pickups p
                JOIN hamper_requests h ON h.request_id = p.request_id
                WHERE h.status <> 'Cancelled'
                  AND ((p.status = 'Pending' AND p.scheduled_at <= ?)
                    OR (p.status = 'Delayed' AND p.scheduled_at <= ?))
                ORDER BY p.pickup_id
                """)) {
            Login.bind(query, now.minusMinutes(DELAY_MINUTES), now.minusHours(MISSED_HOURS));
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getInt("pickup_id"));
                }
            }
        }

        db.setAutoCommit(false);
        for (int id : ids) {
            try {
                var pickup = DistributionStore.pickup(db, id, true);
                String next = overdueStatus(pickup.status(), pickup.scheduled(), now);
                if (!next.equals(pickup.status()) && !"Cancelled".equals(pickup.requestStatus())) {
                    Login.update(db, "UPDATE pickups SET status = ? WHERE pickup_id = ?", next, id);
                    Login.update(db, "UPDATE hamper_requests SET status = 'Scheduled' WHERE request_id = ?",
                            pickup.requestId());
                    queue(db, pickup, next);
                }
                db.commit();
            } catch (SQLException | RuntimeException error) {
                Login.rollback(db, error);
                throw error;
            }
        }
    }

    static void queue(Connection db, DistributionStore.Pickup pickup, String status) throws SQLException {
        if (!"Delayed".equals(status) && !"Missed".equals(status)) {
            return;
        }
        String message = "Pickup #%d for %s, scheduled for %s, was marked %s."
                .formatted(pickup.id(), pickup.recipient(), pickup.scheduled().format(DISPLAY_TIME), status);
        Login.update(db, """
                INSERT INTO alerts (user_id, alert_type, related_record_id, message)
                SELECT user_id, ?, ?, ? FROM users
                WHERE role = 'Coordinator' AND status = 'Active'
                """, status + " Pickup", pickup.id(), message);
    }
}
