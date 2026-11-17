-- Run database.sql first on a fresh installation. Existing installations can run this file directly.
USE food_bank_login_demo;

CREATE TABLE IF NOT EXISTS volunteer_assignment_windows (
    assignment_id INT NOT NULL PRIMARY KEY,
    shift_ends_at DATETIME NOT NULL,
    FOREIGN KEY (assignment_id) REFERENCES volunteer_assignments(assignment_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS volunteer_assignment_pickups (
    assignment_id INT NOT NULL,
    pickup_id INT NOT NULL,
    PRIMARY KEY (assignment_id, pickup_id),
    INDEX (pickup_id),
    FOREIGN KEY (assignment_id) REFERENCES volunteer_assignments(assignment_id),
    FOREIGN KEY (pickup_id) REFERENCES pickups(pickup_id)
) ENGINE=InnoDB;
