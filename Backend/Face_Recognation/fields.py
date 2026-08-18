"""Serializer field for a captured face frame."""

from django.conf import settings
from rest_framework import serializers

# A 720p JPEG at quality 80 is ~150 KB. This ceiling is generous for that and
# still small enough that a malicious client cannot exhaust memory.
MAX_IMAGE_BYTES = 8 * 1024 * 1024


class FaceImageField(serializers.Field):
    """Accepts a captured frame from either client style.

    - An uploaded file, when a native client posts `multipart/form-data`.
      This is the path Android should use: raw JPEG bytes, no base64.
    - A `data:` URL string, for JSON clients and for `curl` during testing.
      Base64 inflates the payload by a third, so avoid it on mobile data.

    Returns bytes or the raw string; `services.decode_image` handles both.
    """

    default_error_messages = {
        "invalid": "Send the frame as an uploaded file or a base64 data URL.",
        "empty": "No image supplied.",
        "too_large": "Image is larger than {max_mb:.0f} MB.",
    }

    def __init__(self, **kwargs):
        kwargs.setdefault("write_only", True)
        super().__init__(**kwargs)

    def to_internal_value(self, data):
        max_bytes = getattr(settings, "MAX_FACE_IMAGE_BYTES", MAX_IMAGE_BYTES)

        if hasattr(data, "read"):  # UploadedFile
            size = getattr(data, "size", None)
            if size == 0:
                self.fail("empty")
            if size is not None and size > max_bytes:
                self.fail("too_large", max_mb=max_bytes / (1024 * 1024))
            return data.read()

        if isinstance(data, str):
            if not data.strip():
                self.fail("empty")
            # base64 is 4 characters per 3 bytes.
            if len(data) > max_bytes * 4 / 3:
                self.fail("too_large", max_mb=max_bytes / (1024 * 1024))
            return data

        self.fail("invalid")

    def to_representation(self, value):
        # Write-only: a captured frame is never echoed back.
        return None
