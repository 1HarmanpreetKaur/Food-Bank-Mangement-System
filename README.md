# Food Bank Management System

INFO 2413 · Team 6 · Option 1

A Java web application backed by MySQL. Donors can view their donation history, recipients can request and schedule hamper pickups, volunteers can track inventory and manage assigned distributions, and coordinators can manage operations and view reports.

## Live demo on Render

**[Open the Food Bank Management System](https://food-bank-mangement-system.onrender.com/)**

**First visit:** The first request may take longer while Render starts the app after inactivity. Please wait for the login page to load.

Use the Render site to test actual email delivery and Claude AI report summaries. The deployed app has the integration credentials configured on the server. For this submission, a local clone runs the core workflows, shows in-app alerts, and generates the MySQL report tables. Real inbox delivery and Claude AI summaries are unavailable in the standard local setup.

| Feature | Render demo | Standard local setup |
|---|---|---|
| Accounts, donations, inventory, pickups, and distributions | Available | Available after MySQL setup |
| In-app alerts and report tables | Available | Available |
| Alert delivery to a real inbox | Available for accounts with real email addresses | Unavailable |
| Claude AI summary when generating a report | Available | Unavailable |

Use the current Render login details provided by Team 6. The demo accounts in the local setup below are database seed accounts. For any Render account whose email was changed for alert testing, sign in with its updated email and existing password.

### Test email alerts on Render

1. Sign in as a coordinator. From the dashboard, open **Manage user accounts** and select **Edit** beside the coordinator test account.
2. Replace its demo email with a real email address you can access and save. The email is also the login ID. Use the new email with the same password at the next sign-in. Gmail skips the seeded test addresses. For an expiry alert test, also update the email of the volunteer you will assign.
3. Trigger a fresh alert using one of the workflows below. Check **View alerts** in the app, then check the receiving inbox and Spam folder. Coordinate changes to shared demo accounts with Team 6.
4. After testing, please go back to **Manage user accounts → Edit** and restore the coordinator account’s original demo email (for example, `coordinator1@test.com`). Save the change so the next tester can sign in with the demo email and existing password.

| Alert | How to trigger it | Who receives the email |
|---|---|---|
| Delayed or missed pickup | Schedule a future pickup as an approved recipient. As coordinator, assign an available volunteer and link the pickup to the shift. As that volunteer, open **Manage pickups and distributions** and mark the pickup **Delayed** or **Missed**. | Coordinators with real email addresses on their accounts |
| Inventory expiry risk | As a volunteer, record a fresh donation with a near-term expiry date. As coordinator, assign a volunteer to an upcoming distribution pickup or a shift flagged for expiry alerts on or before the expiry date. Use stock expected to remain at expiry and allow up to five minutes for the analyzer to run. | Coordinators and volunteers assigned to relevant upcoming distributions, using the real email addresses on their accounts |

### Test the Claude AI summary on Render

Sign in as a coordinator and open **View reports**. Select **Donation & Distribution Summary** or **Inventory Waste Summary**, choose a year and month or **Full year**, then click **Generate report**. Use a period with data. The page displays the report table and its Claude summary. The setup below generates the tables locally from MySQL.

## 1. Get the project

These steps are for Windows. Install **Git**, **JDK 17 or later**, **MySQL Server 8.x**, and **MySQL Workbench**. VS Code is optional. The MySQL driver is already in `lib/`. Java serves the complete web app. No Node.js or npm is needed.

In PowerShell, run `git --version`, `java -version`, and `javac -version`. If a command is missing after installation, reopen PowerShell and check that the relevant program is on your Windows PATH. The commands below check out the reviewed project branch.

Open the folder containing `Login.java` in VS Code. If you need a fresh copy, clone the [team repository](https://github.com/1HarmanpreetKaur/Food-Bank-Mangement-System).

```powershell
git clone --branch team-setup-and-claude-reports https://github.com/1HarmanpreetKaur/Food-Bank-Mangement-System.git Food-Bank
cd Food-Bank
```

Run the rest of the commands from the folder containing `Login.java`, `database.sql`, `frontend/`, and `lib/`.

## 2. Set up MySQL once

Start **MySQL Server** if it is stopped. In Workbench, connect to `127.0.0.1` on port `3306` using the MySQL username and password you chose during installation. Workbench is the client and MySQL Server is the database service.

For a fresh setup, open each script with **File → Open SQL Script**, then execute the full script using the lightning bolt button. Run one script at a time and check the Workbench output before opening the next. The scripts select the app database themselves.

| Order | Script | Purpose |
|---|---|---|
| 1 | `database.sql` | Creates `food_bank_login_demo`, the base tables, and demo accounts. |
| 2 | `assignments.sql` | Adds shift windows and pickup links. |
| 3 | `expiry_alerts.sql` | Adds tracking to prevent duplicate expiry alert records. |
| 4 | `pickup_alert_email.sql` | Adds email delivery tracking. |
| 5 | `workflow_history.sql` | Adds per-date waste entries, pickup outcome history, shift alert settings, and dismissed alerts. |

`reports.sql` is an empty placeholder. Report queries are in `ReportPage.java`.

For an existing setup, stop the app and apply any missing setup scripts in order. The new workflow script backfills the waste and pickup history still available in the database. If an older pickup was marked Fulfilled without a distribution, open its assigned pickup and record the actual items if they were handed out. Otherwise correct the pickup status based on what happened. The first visit to an inventory page creates and seeds `inventory_categories`. The application’s MySQL account needs `CREATE` permission for that setup and read and write access to the project tables.

## 3. Start the app

Use the demo accounts below to run the core app on your own computer.

In PowerShell, run the following from the project folder. Replace `root` if you use a different local MySQL username. Set the environment variables in the same terminal that launches Java.

```powershell
$db = Get-Credential -UserName root -Message "Enter your local MySQL password"
$env:FOOD_BANK_DB_USER = $db.UserName
$env:FOOD_BANK_DB_PASSWORD = $db.GetNetworkCredential().Password
$env:FOOD_BANK_EMAIL_MODE = "gmail"

$buildDir = Join-Path $env:TEMP ("food-bank-java-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $buildDir | Out-Null

javac -cp "lib/*" -d "$buildDir" *.java
if ($LASTEXITCODE -eq 0) {
    java -cp "$buildDir;lib/*" Login
}
```

Open the [login page](http://127.0.0.1:8080/). Press **Ctrl+C** to stop Java. Compiled files are stored outside the repository. Leave this PowerShell window open while using the app. The app serves both the backend and frontend at port 8080. Rerun the compile and start commands after changing Java files. The MySQL setup scripts are a one-time step for a fresh database.

### Demo accounts

Choose the matching role on the login page. These credentials are for the local seeded database.

| Role | Emails | Demo password |
|---|---|---|
| Donor | `donor1@test.com`, `donor2@test.com` | `donorProject#123` |
| Recipient | `recipient1@test.com`, `recipient2@test.com` | `recipientProject#123` |
| Volunteer | `volunteer1@test.com`, `volunteer2@test.com` | `volunteerProject#123` |
| Coordinator | `coordinator1@test.com`, `coordinator2@test.com` | `coordinatorProject#123` |

## 4. Check the workflows

- **Donor** · Open **Search my donations** and filter your donation history by date or category.
- **Recipient** · Once approved, request a hamper and schedule a future pickup. View your pickup and distribution history or cancel one of your future pending pickups.
- **Volunteer** · Record donations and waste, review inventory, view assigned shifts, and update pickups or distributions for assigned recipients.
- **Coordinator** · Review registrations, manage accounts and categories, assign volunteers, and open **View reports**. Both **Donation & Distribution Summary** and **Inventory Waste Summary** support monthly and full-year periods. Quantities are grouped by category and unit to keep unlike units separate. The pickup report counts a pickup once per delayed or missed outcome in the period of its scheduled date, even if later fulfilled.
- **Alerts** · Volunteers and coordinators can view the alerts sent to their roles. A pending pickup becomes Delayed at 30 minutes, and an unresolved pickup becomes Missed at two hours.

## 5. JSON API

The dashboard loads recent activity from the same Java server. Sign in through the browser first and use the same browser session to open these URLs. The server checks that the account is active and returns records for that user and role.

| Method and path | Response |
|---|---|
| `GET /api/v1/session` | The signed-in user's display name and role. |
| `GET /api/v1/activity` | Up to five recent records. Donors see their donations, recipients see their pickups, volunteers see their assignments, and coordinators see their visible alerts. |

The API returns JSON, including `401` when signed out, `404` for an unknown endpoint, `405` for an unsupported method, and `503` when the data is unavailable. Account, inventory, pickup, and report forms continue to use their existing HTML routes. The front end uses the files in `frontend/`, so start Java from the project folder as shown above.

## 6. Contribute and open a pull request

1. Run `git status --short --branch` to check your current branch, then stage the files you changed. Keep local database credentials, Gmail App Passwords, Claude API keys, exports, and compiled `.class` files out of the commit.
2. Review `git diff --cached` and run `git diff --cached --check`. Commit and push your feature branch to the team repository or your fork.
3. Open a pull request into the agreed base branch. Describe what changed, which checks passed, and which checks are still pending.


## Troubleshooting

| Symptom | Check |
|---|---|
| `javac` is not recognized | Install the JDK, reopen PowerShell, and run `javac -version`. |
| MySQL connection refused | Start MySQL Server and verify the Workbench connection uses `127.0.0.1:3306`. |
| MySQL access denied | Use the same MySQL credentials that work in Workbench. The demo login passwords are for the website. |
| A database table is missing | Check the Workbench output and apply the missing script from Step 2 in order. |
| Port 8080 already in use | Stop the earlier copy of the Java app before restarting. |
| Page loads without styles | Start Java from the folder containing `frontend/` and verify that `frontend/styles.css` exists. |
| Gmail alert remains queued | Run the test on Render. Verify the test account has a real email address, trigger a fresh alert, and check Inbox and Spam. |
| The report appears without a Claude summary | Generate the report on Render and select a period that has data. |
