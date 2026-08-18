"""Device integrity attestation for the Android client.

A geofence only proves *what the client said its coordinates were*. On a rooted
phone, or under a mock location provider, that claim is free to fabricate — so
on its own it does not stop a determined proxy.

Two independent signals close that gap, and both are checked in
``Home.mark_attendance``:

1. **Mock-location flag** — Android's own ``Location.isMock`` /
   ``isFromMockProvider``, forwarded by the app. Cheap, local, and catches the
   fake-GPS apps that make up most casual abuse.
2. **Play Integrity** — Google attests that the request came from a genuine,
   unmodified build of your app on a genuine device. This is what makes signal 1
   trustworthy: without it, a patched APK simply reports ``is_mock=false``.

Play Integrity is opt-in (``DEVICE_INTEGRITY_REQUIRED``) because it needs a
Play Console app and a service account. Once enabled it fails closed: if the
verifier cannot run, attendance is refused rather than allowed through.

Tokens are bound to a server-issued single-use nonce, so a token captured once
cannot be replayed for a later session.
"""

import functools
import logging
import secrets

from dataclasses import dataclass
from django.conf import settings
from django.core.cache import cache

logger = logging.getLogger(__name__)

NONCE_CACHE_PREFIX = "device-integrity-nonce"

# Verdicts we accept from Play Integrity.
ACCEPTED_APP_VERDICTS = {"PLAY_RECOGNIZED"}
ACCEPTED_DEVICE_VERDICTS = {"MEETS_DEVICE_INTEGRITY", "MEETS_STRONG_INTEGRITY"}


class DeviceIntegrityUnavailable(Exception):
    """The verifier could not run — misconfigured, or its deps are missing."""


@dataclass(frozen=True)
class IntegrityVerdict:
    ok: bool
    reason: str = ""

    @classmethod
    def passed(cls):
        return cls(True)

    @classmethod
    def failed(cls, reason):
        return cls(False, reason)


# --- Nonce handling --------------------------------------------------------


def nonce_ttl_seconds():
    return int(settings.DEVICE_INTEGRITY["NONCE_TTL_SECONDS"])


def _nonce_key(user, nonce):
    return f"{NONCE_CACHE_PREFIX}:{user.pk}:{nonce}"


def issue_nonce(user):
    """Mint a short-lived, single-use nonce for one attendance attempt."""
    nonce = secrets.token_urlsafe(32)
    cache.set(_nonce_key(user, nonce), True, timeout=nonce_ttl_seconds())
    return nonce


def consume_nonce(user, nonce):
    """Redeem a nonce. Returns False if unknown, expired, or already used."""
    if not nonce:
        return False
    key = _nonce_key(user, nonce)
    if cache.get(key) is None:
        return False
    cache.delete(key)
    return True


# --- Verifiers -------------------------------------------------------------


class BaseIntegrityVerifier:
    name = "base"

    def verify(self, token, nonce):
        raise NotImplementedError


class DisabledIntegrityVerifier(BaseIntegrityVerifier):
    """Raises rather than approving, so a misconfiguration cannot open a hole."""

    name = "disabled"

    def __init__(self, reason="Device integrity checking is not configured."):
        self.reason = reason

    def verify(self, token, nonce):
        raise DeviceIntegrityUnavailable(self.reason)


class PlayIntegrityVerifier(BaseIntegrityVerifier):
    """Decodes a Play Integrity token through Google's API.

    Requires `pip install -r requirements-integrity.txt`, a Play Console app,
    and a service account with the Play Integrity API enabled.
    """

    name = "play-integrity"

    def __init__(self, package_name, credentials_file):
        if not package_name:
            raise DeviceIntegrityUnavailable("ANDROID_PACKAGE_NAME is not set.")
        if not credentials_file:
            raise DeviceIntegrityUnavailable(
                "PLAY_INTEGRITY_CREDENTIALS is not set."
            )

        from google.oauth2 import service_account
        from googleapiclient.discovery import build

        credentials = service_account.Credentials.from_service_account_file(
            credentials_file,
            scopes=["https://www.googleapis.com/auth/playintegrity"],
        )
        self.package_name = package_name
        self._service = build(
            "playintegrity", "v1", credentials=credentials, cache_discovery=False
        )

    def _decode(self, token):
        return (
            self._service.v1()
            .decodeIntegrityToken(
                packageName=self.package_name, body={"integrityToken": token}
            )
            .execute()
        )

    def verify(self, token, nonce):
        try:
            response = self._decode(token)
        except Exception as exc:  # network, auth, malformed token
            logger.warning("Play Integrity decode failed: %s", exc)
            return IntegrityVerdict.failed("Could not verify the integrity token.")

        payload = response.get("tokenPayloadExternal") or {}
        request_details = payload.get("requestDetails") or {}
        app_integrity = payload.get("appIntegrity") or {}
        device_integrity = payload.get("deviceIntegrity") or {}

        # Standard requests carry `requestHash`; classic requests carry `nonce`.
        presented = request_details.get("nonce") or request_details.get("requestHash")
        if presented != nonce:
            return IntegrityVerdict.failed("Integrity token does not match the challenge.")

        if request_details.get("requestPackageName") != self.package_name:
            return IntegrityVerdict.failed("Integrity token is for a different app.")

        if app_integrity.get("appRecognitionVerdict") not in ACCEPTED_APP_VERDICTS:
            return IntegrityVerdict.failed(
                "This build of the app is not recognised by Google Play."
            )

        verdicts = set(device_integrity.get("deviceRecognitionVerdict") or [])
        if not verdicts & ACCEPTED_DEVICE_VERDICTS:
            return IntegrityVerdict.failed(
                "This device does not meet Play device-integrity requirements."
            )

        return IntegrityVerdict.passed()


@functools.lru_cache(maxsize=1)
def get_integrity_verifier():
    config = settings.DEVICE_INTEGRITY
    if not config["REQUIRED"]:
        return DisabledIntegrityVerifier("Device integrity checking is disabled.")
    try:
        return PlayIntegrityVerifier(
            package_name=config["PACKAGE_NAME"],
            credentials_file=config["CREDENTIALS_FILE"],
        )
    except ImportError:
        logger.warning(
            "DEVICE_INTEGRITY_REQUIRED is on but the Google client is missing; "
            "attendance will return 503. Run: pip install -r requirements-integrity.txt"
        )
        return DisabledIntegrityVerifier(
            "Device integrity dependencies are not installed on this server."
        )
    except DeviceIntegrityUnavailable as exc:
        logger.warning("Play Integrity is not configured: %s", exc)
        return DisabledIntegrityVerifier(str(exc))


def integrity_required():
    return bool(settings.DEVICE_INTEGRITY["REQUIRED"])
