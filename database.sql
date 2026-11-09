CREATE DATABASE IF NOT EXISTS food_bank_login_demo;
USE food_bank_login_demo;

CREATE TABLE IF NOT EXISTS users (
    user_id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(120) NOT NULL UNIQUE,
    password_hash CHAR(64) NOT NULL,
    role ENUM('Donor', 'Recipient', 'Volunteer', 'Coordinator') NOT NULL
);

INSERT INTO users (name, email, password_hash, role) VALUES
    ('Inderpreet Kaur', 'donor1@test.com', SHA2('donorProject#123', 256), 'Donor'),
    ('Harmanpreet Kaur', 'donor2@test.com', SHA2('donorProject#123', 256), 'Donor'),
    ('Aadil Bhana', 'recipient1@test.com', SHA2('recipientProject#123', 256), 'Recipient'),
    ('Natasha', 'recipient2@test.com', SHA2('recipientProject#123', 256), 'Recipient'),
    ('Gurshan Singh', 'volunteer1@test.com', SHA2('volunteerProject#123', 256), 'Volunteer'),
    ('Nixon Tran', 'volunteer2@test.com', SHA2('volunteerProject#123', 256), 'Volunteer'),
    ('Dasnoor Singh', 'coordinator1@test.com', SHA2('coordinatorProject#123', 256), 'Coordinator'),
    ('Daljit Singh', 'coordinator2@test.com', SHA2('coordinatorProject#123', 256), 'Coordinator');

SET @add_phone = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'phone_number'),
    'DO 0', 'ALTER TABLE users ADD COLUMN phone_number VARCHAR(25)');
PREPARE migration FROM @add_phone;
EXECUTE migration;
DEALLOCATE PREPARE migration;

SET @add_status = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'status'),
    'DO 0', 'ALTER TABLE users ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT ''Active''');
PREPARE migration FROM @add_status;
EXECUTE migration;
DEALLOCATE PREPARE migration;

