"""Consistent API error shape.

Every failure comes back as a non-2xx status with a JSON body containing a
human-readable ``detail``, plus ``errors`` for field-level validation problems:

    {"detail": "Course name already taken.", "errors": {"name": ["..."]}}

The old code returned HTTP 200 with a ``msg`` string for every error, which
forced the frontend to match on English prose. Don't reintroduce that.
"""

import logging

from rest_framework.views import exception_handler as drf_exception_handler

logger = logging.getLogger(__name__)


def _flatten_detail(detail):
    """Pull a single readable sentence out of a DRF error payload."""
    if isinstance(detail, str):
        return detail
    if isinstance(detail, list):
        return _flatten_detail(detail[0]) if detail else "Invalid request."
    if isinstance(detail, dict):
        for key, value in detail.items():
            message = _flatten_detail(value)
            if key in ("non_field_errors", "detail"):
                return message
            return f"{key}: {message}"
    return "Invalid request."


def api_exception_handler(exc, context):
    response = drf_exception_handler(exc, context)
    if response is None:
        # Not a DRF exception — let Django's handler turn it into a 500 so the
        # traceback still surfaces in logs rather than being swallowed.
        logger.exception("Unhandled exception in %s", context.get("view"))
        return None

    data = response.data
    if isinstance(data, dict) and set(data) <= {"detail"}:
        payload = {"detail": _flatten_detail(data.get("detail"))}
    else:
        payload = {"detail": _flatten_detail(data), "errors": data}
    response.data = payload
    return response
