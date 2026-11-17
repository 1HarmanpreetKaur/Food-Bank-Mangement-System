# Food Bank Management System

INFO 2413 · Team 6 · Option 1

This Java web app runs locally in a browser and stores data in MySQL. The current implementation covers login and account management, donation intake, inventory, donor donation history, inventory categories, and recipient hamper requests and pickup scheduling.

## 1. Get the project

If you already have the project open in VS Code, use your existing folder. For a new setup, clone the [team repository](https://github.com/1HarmanpreetKaur/Food-Bank-Mangement-System) and open the project folder in VS Code.

```powershell
git clone https://github.com/1HarmanpreetKaur/Food-Bank-Mangement-System.git Food-Bank
cd Food-Bank
```

If you’re contributing from a fork, clone your fork instead and add the team repository as `upstream`.

```powershell
git remote add upstream https://github.com/1HarmanpreetKaur/Food-Bank-Mangement-System.git
```

To review changes that are still in a pull request, switch to the feature branch listed in that pull request.

## 2. Set up MySQL

1. Install Git, JDK 17 or later, MySQL 8.x, and MySQL Workbench.
2. Connect to your local MySQL server at `127.0.0.1:3306`.
3. For a fresh setup, open `database.sql` in Workbench and run the whole script. It creates and seeds the `food_bank_login_demo` database. If you already completed Step 05 on this database, keep using it.
4. Confirm the MySQL Connector/J JAR is in the `lib` folder. The repository includes it.
5. The first visit to a Step 05 page creates and seeds `inventory_categories` from existing records. The MySQL account needs `CREATE` permission for this setup and read and write access to the project tables.

## 3. Run the app

Open PowerShell in the project folder. Use your MySQL username in place of `root` if it differs.

```powershell
$db = Get-Credential -UserName root -Message "Enter your local MySQL password"
$env:FOOD_BANK_DB_USER = $db.UserName
$env:FOOD_BANK_DB_PASSWORD = $db.GetNetworkCredential().Password

$buildDir = Join-Path $env:TEMP ("food-bank-java-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $buildDir | Out-Null

javac -cp "lib/*" -d "$buildDir" Login.java InventoryPage.java HamperPage.java
if ($LASTEXITCODE -eq 0) {
    java -cp "$buildDir;lib/*" Login
}
```

Open [the login page](http://127.0.0.1:8080/). Press **Ctrl+C** in PowerShell to stop the app. Compiled files are stored in a temporary folder outside the repository.

### Demo accounts

Select the matching role on the login page.

| Role | Email | Demo password |
|---|---|---|
| Donor | `donor1@test.com` | `donorProject#123` |
| Recipient | `recipient1@test.com` | `recipientProject#123` |
| Volunteer | `volunteer1@test.com` | `volunteerProject#123` |
| Coordinator | `coordinator1@test.com` | `coordinatorProject#123` |

### Check the current features

- **Volunteer** — Record a donation. Search inventory history, edit or delete unused intake, or record waste.
- **Donor** — Open **Search my donations** and filter your donation history by date or category.
- **Coordinator** — Review recipient registrations, manage accounts, and add, rename, archive, or reactivate inventory categories.
- **Recipient** — If approved, request a hamper, choose a future pickup date and time, and view it on your dashboard. Pending and rejected recipients cannot submit requests.

## 4. Commit and push Steps 05 and 06

Run these commands from your existing `step-05-donations-inventory` branch.

```powershell
git status --short --branch
git add -- Login.java InventoryPage.java HamperPage.java README.md
git diff --cached --check
git diff --cached --stat
```

Review `git diff --cached` before committing. Keep compiled `.class` files, local credentials, and database exports out of the commit.

```powershell
git commit -m "Implement inventory and hamper pickup scheduling"
git push -u origin HEAD
```

The push requires write access to `origin`. Run `git remote -v` to confirm that `origin` points to the team repository or your fork.

## 5. Open a pull request

1. Open the team repository on GitHub and select **Pull requests → New pull request**.
2. Set the base branch to the team repository’s `main` and the compare branch to your feature branch. If you pushed to a fork, use **compare across forks**.
3. Describe what changed and which checks passed. Create the pull request and share its link with the team.
4. If changes are requested, commit and push them to the same feature branch. The pull request updates automatically.
