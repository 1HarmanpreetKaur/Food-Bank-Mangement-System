# Food Bank Management System

INFO2413 Team 6. Python 3.12, Flask, and MySQL 8.4.

## Run on Windows

Install Python 3.12. Open PowerShell in the project folder, where `requirements.txt` is located, and run:

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m flask --app foodbank run
```

If the Python launcher is unavailable, use `python -m venv .venv` after checking that `python --version` reports 3.12. On macOS or Linux, use `python3.12 -m venv .venv` and replace the Windows executable path with `.venv/bin/python`.

Open http://127.0.0.1:5000/. The starter page should say “The local starter application is running.” Stop the local server with Ctrl+C.

## Project folders

- `foodbank/` holds the Flask entry point, templates, and static files.
- `database/` holds the Step 02 MySQL draft and setup notes. The starter page runs independently of MySQL. See database/README.md for the connection check and first coordinator command.

Use local Git through Step 04. Then push the history to a private GitHub repository and invite teammates. A teammate should run the project from a clean clone.

## Step 03 sign-in check

The existing Step 02 schema already has the lockout fields. In PowerShell,
set the MySQL account password for the current terminal and run Flask:

```powershell
$env:FOODBANK_DB_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'MySQL password' -AsSecureString)).Password
.\.venv\Scripts\python.exe -m flask --app foodbank run
```

Set `FOODBANK_DB_USER` first if your Workbench connection uses a username other
than root. Open http://127.0.0.1:5000/login and use your app coordinator email
and password. Check that you reach the coordinator dashboard, that
`/dashboard/volunteer` returns 403, and that sign-out ends the session.
Five consecutive incorrect passwords lock the account for 15 minutes.
For local use the session signing key is generated on startup, so restarting
Flask signs users out. Use a persistent `FOODBANK_SECRET_KEY` for shared runs.

## Step 04 account backend

The functions in `foodbank/accounts.py` are ready for the future Flask forms.
No schema reload is required. To try the functions directly, set the same MySQL
environment variables as in Step 03 and run:

```powershell
.\.venv\Scripts\python.exe -m flask --app foodbank shell
```

In the shell, import `from foodbank import accounts`. Use `accounts.list_accounts(coordinator_user_id)`
to list active accounts. The actor ID in future routes must come from the
authenticated session. The shell is for local development only. Use dummy
accounts for checks. Account removal disables login and preserves existing
database records. A coordinator cannot remove their own account. The volunteer
image field stores a reference for the future upload form.
