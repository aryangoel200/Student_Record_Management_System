"""
Django settings for the Student Record Management System.

Anything environment-specific (secrets, hosts, database location, face
recognition tuning) is read from the environment, optionally via Backend/.env.
See .env.example for the full list.
"""

import os
from datetime import timedelta
from pathlib import Path

from django.core.exceptions import ImproperlyConfigured

BASE_DIR = Path(__file__).resolve().parent.parent

try:
    from dotenv import load_dotenv
except ImportError:  # dotenv is a convenience, not a requirement
    pass
else:
    load_dotenv(BASE_DIR / ".env")


def env_bool(name, default=False):
    raw = os.environ.get(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def env_list(name, default):
    raw = os.environ.get(name)
    if not raw:
        return list(default)
    return [item.strip() for item in raw.split(",") if item.strip()]


def env_float(name, default):
    raw = os.environ.get(name)
    if raw is None or not raw.strip():
        return default
    try:
        return float(raw)
    except ValueError as exc:
        raise ImproperlyConfigured(f"{name} must be a number, got {raw!r}") from exc


DEBUG = env_bool("DJANGO_DEBUG", default=False)

SECRET_KEY = os.environ.get("DJANGO_SECRET_KEY", "").strip()
if not SECRET_KEY:
    if not DEBUG:
        raise ImproperlyConfigured(
            "DJANGO_SECRET_KEY must be set when DJANGO_DEBUG is off. "
            "See Backend/.env.example."
        )
    # Development-only fallback so a fresh clone runs without setup.
    SECRET_KEY = "django-insecure-development-key-not-for-production-use"

ALLOWED_HOSTS = env_list("DJANGO_ALLOWED_HOSTS", ["localhost", "127.0.0.1"])

INSTALLED_APPS = [
    "django.contrib.admin",
    "django.contrib.auth",
    "django.contrib.contenttypes",
    "django.contrib.sessions",
    "django.contrib.messages",
    "django.contrib.staticfiles",
    "rest_framework",
    "corsheaders",
    "rest_framework_simplejwt.token_blacklist",
    "Auth",
    "Face_Recognation",
    "Home",
]

MIDDLEWARE = [
    "django.middleware.security.SecurityMiddleware",
    "django.contrib.sessions.middleware.SessionMiddleware",
    "corsheaders.middleware.CorsMiddleware",
    "django.middleware.common.CommonMiddleware",
    "django.middleware.csrf.CsrfViewMiddleware",
    "django.contrib.auth.middleware.AuthenticationMiddleware",
    "django.contrib.messages.middleware.MessageMiddleware",
    "django.middleware.clickjacking.XFrameOptionsMiddleware",
]

REST_FRAMEWORK = {
    "DEFAULT_AUTHENTICATION_CLASSES": (
        "rest_framework_simplejwt.authentication.JWTAuthentication",
    ),
    # Deny by default. Endpoints that are genuinely public (login, register,
    # username availability) opt out explicitly with @permission_classes.
    "DEFAULT_PERMISSION_CLASSES": ("rest_framework.permissions.IsAuthenticated",),
    "EXCEPTION_HANDLER": "common.exceptions.api_exception_handler",
    "DEFAULT_THROTTLE_CLASSES": (
        "rest_framework.throttling.ScopedRateThrottle",
    ),
    "DEFAULT_THROTTLE_RATES": {
        "auth": "20/min",  # login + register
        "face": "30/min",  # face enrolment + face-verified attendance
    },
}

SIMPLE_JWT = {
    # Short-lived access tokens; the client refreshes silently.
    "ACCESS_TOKEN_LIFETIME": timedelta(minutes=30),
    "REFRESH_TOKEN_LIFETIME": timedelta(days=7),
    "ROTATE_REFRESH_TOKENS": True,
    "BLACKLIST_AFTER_ROTATION": True,
    "UPDATE_LAST_LOGIN": True,
    "ALGORITHM": "HS256",
    "SIGNING_KEY": SECRET_KEY,
    "AUTH_HEADER_TYPES": ("Bearer",),
    "USER_ID_FIELD": "id",
    "USER_ID_CLAIM": "user_id",
}

ROOT_URLCONF = "AttendanceSysytemServer.urls"

TEMPLATES = [
    {
        "BACKEND": "django.template.backends.django.DjangoTemplates",
        "DIRS": [BASE_DIR / "templates"],
        "APP_DIRS": True,
        "OPTIONS": {
            "context_processors": [
                "django.template.context_processors.debug",
                "django.template.context_processors.request",
                "django.contrib.auth.context_processors.auth",
                "django.contrib.messages.context_processors.messages",
            ],
        },
    },
]

WSGI_APPLICATION = "AttendanceSysytemServer.wsgi.application"

_db_path = Path(os.environ.get("DJANGO_DB_PATH", "db.sqlite3"))
if not _db_path.is_absolute():
    _db_path = BASE_DIR / _db_path

DATABASES = {
    "default": {
        "ENGINE": "django.db.backends.sqlite3",
        "NAME": _db_path,
        "OPTIONS": {
            # Fail fast instead of hanging forever when another process holds
            # the write lock — SQLite has no row-level locking.
            "timeout": 20,
        },
    }
}

AUTH_PASSWORD_VALIDATORS = [
    {"NAME": "django.contrib.auth.password_validation.UserAttributeSimilarityValidator"},
    {
        "NAME": "django.contrib.auth.password_validation.MinimumLengthValidator",
        "OPTIONS": {"min_length": 8},
    },
    {"NAME": "django.contrib.auth.password_validation.CommonPasswordValidator"},
    {"NAME": "django.contrib.auth.password_validation.NumericPasswordValidator"},
]

LANGUAGE_CODE = "en-us"
TIME_ZONE = "Asia/Kolkata"
USE_I18N = True
USE_TZ = True

STATIC_URL = "static/"
STATIC_ROOT = BASE_DIR / "staticfiles"
_static_src = BASE_DIR / "static"
STATICFILES_DIRS = [_static_src] if _static_src.is_dir() else []

MEDIA_URL = "media/"
MEDIA_ROOT = BASE_DIR / "media"

AUTH_USER_MODEL = "Auth.User"
DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"

CORS_ALLOWED_ORIGINS = env_list(
    "CORS_ALLOWED_ORIGINS", ["http://localhost:3000", "http://127.0.0.1:3000"]
)
CORS_ALLOW_METHODS = ["GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"]
CORS_ALLOW_HEADERS = ["Accept", "Content-Type", "Authorization"]

APPEND_SLASH = False

# Base64 camera frames are large; 12 MB leaves plenty of headroom for a
# full-resolution PNG without letting a client exhaust memory.
DATA_UPLOAD_MAX_MEMORY_SIZE = 12 * 1024 * 1024

# --- Application-specific configuration -------------------------------------

FACE_RECOGNITION = {
    "ENABLED": env_bool("FACE_RECOGNITION_ENABLED", default=True),
    "MODEL": os.environ.get("FACE_RECOGNITION_MODEL", "Facenet512"),
    "DETECTOR": os.environ.get("FACE_DETECTOR_BACKEND", "opencv"),
    "THRESHOLD": env_float("FACE_MATCH_THRESHOLD", 0.30),
}

DEFAULT_SESSION_RADIUS_M = env_float("DEFAULT_SESSION_RADIUS_M", 100.0)

if not DEBUG:
    SECURE_CONTENT_TYPE_NOSNIFF = True
    SESSION_COOKIE_SECURE = True
    CSRF_COOKIE_SECURE = True
    X_FRAME_OPTIONS = "DENY"

LOGGING = {
    "version": 1,
    "disable_existing_loggers": False,
    "formatters": {"simple": {"format": "{levelname} {name} {message}", "style": "{"}},
    "handlers": {
        "console": {"class": "logging.StreamHandler", "formatter": "simple"},
    },
    "root": {"handlers": ["console"], "level": "INFO"},
}
