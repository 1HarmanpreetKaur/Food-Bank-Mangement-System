USE food_bank_login_demo;

CREATE TABLE IF NOT EXISTS inventory_waste_events (
    waste_event_id INT AUTO_INCREMENT PRIMARY KEY,
    inventory_id INT NOT NULL,
    waste_date DATE NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    legacy_inventory_id INT UNIQUE,
    FOREIGN KEY (inventory_id) REFERENCES inventory(inventory_id)
) ENGINE=InnoDB;

INSERT IGNORE INTO inventory_waste_events (inventory_id, waste_date, quantity, legacy_inventory_id)
SELECT i.inventory_id, i.waste_date, i.wasted_quantity, i.inventory_id
FROM inventory i
WHERE i.wasted_quantity > 0 AND i.waste_date IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM inventory_waste_events w WHERE w.inventory_id = i.inventory_id);

CREATE TABLE IF NOT EXISTS pickup_status_events (
    pickup_id INT NOT NULL,
    status ENUM('Missed', 'Delayed') NOT NULL,
    PRIMARY KEY (pickup_id, status),
    FOREIGN KEY (pickup_id) REFERENCES pickups(pickup_id)
) ENGINE=InnoDB;

INSERT IGNORE INTO pickup_status_events (pickup_id, status)
SELECT pickup_id, status FROM pickups WHERE status IN ('Missed', 'Delayed');

INSERT IGNORE INTO pickup_status_events (pickup_id, status)
SELECT p.pickup_id, CASE a.alert_type WHEN 'Missed Pickup' THEN 'Missed' ELSE 'Delayed' END
FROM alerts a JOIN pickups p ON p.pickup_id = a.related_record_id
WHERE a.alert_type IN ('Missed Pickup', 'Delayed Pickup');

CREATE TABLE IF NOT EXISTS expiry_distribution_shifts (
    assignment_id INT NOT NULL PRIMARY KEY,
    FOREIGN KEY (assignment_id) REFERENCES volunteer_assignments(assignment_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS dismissed_alerts (
    alert_id INT NOT NULL PRIMARY KEY,
    FOREIGN KEY (alert_id) REFERENCES alerts(alert_id) ON DELETE CASCADE
) ENGINE=InnoDB;
