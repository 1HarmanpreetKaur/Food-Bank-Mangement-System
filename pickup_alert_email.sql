USE food_bank_login_demo;

-- Only record delivery after Mailpit acknowledges the SMTP message.
CREATE TABLE IF NOT EXISTS alert_email_deliveries (
    alert_id INT NOT NULL PRIMARY KEY,
    recipient_email VARCHAR(120) NOT NULL,
    delivered_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (alert_id) REFERENCES alerts(alert_id) ON DELETE CASCADE
) ENGINE=InnoDB;
