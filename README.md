# Food Bank Management System

## 1. Get the code

Open the [team repository](https://github.com/1HarmanpreetKaur/Food-Bank-Mangement-System) and click **Fork → Create fork**. On your fork, click **Code → HTTPS** and copy the URL.

In VS Code, open a PowerShell terminal. Replace `YOUR-FORK-URL` with the link you copied:

```powershell
git clone "YOUR-FORK-URL" Food-Bank
cd Food-Bank
git remote add upstream https://github.com/1HarmanpreetKaur/Food-Bank-Mangement-System.git
```

Open the new **Food-Bank** folder in VS Code. Run the remaining commands in that folder.

## 2. Set up and run the app

1. Install Git, Java JDK 17 or newer, MySQL Server, and MySQL Workbench if you need them. Check with `git --version`, `javac -version`, and `Get-Service *mysql*`.
2. In MySQL Workbench, connect to your local server on port **3306**. Open `database.sql` and click the **first lightning bolt**. Do this once on a fresh database.
3. If the MySQL driver is missing, download the [MySQL Connector/J ZIP](https://dev.mysql.com/downloads/connector/j/). Extract it and put the JAR in a `lib` folder next to `Login.java`.
4. Run these commands in PowerShell. Enter your local MySQL password when prompted. Replace `root` if you use another MySQL username.

```powershell
$db = Get-Credential -UserName root -Message "Enter your MySQL password"
$env:FOOD_BANK_DB_USER = $db.UserName
$env:FOOD_BANK_DB_PASSWORD = $db.GetNetworkCredential().Password
java --class-path "lib/*" Login.java
```

Open [the login page](http://127.0.0.1:8080). Try `coordinator1@test.com` with `coordinatorProject#123` and select **Coordinator**. You should see the Coordinator details. Try the same credentials with **Volunteer** selected to check that the app rejects the wrong role. Press **Ctrl+C** in PowerShell to stop the app.

## 3. Make and push a change

Start each task on a new branch. Replace `your-task-name` with a short name for your task.

```powershell
git switch main
git pull --ff-only upstream main
git switch -c your-task-name
```

Edit your assigned files, save, and test the app. If Git asks for your name and email on your first commit, run:

```powershell
git config user.name "Your Name"
git config user.email "Your GitHub commit email"
```

Run the commands below. Replace `Login.java` with the files you changed and write a short commit message.

```powershell
git status --short
git add -- Login.java
git diff --cached
git commit -m "Describe your change"
git push -u origin HEAD
```

Keep your local MySQL password out of the commit.

## 4. Open a pull request

1. Open your fork on GitHub and click **Compare & pull request**. If the button is missing, open **Pull requests → New pull request → compare across forks** in the team repository.
2. Set the **base** to the team repo's `main` and the **compare** branch to your task branch in your fork.
3. Write what you changed and how you tested it. Click **Create pull request** and share the PR link with the team.

If someone requests changes, edit and test on the same branch, then run `git add -- Login.java` (or the files you changed), `git commit -m "Address feedback"`, and `git push`. The same pull request updates automatically.
