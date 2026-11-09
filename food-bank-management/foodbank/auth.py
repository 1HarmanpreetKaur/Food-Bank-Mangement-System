from secrets import compare_digest, token_urlsafe

import mysql.connector
from flask import Blueprint, abort, g, redirect, render_template, request, session, url_for
from werkzeug.security import check_password_hash

from .database import connect

auth = Blueprint("auth", __name__)
ROLES = {"donor", "recipient", "volunteer", "coordinator"}


@auth.before_app_request
def load_user():
    g.user = None
    if "user_id" in session:
        with connect(interactive=False) as connection, connection.cursor(dictionary=True) as cursor:
            cursor.execute(
                "SELECT user_id, name, role FROM users WHERE user_id = %s AND status = 'active'",
                (session["user_id"],),
            )
            g.user = cursor.fetchone()
        if g.user is None:
            session.clear()


@auth.app_context_processor
def form_helpers():
    def csrf_token():
        if "csrf_token" not in session:
            session["csrf_token"] = token_urlsafe(32)
        return session["csrf_token"]

    return {"csrf_token": csrf_token}


def check_csrf():
    token = session.get("csrf_token")
    submitted = request.form.get("csrf_token", "")
    if not token or not compare_digest(token.encode(), submitted.encode()):
        abort(400)


@auth.route("/login", methods=["GET", "POST"])
def login():
    if g.user:
        return redirect(url_for("auth.dashboard", role=g.user["role"]))
    if request.method == "GET":
        return render_template("login.html")

    check_csrf()
    email = request.form.get("email", "").strip().lower()
    password = request.form.get("password", "")
    if not email or len(email) > 255 or not password or len(password) > 1024:
        return render_template("login.html", error="Enter a valid email and password."), 400
    try:
        user = authenticate(email, password)
    except mysql.connector.Error:
        return render_template("login.html", error="The database is unavailable. Check the local setup."), 503
    if user is None:
        return render_template("login.html", error="Sign-in failed. Check your details or try again later."), 401
    session.clear()
    session["user_id"] = user["user_id"]
    return redirect(url_for("auth.dashboard", role=user["role"]))


def authenticate(email, password):
    with connect(interactive=False) as connection, connection.cursor(dictionary=True) as cursor:
        cursor.execute(
            "SELECT user_id, role, password_hash, failed_login_attempts, locked_until, "
            "COALESCE(locked_until > UTC_TIMESTAMP(), 0) AS is_locked "
            "FROM users WHERE email = %s AND status = 'active' FOR UPDATE",
            (email,),
        )
        user = cursor.fetchone()
        if not user or user["is_locked"]:
            return None
        if check_password_hash(user["password_hash"], password):
            cursor.execute(
                "UPDATE users SET failed_login_attempts = 0, locked_until = NULL WHERE user_id = %s",
                (user["user_id"],),
            )
            connection.commit()
            return user
        attempts = 1 if user["locked_until"] else user["failed_login_attempts"] + 1
        cursor.execute(
            "UPDATE users SET failed_login_attempts = %s, "
            "locked_until = IF(%s >= 5, UTC_TIMESTAMP() + INTERVAL 15 MINUTE, NULL) WHERE user_id = %s",
            (attempts, attempts, user["user_id"]),
        )
        connection.commit()
        return None


@auth.post("/logout")
def logout():
    check_csrf()
    session.clear()
    return redirect(url_for("home"))


@auth.get("/dashboard/<role>")
def dashboard(role):
    if role not in ROLES:
        abort(404)
    if not g.user:
        return redirect(url_for("auth.login"))
    if g.user["role"] != role:
        abort(403)
    return render_template("dashboard.html", role=role)
