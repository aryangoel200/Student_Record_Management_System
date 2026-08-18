"""Maps face-recognition failures onto HTTP responses.

Kept apart from services.py so the recognition code stays free of any web
framework, and shared by both the enrolment view and Home.mark_attendance so
the two report identical failures identically.
"""

from rest_framework import status
from rest_framework.response import Response

from .services import (
    BackendUnavailable,
    InvalidImage,
    MultipleFacesDetected,
    NoFaceDetected,
)

_STATUS_BY_ERROR = [
    (BackendUnavailable, status.HTTP_503_SERVICE_UNAVAILABLE),
    (InvalidImage, status.HTTP_400_BAD_REQUEST),
    (NoFaceDetected, status.HTTP_422_UNPROCESSABLE_ENTITY),
    (MultipleFacesDetected, status.HTTP_422_UNPROCESSABLE_ENTITY),
]


def face_error_response(exc):
    for error_type, http_status in _STATUS_BY_ERROR:
        if isinstance(exc, error_type):
            return Response({"detail": str(exc)}, status=http_status)
    return Response(
        {"detail": "Face verification failed."},
        status=status.HTTP_422_UNPROCESSABLE_ENTITY,
    )
