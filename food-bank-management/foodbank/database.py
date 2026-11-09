import os

import click
import mysql.connector
from werkzeug.security import generate_password_hash


def connect(*, interactive=True):
    password = os.environ.get("FOODBANK_DB_PASSWORD")
    if password is None and not interactive:
        raise mysql.connector.InterfaceError("Set FOODBANK_DB_PASSWORD before starting Flask.")
    if password is None:
        password = click.prompt("MySQL password", hide_input=True, default="", show_default=False)
    return mysql.connector.connect(
        host=os.environ.get("FOODBANK_DB_HOST", "127.0.0.1"),
        port=int(os.environ.get("FOODBANK_DB_PORT", "3306")),
        user=os.environ.get("FOODBANK_DB_USER", "root"),
        password=password,
        database="food_bank",
        connection_timeout=5,
    )


@click.command("check-db")
def check_db():
    """Check the MySQL connection and core account tables."""
    try:
        with connect() as connection, connection.cursor() as cursor:
            cursor.execute("SELECT COUNT(*) FROM users")
            cursor.fetchone()
            cursor.execute("SELECT COUNT(*) FROM coordinator")
            cursor.fetchone()
    except (mysql.connector.Error, ValueError) as error:
        raise click.ClickException(str(error)) from error
    click.echo("Connected to food_bank. Account tables are ready.")


@click.command("create-coordinator")
@click.option("--name", prompt=True)
@click.option("--email", prompt=True)
@click.option("--phone", prompt=True)
def create_coordinator(name, email, phone):
    """Create a coordinator and its user record in one transaction."""
    name, email, phone = name.strip(), email.strip().lower(), phone.strip()
    if not (1 <= len(name) <= 100 and 1 <= len(phone) <= 25):
        raise click.ClickException("Enter a name and phone number within 100 and 25 characters.")
    local, separator, domain = email.partition("@")
    if not (local and separator and domain and len(email) <= 255 and email.count("@") == 1
            and not any(character.isspace() for character in email)):
        raise click.ClickException("Enter a valid email address.")
    password = click.prompt("New coordinator password", hide_input=True, confirmation_prompt=True)
    if len(password) < 8:
        raise click.ClickException("Use at least eight characters for the coordinator password.")
    password_hash = generate_password_hash(password)
    try:
        with connect() as connection, connection.cursor() as cursor:
            try:
                cursor.execute(
                    "INSERT INTO users (name, email, phone_number, password_hash, role) "
                    "VALUES (%s, %s, %s, %s, 'coordinator')",
                    (name, email, phone, password_hash),
                )
                cursor.execute("INSERT INTO coordinator (user_id) VALUES (%s)", (cursor.lastrowid,))
                connection.commit()
            except mysql.connector.Error:
                connection.rollback()
                raise
    except mysql.connector.IntegrityError as error:
        if error.errno == 1062:
            raise click.ClickException("That email already belongs to a user.") from error
        raise click.ClickException(str(error)) from error
    except (mysql.connector.Error, ValueError) as error:
        raise click.ClickException(str(error)) from error
    click.echo("Coordinator account created.")
