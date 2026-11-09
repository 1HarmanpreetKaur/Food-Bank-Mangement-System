import os
from secrets import token_hex

from flask import Flask, render_template

from .auth import auth
from .database import check_db, create_coordinator


def create_app():
    app = Flask(__name__)
    app.config.update(SECRET_KEY=os.environ.get("FOODBANK_SECRET_KEY") or token_hex(32),
                      SESSION_COOKIE_SAMESITE="Lax")
    app.register_blueprint(auth)
    app.cli.add_command(check_db)
    app.cli.add_command(create_coordinator)

    @app.get("/")
    def home():
        return render_template("home.html")

    return app