CREATE TABLE IF NOT EXISTS donors (
    donor_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL UNIQUE,
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

CREATE TABLE IF NOT EXISTS recipients (
    recipient_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL UNIQUE,
    household_size INT NOT NULL CHECK (household_size > 0),
    dietary_restrictions TEXT,
    emergency_contact VARCHAR(100),
    approval_status ENUM('Pending','Approved','Rejected') NOT NULL DEFAULT 'Pending',
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

CREATE TABLE IF NOT EXISTS volunteers (
    volunteer_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL UNIQUE,
    profile_image VARCHAR(255),
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

CREATE TABLE IF NOT EXISTS coordinators (
    coordinator_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL UNIQUE,
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

CREATE TABLE IF NOT EXISTS volunteer_availability (
    availability_id INT AUTO_INCREMENT PRIMARY KEY,
    volunteer_id INT NOT NULL,
    available_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
	CHECK (end_time > start_time),
    FOREIGN KEY (volunteer_id) REFERENCES volunteers(volunteer_id)
);

CREATE TABLE IF NOT EXISTS donations (
    donation_id INT AUTO_INCREMENT PRIMARY KEY,
    donor_id INT NOT NULL,
    entered_by_volunteer_id INT NOT NULL,
    item_name VARCHAR(120) NOT NULL,
    category VARCHAR(60) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    unit VARCHAR(30) NOT NULL,
    donation_date DATE NOT NULL,
    expiry_date DATE,
    FOREIGN KEY (donor_id) REFERENCES donors(donor_id),
    FOREIGN KEY (entered_by_volunteer_id) REFERENCES volunteers(volunteer_id)
);

CREATE TABLE IF NOT EXISTS inventory (
    inventory_id INT AUTO_INCREMENT PRIMARY KEY,
    donation_id INT NOT NULL UNIQUE,
    item_name VARCHAR(120) NOT NULL,
    category VARCHAR(60) NOT NULL,
    quantity INT NOT NULL CHECK (quantity >= 0),
    unit VARCHAR(30) NOT NULL,
    expiry_date DATE,
    status ENUM('Available','Depleted','Expired') NOT NULL DEFAULT 'Available',
    waste_date DATE,
    wasted_quantity INT NOT NULL DEFAULT 0 CHECK (wasted_quantity >= 0),
    CHECK (wasted_quantity = 0 OR waste_date IS NOT NULL),
    FOREIGN KEY (donation_id) REFERENCES donations(donation_id)
);

CREATE TABLE IF NOT EXISTS hamper_requests (
    request_id INT AUTO_INCREMENT PRIMARY KEY,
    recipient_id INT NOT NULL,
    request_date DATE NOT NULL,
    status ENUM('Pending','Scheduled','Completed','Cancelled') NOT NULL DEFAULT 'Pending',
    FOREIGN KEY (recipient_id) REFERENCES recipients(recipient_id)
);

CREATE TABLE IF NOT EXISTS pickups (
    pickup_id INT AUTO_INCREMENT PRIMARY KEY,
    request_id INT NOT NULL UNIQUE,
    scheduled_at DATETIME NOT NULL,
    status ENUM('Pending','Fulfilled','Delayed','Missed','Cancelled') NOT NULL DEFAULT 'Pending',
    FOREIGN KEY (request_id) REFERENCES hamper_requests(request_id)
);

CREATE TABLE IF NOT EXISTS distributions (
    distribution_id INT AUTO_INCREMENT PRIMARY KEY,
    recipient_id INT NOT NULL,
    volunteer_id INT NOT NULL,
    pickup_id INT NOT NULL UNIQUE,
    distribution_at DATETIME NOT NULL,
    FOREIGN KEY (recipient_id) REFERENCES recipients(recipient_id),
    FOREIGN KEY (volunteer_id) REFERENCES volunteers(volunteer_id),
    FOREIGN KEY (pickup_id) REFERENCES pickups(pickup_id)
);

CREATE TABLE IF NOT EXISTS distribution_items (
    distribution_item_id INT AUTO_INCREMENT PRIMARY KEY,
    distribution_id INT NOT NULL,
    inventory_id INT NOT NULL,
    quantity_given INT NOT NULL CHECK (quantity_given > 0),
    UNIQUE (distribution_id, inventory_id),
    FOREIGN KEY (distribution_id) REFERENCES distributions(distribution_id),
    FOREIGN KEY (inventory_id) REFERENCES inventory(inventory_id)
);

CREATE TABLE IF NOT EXISTS volunteer_assignments (
    assignment_id INT AUTO_INCREMENT PRIMARY KEY,
    volunteer_id INT NOT NULL,
    assignment_date DATE NOT NULL,
    shift_at DATETIME NOT NULL,
    task_description TEXT NOT NULL,
    status ENUM('Assigned','Completed','Cancelled') NOT NULL DEFAULT 'Assigned',
    FOREIGN KEY (volunteer_id) REFERENCES volunteers(volunteer_id)
);

CREATE TABLE IF NOT EXISTS alerts (
    alert_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    alert_type ENUM('Expiry Risk','Delayed Pickup','Missed Pickup') NOT NULL,
    related_record_id INT NOT NULL,
    message TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status ENUM('Unread','Read') NOT NULL DEFAULT 'Unread',
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

START TRANSACTION;

UPDATE users SET phone_number = CONCAT('604-555-010',
    FIELD(email, 'donor1@test.com', 'donor2@test.com', 'recipient1@test.com',
        'recipient2@test.com', 'volunteer1@test.com', 'volunteer2@test.com',
        'coordinator1@test.com', 'coordinator2@test.com') - 1)
WHERE phone_number IS NULL AND email IN (
    'donor1@test.com', 'donor2@test.com', 'recipient1@test.com', 'recipient2@test.com',
    'volunteer1@test.com', 'volunteer2@test.com', 'coordinator1@test.com', 'coordinator2@test.com');

INSERT IGNORE INTO donors (user_id)
SELECT user_id FROM users WHERE role = 'Donor' AND email IN ('donor1@test.com','donor2@test.com');
INSERT IGNORE INTO recipients (user_id, household_size, dietary_restrictions, emergency_contact, approval_status)
SELECT user_id, IF(email = 'recipient1@test.com', 3, 2),
       IF(email = 'recipient1@test.com', 'Vegetarian', 'No restrictions'),
       '604-555-0110', 'Approved'
FROM users WHERE role = 'Recipient' AND email IN ('recipient1@test.com','recipient2@test.com');
INSERT IGNORE INTO volunteers (user_id)
SELECT user_id FROM users WHERE role = 'Volunteer' AND email IN ('volunteer1@test.com','volunteer2@test.com');
INSERT IGNORE INTO coordinators (user_id)
SELECT user_id FROM users WHERE role = 'Coordinator' AND email IN ('coordinator1@test.com','coordinator2@test.com');

SET @donor1 = (SELECT donor_id FROM donors JOIN users USING (user_id) WHERE email = 'donor1@test.com');
SET @donor2 = (SELECT donor_id FROM donors JOIN users USING (user_id) WHERE email = 'donor2@test.com');
SET @recipient1 = (SELECT recipient_id FROM recipients JOIN users USING (user_id) WHERE email = 'recipient1@test.com');
SET @recipient2 = (SELECT recipient_id FROM recipients JOIN users USING (user_id) WHERE email = 'recipient2@test.com');
SET @volunteer1 = (SELECT volunteer_id FROM volunteers JOIN users USING (user_id) WHERE email = 'volunteer1@test.com');
SET @volunteer2 = (SELECT volunteer_id FROM volunteers JOIN users USING (user_id) WHERE email = 'volunteer2@test.com');
SET @volunteer1_user = (SELECT user_id FROM users WHERE email = 'volunteer1@test.com');
SET @coordinator1_user = (SELECT user_id FROM users WHERE email = 'coordinator1@test.com');

INSERT IGNORE INTO volunteer_availability VALUES
    (1, @volunteer1, '2026-11-06', '12:00:00', '16:00:00'),
    (2, @volunteer1, '2026-11-10', '12:00:00', '16:00:00'),
    (3, @volunteer2, '2026-11-09', '09:00:00', '13:00:00');

INSERT IGNORE INTO donations VALUES
    (1, @donor1, @volunteer1, 'Black beans', 'Canned Goods', 12, 'cans', '2026-11-02', '2027-05-01'),
    (2, @donor1, @volunteer2, 'Rolled oats', 'Dry Goods', 8, 'bags', '2026-11-03', '2027-01-01'),
    (3, @donor2, @volunteer2, 'Spinach', 'Produce', 6, 'bags', '2026-11-03', '2026-11-05'),
    (4, @donor2, @volunteer1, 'Apples', 'Produce', 10, 'bags', '2026-11-08', '2026-11-11');

INSERT IGNORE INTO inventory VALUES
    (1, 1, 'Black beans', 'Canned Goods', 10, 'cans', '2027-05-01', 'Available', NULL, 0),
    (2, 2, 'Rolled oats', 'Dry Goods', 7, 'bags', '2027-01-01', 'Available', NULL, 0),
    (3, 3, 'Spinach', 'Produce', 0, 'bags', '2026-11-05', 'Expired', '2026-11-06', 6),
    (4, 4, 'Apples', 'Produce', 10, 'bags', '2026-11-11', 'Available', NULL, 0);

INSERT IGNORE INTO hamper_requests VALUES
    (1, @recipient1, '2026-11-04', 'Completed'),
    (2, @recipient2, '2026-11-05', 'Scheduled'),
    (3, @recipient2, '2026-11-07', 'Scheduled');

INSERT IGNORE INTO pickups VALUES
    (1, 1, '2026-11-06 14:00:00', 'Fulfilled'),
    (2, 2, '2026-11-07 11:00:00', 'Missed'),
    (3, 3, '2026-11-09 10:00:00', 'Delayed');

INSERT IGNORE INTO distributions VALUES
    (1, @recipient1, @volunteer1, 1, '2026-11-06 14:05:00');

INSERT IGNORE INTO distribution_items VALUES
    (1, 1, 1, 2),
    (2, 1, 2, 1);

INSERT IGNORE INTO volunteer_assignments VALUES
    (1, @volunteer1, '2026-11-04', '2026-11-06 13:00:00', 'Prepare and distribute food hampers', 'Completed'),
    (2, @volunteer1, '2026-11-08', '2026-11-10 13:00:00', 'Pack apples for priority distribution', 'Assigned'),
    (3, @volunteer2, '2026-11-07', '2026-11-09 09:00:00', 'Check scheduled pickups', 'Assigned');

-- Expiry Risk references inventory_id. Pickup alerts reference pickup_id.
INSERT IGNORE INTO alerts VALUES
    (1, @coordinator1_user, 'Missed Pickup', 2, 'The November 7 pickup was missed.', '2026-11-07 12:00:00', 'Unread'),
    (2, @coordinator1_user, 'Delayed Pickup', 3, 'The November 9 pickup is delayed.', '2026-11-09 10:30:00', 'Unread'),
    (3, @coordinator1_user, 'Expiry Risk', 4, 'Apples expire November 11. Prioritize distribution.', '2026-11-09 09:00:00', 'Unread'),
    (4, @volunteer1_user, 'Expiry Risk', 4, 'Pack the apples for your November 10 shift.', '2026-11-09 09:00:00', 'Unread');

COMMIT;
