# Step 02 MySQL schema draft

The schema uses MySQL 8.4. It can be reviewed and loaded independently of the Java or Python application.

## Load and show the tables

1. Connect to a MySQL 8.4 server in MySQL Workbench using an account with schema creation privileges.
2. Open `schema.sql` with **File → Open SQL Script**, then execute the whole script against a fresh database.
3. Run `SHOW TABLES FROM food_bank;` to show the 15 tables. To show how multiple items belong to one distribution, run `SHOW CREATE TABLE food_bank.distribution_item;`.

## Draft decisions for review

- The 13 SDD tables are represented. Two proposed tables let volunteers have editable availability time windows and let an assignment cover several pickups.
- The intake workflow will let volunteers enter donations linked to donors. Donation expiry is stored per donation as requested in the feedback. A null expiry represents an item with no expiry. The application must keep shared donation and inventory fields in sync when it edits intake.
- The proposed account removal behavior disables access and preserves operational history. Confirm this approach against the SRS delete requirement before implementation.
- The application must enforce approved-recipient eligibility, role/profile matching, assigned-volunteer access, pickup/recipient matching, and at least one item per distribution. Distribution and inventory updates must share a transaction.
- Alert rows support tracking email delivery and deduplicating individual alert events. Alert timing and expiry detection are separate implementation steps.
- The current waste columns represent one waste date and quantity per inventory row, as in the SDD. If the review calls for multiple disposal events per item, add a waste event table before implementing reporting.

## Connect Flask and create the first coordinator

From the project folder in PowerShell, after installing the requirements:

```powershell
.\.venv\Scripts\python.exe -m flask --app foodbank check-db
.\.venv\Scripts\python.exe -m flask --app foodbank create-coordinator
```

The commands default to local MySQL at 127.0.0.1:3306 and the root user.
If your Workbench connection uses a different username, set
`$env:FOODBANK_DB_USER = 'your_mysql_user'` first. The MySQL password is
prompted separately from the new coordinator password. Only a password hash
is saved for the coordinator. Use an app-specific MySQL user for shared setups.
