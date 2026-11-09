-- INFO2413 Food Bank Management System | MySQL 8.4
-- Step 02 draft. Run once against a fresh schema. No data is dropped.
CREATE DATABASE IF NOT EXISTS food_bank
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE food_bank;

CREATE TABLE users (
    user_id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    phone_number VARCHAR(25) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role ENUM('donor', 'recipient', 'volunteer', 'coordinator') NOT NULL,
    status ENUM('active', 'deleted') NOT NULL DEFAULT 'active',
    failed_login_attempts INT UNSIGNED NOT NULL DEFAULT 0,
    locked_until DATETIME NULL
) ENGINE=InnoDB;

CREATE TABLE donor (
    donor_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL UNIQUE,
    FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB;

CREATE TABLE recipient (
    recipient_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL UNIQUE,
    household_size INT NOT NULL CHECK (household_size > 0),
    dietary_restrictions TEXT,
    emergency_contact VARCHAR(100) NOT NULL,
    approval_status ENUM('pending', 'approved', 'rejected') NOT NULL DEFAULT 'pending',
    FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB;

CREATE TABLE volunteer (
    volunteer_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL UNIQUE,
    profile_image VARCHAR(255),
    FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB;

CREATE TABLE coordinator (
    coordinator_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL UNIQUE,
    FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB;

CREATE TABLE donation (
    donation_id INT AUTO_INCREMENT PRIMARY KEY,
    donor_id INT NOT NULL,
    item_name VARCHAR(120) NOT NULL,
    category VARCHAR(60) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    unit VARCHAR(30) NOT NULL,
    donation_date DATE NOT NULL,
    expiry_date DATE NULL,
    INDEX (donor_id, donation_date),
    FOREIGN KEY (donor_id) REFERENCES donor (donor_id)
) ENGINE=InnoDB;

CREATE TABLE inventory (
    inventory_id INT AUTO_INCREMENT PRIMARY KEY,
    donation_id INT NOT NULL UNIQUE,
    item_name VARCHAR(120) NOT NULL,
    category VARCHAR(60) NOT NULL,
    quantity INT NOT NULL CHECK (quantity >= 0),
    unit VARCHAR(30) NOT NULL,
    expiry_date DATE NULL,
    status ENUM('available', 'at_risk', 'expired', 'depleted', 'removed') NOT NULL DEFAULT 'available',
    waste_date DATE NULL,
    wasted_quantity INT NOT NULL DEFAULT 0 CHECK (wasted_quantity >= 0),
    CHECK ((waste_date IS NULL AND wasted_quantity = 0)
        OR (waste_date IS NOT NULL AND wasted_quantity > 0)),
    INDEX (status, expiry_date),
    FOREIGN KEY (donation_id) REFERENCES donation (donation_id)
) ENGINE=InnoDB;

CREATE TABLE hamper_request (
    request_id INT AUTO_INCREMENT PRIMARY KEY,
    recipient_id INT NOT NULL,
    request_date DATE NOT NULL,
    status ENUM('pending', 'scheduled', 'fulfilled', 'cancelled') NOT NULL DEFAULT 'pending',
    FOREIGN KEY (recipient_id) REFERENCES recipient (recipient_id)
) ENGINE=InnoDB;

CREATE TABLE pickup (
    pickup_id INT AUTO_INCREMENT PRIMARY KEY,
    request_id INT NOT NULL UNIQUE,
    scheduled_at DATETIME NOT NULL,
    status ENUM('pending', 'fulfilled', 'missed', 'delayed') NOT NULL DEFAULT 'pending',
    status_changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX (status, scheduled_at),
    FOREIGN KEY (request_id) REFERENCES hamper_request (request_id)
) ENGINE=InnoDB;

CREATE TABLE distribution (
    distribution_id INT AUTO_INCREMENT PRIMARY KEY,
    recipient_id INT NOT NULL,
    volunteer_id INT NOT NULL,
    pickup_id INT NOT NULL UNIQUE,
    distribution_at DATETIME NOT NULL,
    INDEX (recipient_id, distribution_at),
    FOREIGN KEY (recipient_id) REFERENCES recipient (recipient_id),
    FOREIGN KEY (volunteer_id) REFERENCES volunteer (volunteer_id),
    FOREIGN KEY (pickup_id) REFERENCES pickup (pickup_id)
) ENGINE=InnoDB;

CREATE TABLE distribution_item (
    distribution_item_id INT AUTO_INCREMENT PRIMARY KEY,
    distribution_id INT NOT NULL,
    inventory_id INT NOT NULL,
    quantity_given INT NOT NULL CHECK (quantity_given > 0),
    UNIQUE (distribution_id, inventory_id),
    FOREIGN KEY (distribution_id) REFERENCES distribution (distribution_id) ON DELETE CASCADE,
    FOREIGN KEY (inventory_id) REFERENCES inventory (inventory_id)
) ENGINE=InnoDB;

CREATE TABLE volunteer_assignment (
    assignment_id INT AUTO_INCREMENT PRIMARY KEY,
    volunteer_id INT NOT NULL,
    assignment_date DATE NOT NULL,
    shift_at DATETIME NOT NULL,
    task_description TEXT,
    status ENUM('scheduled', 'completed', 'cancelled') NOT NULL DEFAULT 'scheduled',
    INDEX (volunteer_id, shift_at),
    FOREIGN KEY (volunteer_id) REFERENCES volunteer (volunteer_id)
) ENGINE=InnoDB;

CREATE TABLE alert (
    alert_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    alert_type ENUM('inventory_at_risk', 'pickup_missed', 'pickup_delayed') NOT NULL,
    related_record_id INT NOT NULL,
    event_key VARCHAR(100) NOT NULL,
    message TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status ENUM('unread', 'read') NOT NULL DEFAULT 'unread',
    email_sent_at DATETIME NULL,
    UNIQUE (user_id, alert_type, related_record_id, event_key),
    FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB;

-- Supporting tables proposed for the feedback and assignment checks.
CREATE TABLE volunteer_availability (
    availability_id INT AUTO_INCREMENT PRIMARY KEY,
    volunteer_id INT NOT NULL,
    available_from DATETIME NOT NULL,
    available_until DATETIME NOT NULL,
    CHECK (available_until > available_from),
    UNIQUE (volunteer_id, available_from, available_until),
    FOREIGN KEY (volunteer_id) REFERENCES volunteer (volunteer_id)
) ENGINE=InnoDB;

CREATE TABLE assignment_pickup (
    assignment_id INT NOT NULL,
    pickup_id INT NOT NULL,
    PRIMARY KEY (assignment_id, pickup_id),
    FOREIGN KEY (assignment_id) REFERENCES volunteer_assignment (assignment_id) ON DELETE CASCADE,
    FOREIGN KEY (pickup_id) REFERENCES pickup (pickup_id)
) ENGINE=InnoDB;
