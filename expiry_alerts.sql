USE food_bank_login_demo;

-- One expiry alert per inventory batch, expiry date and recipient.
-- Keep the record even if the original inventory batch is later deleted.
CREATE TABLE IF NOT EXISTS expiry_alert_keys (
    inventory_id INT NOT NULL,
    expiry_date DATE NOT NULL,
    user_id INT NOT NULL,
    PRIMARY KEY (inventory_id, expiry_date, user_id)
) ENGINE=InnoDB;
