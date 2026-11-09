"""Account operations shared by future Flask forms."""

from contextlib import contextmanager
from datetime import datetime

import mysql.connector
from werkzeug.security import generate_password_hash

from .database import connect


@contextmanager
def account_transaction():
    try:
        with connect(interactive=False) as connection, connection.cursor(dictionary=True) as cursor:
            try:
                yield cursor
                connection.commit()
            except Exception:
                connection.rollback()
                raise
    except mysql.connector.IntegrityError as error:
        if error.errno == 1062:
            raise ValueError("That email or availability window is already recorded.") from error
        raise


def required_text(value, limit, label):
    value = value.strip()
    if not 1 <= len(value) <= limit:
        raise ValueError(f"Enter {label} with at most {limit} characters.")
    return value


def contact(name, email, phone):
    email = required_text(email, 255, "an email address").lower()
    local, separator, domain = email.partition("@")
    if not (local and separator and domain and email.count("@") == 1
            and not any(char.isspace() for char in email)):
        raise ValueError("Enter a valid email address.")
    return required_text(name, 100, "a name"), email, required_text(phone, 25, "a phone number")


def recipient_details(household_size, dietary_restrictions, emergency_contact):
    if type(household_size) is not int or not 1 <= household_size <= 2147483647:
        raise ValueError("Household size must be a positive whole number.")
    restrictions = dietary_restrictions.strip()
    if len(restrictions.encode("utf-8")) > 65535:
        raise ValueError("Dietary restrictions are too long.")
    return household_size, restrictions, required_text(emergency_contact, 100, "an emergency contact")


def add_user(cursor, role, name, email, phone, password):
    if not 8 <= len(password) <= 1024:
        raise ValueError("Use a password of 8 to 1024 characters.")
    cursor.execute(
        "INSERT INTO users (name, email, phone_number, password_hash, role) "
        "VALUES (%s, %s, %s, %s, %s)",
        (*contact(name, email, phone), generate_password_hash(password), role),
    )
    return cursor.lastrowid


def active_role(cursor, user_id):
    cursor.execute(
        "SELECT role FROM users WHERE user_id = %s AND status = 'active' FOR UPDATE",
        (user_id,),
    )
    row = cursor.fetchone()
    return row["role"] if row else None


def require_coordinator(cursor, actor_id):
    if active_role(cursor, actor_id) != "coordinator":
        raise PermissionError("Only a coordinator can do this.")


def register_donor(name, email, phone, password):
    with account_transaction() as cursor:
        user_id = add_user(cursor, "donor", name, email, phone, password)
        cursor.execute("INSERT INTO donor (user_id) VALUES (%s)", (user_id,))
        return user_id


def register_recipient(name, email, phone, password, household_size,
                       dietary_restrictions, emergency_contact):
    details = recipient_details(household_size, dietary_restrictions, emergency_contact)
    with account_transaction() as cursor:
        user_id = add_user(cursor, "recipient", name, email, phone, password)
        cursor.execute(
            "INSERT INTO recipient (user_id, household_size, dietary_restrictions, emergency_contact) "
            "VALUES (%s, %s, %s, %s)",
            (user_id, *details),
        )
        return user_id


def save_volunteer_details(cursor, user_id, profile_image, availability):
    image = profile_image.strip()
    if len(image) > 255:
        raise ValueError("The profile image reference is too long.")
    windows = set()
    for start, end in availability:
        if (not isinstance(start, datetime) or not isinstance(end, datetime)
                or start.tzinfo is not None or end.tzinfo is not None or start >= end):
            raise ValueError("Each availability window needs local start and end times in order.")
        windows.add((start, end))
    cursor.execute("SELECT volunteer_id FROM volunteer WHERE user_id = %s", (user_id,))
    volunteer_id = cursor.fetchone()["volunteer_id"]
    cursor.execute("UPDATE volunteer SET profile_image = %s WHERE volunteer_id = %s",
                   (image or None, volunteer_id))
    cursor.execute("DELETE FROM volunteer_availability WHERE volunteer_id = %s", (volunteer_id,))
    if windows:
        cursor.executemany(
            "INSERT INTO volunteer_availability (volunteer_id, available_from, available_until) "
            "VALUES (%s, %s, %s)",
            [(volunteer_id, start, end) for start, end in sorted(windows)],
        )
    return volunteer_id


def create_volunteer(actor_id, name, email, phone, password, profile_image="", availability=()):
    with account_transaction() as cursor:
        require_coordinator(cursor, actor_id)
        user_id = add_user(cursor, "volunteer", name, email, phone, password)
        cursor.execute("INSERT INTO volunteer (user_id) VALUES (%s)", (user_id,))
        return save_volunteer_details(cursor, user_id, profile_image, availability)


def update_profile(actor_id, user_id, name, email, phone, *, recipient=None, volunteer=None):
    """Recipient details are a triple and volunteer details are an image and time windows."""
    with account_transaction() as cursor:
        actor_role = active_role(cursor, actor_id)
        if actor_role != "coordinator" and (actor_id != user_id or actor_role not in {"donor", "recipient"}):
            raise PermissionError("You cannot edit this account.")
        role = actor_role if actor_id == user_id else active_role(cursor, user_id)
        if role is None:
            raise LookupError("Active account not found.")
        if recipient is not None:
            if role != "recipient":
                raise ValueError("Recipient details require a recipient account.")
            cursor.execute(
                "UPDATE recipient SET household_size = %s, dietary_restrictions = %s, "
                "emergency_contact = %s WHERE user_id = %s",
                (*recipient_details(*recipient), user_id),
            )
        if volunteer is not None:
            if role != "volunteer":
                raise ValueError("Volunteer details require a volunteer account.")
            save_volunteer_details(cursor, user_id, *volunteer)
        cursor.execute("UPDATE users SET name = %s, email = %s, phone_number = %s WHERE user_id = %s",
                       (*contact(name, email, phone), user_id))


def review_recipient(actor_id, user_id, decision):
    if decision not in {"approved", "rejected"}:
        raise ValueError("Choose approved or rejected.")
    with account_transaction() as cursor:
        require_coordinator(cursor, actor_id)
        if active_role(cursor, user_id) != "recipient":
            raise LookupError("Active recipient not found.")
        cursor.execute(
            "UPDATE recipient SET approval_status = %s WHERE user_id = %s AND approval_status = 'pending'",
            (decision, user_id),
        )
        if cursor.rowcount != 1:
            raise ValueError("Only a pending application can be reviewed.")


def list_accounts(actor_id):
    with account_transaction() as cursor:
        require_coordinator(cursor, actor_id)
        cursor.execute(
            "SELECT u.user_id, u.name, u.email, u.phone_number, u.role, r.approval_status "
            "FROM users u LEFT JOIN recipient r ON r.user_id = u.user_id "
            "WHERE u.status = 'active' ORDER BY u.name, u.user_id"
        )
        return cursor.fetchall()


def delete_account(actor_id, user_id):
    with account_transaction() as cursor:
        require_coordinator(cursor, actor_id)
        if actor_id == user_id:
            raise ValueError("Ask another coordinator to remove your account.")
        cursor.execute("UPDATE users SET status = 'deleted' WHERE user_id = %s AND status = 'active'",
                       (user_id,))
        if cursor.rowcount != 1:
            raise LookupError("Active account not found.")
