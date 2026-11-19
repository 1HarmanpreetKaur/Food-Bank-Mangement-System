import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class AnalyzerEngine {
    private static final int HISTORY_DAYS = 30;
    private static final int CHECK_INTERVAL_MINUTES = 5;

    private record Item(String name, String unit) {
        static Item from(String name, String unit) {
            return new Item(name.strip().toLowerCase(Locale.ROOT),
                    unit.strip().toLowerCase(Locale.ROOT));
        }
    }

    private record Batch(int id, String name, String unit, int quantity, LocalDate expiry) {}
    private record History(long hamperCount, Map<Item, Long> quantities) {}
    private record UpcomingPickup(LocalDate date, Set<Integer> volunteerIds) {}
    private record DistributionShift(LocalDate date, int userId) {}
    private record Risk(Batch batch, int quantity, String reason) {}

    static void start() {
        var scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "expiry-risk-analyzer");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try (Connection db = Login.openDatabase()) {
                int alerts = analyzeInventoryData(db, LocalDateTime.now());
                if (alerts > 0) {
                    System.out.println("Expiry analyzer created " + alerts + " alert(s).");
                }
            } catch (SQLException | RuntimeException error) {
                System.err.println("Expiry analyzer could not complete: " + error.getMessage());
            }
        }, 0, CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    // Uses its own connection so the forecast reads a consistent snapshot.
    static int analyzeInventoryData(Connection db, LocalDateTime now) throws SQLException {
        db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        db.setAutoCommit(false);
        try {
            List<Batch> batches = readBatches(db, now.toLocalDate());
            if (batches.isEmpty()) {
                db.commit();
                return 0;
            }
            History history = readHistory(db, now);
            List<UpcomingPickup> pickups = readUpcomingPickups(db, now,
                    batches.get(batches.size() - 1).expiry());
            Set<Integer> coordinators = readCoordinatorIds(db);
            List<DistributionShift> shifts = readDistributionShifts(db, now,
                    batches.get(batches.size() - 1).expiry());
            int alerts = 0;
            for (Risk risk : identifyStockTrend(batches, history, pickups)) {
                alerts += saveAlerts(db, risk, recipientsFor(risk, coordinators, pickups, shifts));
            }
            db.commit();
            return alerts;
        } catch (SQLException | RuntimeException error) {
            Login.rollback(db, error);
            throw error;
        }
    }

    private static List<Batch> readBatches(Connection db, LocalDate today) throws SQLException {
        String sql = """
                SELECT inventory_id, item_name, unit, quantity, expiry_date
                FROM inventory
                WHERE status = 'Available' AND quantity > 0 AND expiry_date >= ?
                ORDER BY expiry_date, inventory_id
                """;
        List<Batch> batches = new ArrayList<>();
        try (var query = db.prepareStatement(sql)) {
            query.setObject(1, today);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    batches.add(new Batch(rows.getInt("inventory_id"), rows.getString("item_name"),
                            rows.getString("unit"), rows.getInt("quantity"),
                            rows.getObject("expiry_date", LocalDate.class)));
                }
            }
        }
        return batches;
    }

    private static History readHistory(Connection db, LocalDateTime now) throws SQLException {
        String sql = """
                SELECT d.distribution_id, i.item_name, i.unit, di.quantity_given
                FROM distributions d
                JOIN pickups p ON p.pickup_id = d.pickup_id
                LEFT JOIN distribution_items di ON di.distribution_id = d.distribution_id
                LEFT JOIN inventory i ON i.inventory_id = di.inventory_id
                WHERE p.status = 'Fulfilled' AND d.distribution_at >= ? AND d.distribution_at <= ?
                ORDER BY d.distribution_id
                """;
        Map<Item, Long> quantities = new HashMap<>();
        long hampers = 0;
        int previousId = 0;
        try (var query = db.prepareStatement(sql)) {
            Login.bind(query, now.minusDays(HISTORY_DAYS), now);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    int id = rows.getInt("distribution_id");
                    if (id != previousId) {
                        hampers++;
                        previousId = id;
                    }

                    String itemName = rows.getString("item_name");
                    if (itemName != null) {
                        Item item = Item.from(itemName, rows.getString("unit"));
                        quantities.merge(item, rows.getLong("quantity_given"), Long::sum);
                    }
                }
            }
        }
        return new History(hampers, quantities);
    }

    private static List<UpcomingPickup> readUpcomingPickups(Connection db, LocalDateTime now,
                                                            LocalDate lastExpiry) throws SQLException {
        String sql = """
                SELECT p.pickup_id, p.scheduled_at, assigned.user_id
                FROM pickups p
                LEFT JOIN (
                    SELECT ap.pickup_id, u.user_id
                    FROM volunteer_assignment_pickups ap
                    JOIN volunteer_assignments a ON a.assignment_id = ap.assignment_id
                    JOIN volunteers v ON v.volunteer_id = a.volunteer_id
                    JOIN users u ON u.user_id = v.user_id
                    WHERE a.status = 'Assigned' AND u.status = 'Active' AND u.role = 'Volunteer'
                ) assigned ON assigned.pickup_id = p.pickup_id
                WHERE p.status = 'Pending' AND p.scheduled_at >= ? AND p.scheduled_at < ?
                ORDER BY p.scheduled_at, p.pickup_id, assigned.user_id
                """;
        List<UpcomingPickup> pickups = new ArrayList<>();
        try (var query = db.prepareStatement(sql)) {
            Login.bind(query, now, lastExpiry.plusDays(1).atStartOfDay());
            try (var rows = query.executeQuery()) {
                int previousId = 0;
                UpcomingPickup pickup = null;
                while (rows.next()) {
                    int id = rows.getInt("pickup_id");
                    if (id != previousId) {
                        LocalDate date = rows.getObject("scheduled_at", LocalDateTime.class).toLocalDate();
                        pickup = new UpcomingPickup(date, new LinkedHashSet<>());
                        pickups.add(pickup);
                        previousId = id;
                    }
                    int volunteerId = rows.getInt("user_id");
                    if (!rows.wasNull()) {
                        pickup.volunteerIds().add(volunteerId);
                    }
                }
            }
        }
        return pickups;
    }

    private static List<Risk> identifyStockTrend(List<Batch> batches, History history,
                                                List<UpcomingPickup> pickups) {
        Map<Item, BigInteger> allocated = new HashMap<>();
        List<Risk> risks = new ArrayList<>();
        int pickupCount = 0;
        for (Batch batch : batches) {
            while (pickupCount < pickups.size() && !pickups.get(pickupCount).date().isAfter(batch.expiry())) {
                pickupCount++;
            }

            Item item = Item.from(batch.name(), batch.unit());
            long distributed = history.quantities().getOrDefault(item, 0L);
            if (pickupCount == 0 || distributed == 0) {
                String reason = pickupCount == 0 ? "No upcoming pickups by expiry" : "No distribution history";
                risks.add(new Risk(batch, batch.quantity(), reason));
                continue;
            }

            // Round the total down to whole units, then subtract units already forecast for earlier batches.
            BigInteger capacity = BigInteger.valueOf(distributed).multiply(BigInteger.valueOf(pickupCount))
                    .divide(BigInteger.valueOf(history.hamperCount()));
            BigInteger used = allocated.getOrDefault(item, BigInteger.ZERO);
            int expected = capacity.subtract(used).min(BigInteger.valueOf(batch.quantity())).intValueExact();
            allocated.put(item, used.add(BigInteger.valueOf(expected)));
            if (expected < batch.quantity()) {
                risks.add(new Risk(batch, batch.quantity() - expected, "Expected to remain at expiry"));
            }
        }
        return risks;
    }

    private static Set<Integer> readCoordinatorIds(Connection db) throws SQLException {
        Set<Integer> ids = new LinkedHashSet<>();
        try (var query = db.prepareStatement("""
                SELECT user_id FROM users WHERE role = 'Coordinator' AND status = 'Active' ORDER BY user_id
                """);
             var rows = query.executeQuery()) {
            while (rows.next()) {
                ids.add(rows.getInt("user_id"));
            }
        }
        return ids;
    }

    private static List<DistributionShift> readDistributionShifts(Connection db, LocalDateTime now,
                                                                  LocalDate lastExpiry) throws SQLException {
        var shifts = new ArrayList<DistributionShift>();
        try (var query = db.prepareStatement("""
                SELECT DATE(a.shift_at) AS shift_date, u.user_id
                FROM expiry_distribution_shifts s
                JOIN volunteer_assignments a USING (assignment_id)
                JOIN volunteer_assignment_windows w USING (assignment_id)
                JOIN volunteers v ON v.volunteer_id = a.volunteer_id
                JOIN users u ON u.user_id = v.user_id
                WHERE a.status = 'Assigned' AND u.status = 'Active'
                  AND w.shift_ends_at > ? AND a.shift_at < ?
                """)) {
            Login.bind(query, now, lastExpiry.plusDays(1).atStartOfDay());
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    shifts.add(new DistributionShift(rows.getObject("shift_date", LocalDate.class),
                            rows.getInt("user_id")));
                }
            }
        }
        return shifts;
    }

    private static Set<Integer> recipientsFor(Risk risk, Set<Integer> coordinators,
                                              List<UpcomingPickup> pickups, List<DistributionShift> shifts) {
        Set<Integer> recipients = new LinkedHashSet<>(coordinators);
        for (UpcomingPickup pickup : pickups) {
            if (pickup.date().isAfter(risk.batch().expiry())) {
                break;
            }
            recipients.addAll(pickup.volunteerIds());
        }
        for (DistributionShift shift : shifts) {
            if (!shift.date().isAfter(risk.batch().expiry())) {
                recipients.add(shift.userId());
            }
        }
        return recipients;
    }

    private static int saveAlerts(Connection db, Risk risk, Set<Integer> recipients) throws SQLException {
        Batch batch = risk.batch();
        String message = "%s (batch #%d) expires on %s. At risk: %d %s out of %d in stock. %s."
                .formatted(batch.name(), batch.id(), batch.expiry(), risk.quantity(), batch.unit(),
                        batch.quantity(), risk.reason());
        int saved = 0;
        for (int userId : recipients) {
            int claimed = Login.update(db, """
                    INSERT IGNORE INTO expiry_alert_keys (inventory_id, expiry_date, user_id)
                    VALUES (?, ?, ?)
                    """, batch.id(), batch.expiry(), userId);
            if (claimed == 1) {
                saved += Login.update(db, """
                        INSERT INTO alerts (user_id, alert_type, related_record_id, message)
                        VALUES (?, 'Expiry Risk', ?, ?)
                        """, userId, batch.id(), message);
            }
        }
        return saved;
    }
}
