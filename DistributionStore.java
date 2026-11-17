import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

final class DistributionStore {
    record Pickup(int id, int requestId, int recipientId, String recipient, LocalDateTime scheduled,
                  String status, String requestStatus) {}
    record Distribution(int id, LocalDateTime time) {}

    private static final String ASSIGNED = """
        SELECT a.assignment_id
        FROM volunteer_assignments a
        JOIN volunteer_assignment_pickups ap ON ap.assignment_id = a.assignment_id
        WHERE a.volunteer_id = ? AND a.status = 'Assigned' AND ap.pickup_id = ?
        """;

    static boolean assigned(Connection db, int volunteer, int pickup, boolean lock) throws SQLException {
        try (var query = db.prepareStatement(ASSIGNED + " LIMIT 1" + (lock ? " FOR UPDATE" : ""))) {
            Login.bind(query, volunteer, pickup);
            try (var rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    static Pickup pickup(Connection db, int pickup, boolean lock) throws SQLException {
        try (var query = db.prepareStatement("""
                SELECT p.pickup_id, p.request_id, h.recipient_id, u.name, p.scheduled_at,
                       p.status, h.status AS request_status
                FROM pickups p JOIN hamper_requests h ON h.request_id = p.request_id
                JOIN recipients r ON r.recipient_id = h.recipient_id JOIN users u ON u.user_id = r.user_id
                WHERE p.pickup_id = ?
                """ + (lock ? " FOR UPDATE" : ""))) {
            query.setInt(1, pickup);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new Login.FormError("This pickup could not be found.");
                }
                return new Pickup(pickup, rows.getInt("request_id"), rows.getInt("recipient_id"),
                        rows.getString("name"), rows.getObject("scheduled_at", LocalDateTime.class),
                        rows.getString("status"), rows.getString("request_status"));
            }
        }
    }

    static Distribution distribution(Connection db, int pickup) throws SQLException {
        try (var query = db.prepareStatement("""
                SELECT distribution_id, distribution_at FROM distributions WHERE pickup_id = ?
                """)) {
            query.setInt(1, pickup);
            try (var rows = query.executeQuery()) {
                return rows.next() ? new Distribution(rows.getInt("distribution_id"),
                        rows.getObject("distribution_at", LocalDateTime.class)) : null;
            }
        }
    }

    static Map<Integer, Integer> items(Connection db, Distribution distribution) throws SQLException {
        Map<Integer, Integer> items = new TreeMap<>();
        if (distribution == null) {
            return items;
        }
        try (var query = db.prepareStatement("""
                SELECT inventory_id, quantity_given FROM distribution_items WHERE distribution_id = ?
                """)) {
            query.setInt(1, distribution.id());
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    items.put(rows.getInt("inventory_id"), rows.getInt("quantity_given"));
                }
            }
        }
        return items;
    }

    static boolean apply(Connection db, int volunteer, int pickupId, Map<String, String> fields)
            throws SQLException {
        db.setAutoCommit(false);
        try {
            if (!assigned(db, volunteer, pickupId, true)) {
                db.rollback();
                return false;
            }
            Pickup pickup = pickup(db, pickupId, true);
            if ("Cancelled".equals(pickup.status()) || "Cancelled".equals(pickup.requestStatus())) {
                throw new Login.FormError("This pickup has been cancelled.");
            }
            Distribution previous = distribution(db, pickupId);
            switch (Login.value(fields, "action")) {
                case "save" -> save(db, volunteer, pickup, previous, fields);
                case "delete" -> {
                    requireCurrentRecord(previous, fields);
                    remove(db, pickup, previous);
                }
                case "status" -> updateStatus(db, pickup, previous, Login.value(fields, "status"));
                default -> throw new Login.FormError("Choose an action for this pickup.");
            }
            db.commit();
            return true;
        } catch (SQLException | RuntimeException error) {
            Login.rollback(db, error);
            throw error;
        }
    }

    private static void save(Connection db, int volunteer, Pickup pickup, Distribution previous,
                             Map<String, String> fields) throws SQLException {
        requireCurrentRecord(previous, fields);
        LocalDateTime date = DistributionPage.distributionTime(Login.value(fields, "distribution_at"));
        Map<Integer, Integer> next = new TreeMap<>();
        for (var entry : fields.entrySet()) {
            if (entry.getKey().startsWith("item_") && !entry.getValue().isBlank()) {
                int id = Login.positiveId(entry.getKey().substring(5), "Inventory item");
                int quantity = Login.positiveId(entry.getValue().strip(), "Quantity");
                next.put(id, quantity);
            }
        }
        if (next.isEmpty()) {
            throw new Login.FormError("Enter a positive quantity for at least one item.");
        }

        adjustStock(db, items(db, previous), next);
        int id;
        if (previous == null) {
            id = insertDistribution(db, volunteer, pickup, date);
        } else {
            id = previous.id();
            Login.update(db, "UPDATE distributions SET distribution_at = ? WHERE distribution_id = ?", date, id);
            Login.update(db, "DELETE FROM distribution_items WHERE distribution_id = ?", id);
        }
        for (var item : next.entrySet()) {
            Login.update(db, """
                INSERT INTO distribution_items (distribution_id, inventory_id, quantity_given) VALUES (?, ?, ?)
                """, id, item.getKey(), item.getValue());
        }
        setStatus(db, pickup, "Fulfilled");
    }

    private static void requireCurrentRecord(Distribution current, Map<String, String> fields) {
        String submitted = Login.value(fields, "distribution_id");
        String expected = current == null ? "" : Integer.toString(current.id());
        if (!expected.equals(submitted)) {
            throw new Login.FormError("The distribution changed. Reopen the pickup before trying again.");
        }
    }

    private static void remove(Connection db, Pickup pickup, Distribution previous) throws SQLException {
        if (previous == null) {
            throw new Login.FormError("There is no distribution to delete.");
        }
        adjustStock(db, items(db, previous), Map.of());
        Login.update(db, "DELETE FROM distribution_items WHERE distribution_id = ?", previous.id());
        Login.update(db, "DELETE FROM distributions WHERE distribution_id = ?", previous.id());
        setStatus(db, pickup, "Pending");
    }

    private static void updateStatus(Connection db, Pickup pickup, Distribution previous, String status)
            throws SQLException {
        if (!status.equals("Fulfilled") && !status.equals("Missed") && !status.equals("Delayed")) {
            throw new Login.FormError("Choose Fulfilled, Missed, or Delayed.");
        }
        if (previous != null && !status.equals("Fulfilled")) {
            throw new Login.FormError("Delete the distribution before marking this pickup missed or delayed.");
        }
        setStatus(db, pickup, status);
    }

    private static void setStatus(Connection db, Pickup pickup, String status) throws SQLException {
        Login.update(db, "UPDATE pickups SET status = ? WHERE pickup_id = ?", status, pickup.id());
        Login.update(db, "UPDATE hamper_requests SET status = ? WHERE request_id = ?",
                status.equals("Fulfilled") ? "Completed" : "Scheduled", pickup.requestId());
    }

    private static int insertDistribution(Connection db, int volunteer, Pickup pickup, LocalDateTime time)
            throws SQLException {
        try (var insert = db.prepareStatement("""
                INSERT INTO distributions (recipient_id, volunteer_id, pickup_id, distribution_at)
                VALUES (?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            Login.bind(insert, pickup.recipientId(), volunteer, pickup.id(), time);
            if (insert.executeUpdate() != 1) {
                throw new SQLException("The distribution was not saved.");
            }
            try (var keys = insert.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("The distribution did not return an ID.");
                }
                return keys.getInt(1);
            }
        }
    }

    private static void adjustStock(Connection db, Map<Integer, Integer> before, Map<Integer, Integer> after)
            throws SQLException {
        var ids = new TreeSet<>(before.keySet());
        ids.addAll(after.keySet());
        LocalDate today = LocalDate.now();
        for (int id : ids) {
            int difference = after.getOrDefault(id, 0) - before.getOrDefault(id, 0);
            try (var query = db.prepareStatement("""
                    SELECT item_name, quantity, expiry_date, status FROM inventory WHERE inventory_id = ? FOR UPDATE
                    """)) {
                query.setInt(1, id);
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) {
                        throw new Login.FormError("Inventory item #" + id + " could not be found.");
                    }
                    int quantity = rows.getInt("quantity");
                    LocalDate expiry = rows.getObject("expiry_date", LocalDate.class);
                    boolean expired = (expiry != null && expiry.isBefore(today))
                            || "Expired".equals(rows.getString("status"));
                    long remaining = (long) quantity - difference;
                    String item = rows.getString("item_name") + " (#" + id + ")";
                    if (difference > 0 && expired) {
                        throw new Login.FormError(item + " has expired.");
                    }
                    if (remaining < 0) {
                        throw new Login.FormError("There is not enough stock for " + item + ".");
                    }
                    if (remaining > Integer.MAX_VALUE) {
                        throw new Login.FormError("Restoring " + item + " would exceed the inventory quantity limit.");
                    }
                    if (difference != 0) {
                        String status = expired ? "Expired" : remaining == 0 ? "Depleted" : "Available";
                        Login.update(db, "UPDATE inventory SET quantity = ?, status = ? WHERE inventory_id = ?",
                                (int) remaining, status, id);
                    }
                }
            }
        }
    }
}
