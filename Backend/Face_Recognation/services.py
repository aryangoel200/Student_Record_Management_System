"""Face embedding + matching, behind a swappable backend.

The concrete implementation is DeepFace, which installs from PyPI with no
compiler toolchain (the previous `face_recognition` package needed dlib and
cmake). Everything above this module talks to `get_face_backend()`, so swapping
in InsightFace or anything else means adding one class here.

Nothing in this module authorises anything. It answers "do these two faces
match"; deciding what that permits is the caller's job — see
Home.views.mark_attendance.
"""

import base64
import binascii
import functools
import io
import logging
import re

import numpy as np
from django.conf import settings
from PIL import Image, UnidentifiedImageError

logger = logging.getLogger(__name__)

# Longest edge we feed to the detector. Camera frames are far larger than any
# face model needs, and downscaling first is both faster and a cheap guard
# against decompression-bomb payloads.
MAX_IMAGE_EDGE = 1280

_DATA_URL_RE = re.compile(r"^data:image/[a-zA-Z0-9.+-]+;base64,", re.IGNORECASE)


class FaceRecognitionError(Exception):
    """Base class for anything that stops us producing a verdict."""


class InvalidImage(FaceRecognitionError):
    pass


class NoFaceDetected(FaceRecognitionError):
    pass


class MultipleFacesDetected(FaceRecognitionError):
    pass


class BackendUnavailable(FaceRecognitionError):
    """Face recognition is switched off, or its dependencies are missing."""


def decode_image(data_url):
    """Turn a browser `data:` URL into an RGB numpy array."""
    if not isinstance(data_url, str) or not data_url.strip():
        raise InvalidImage("No image supplied.")

    payload = _DATA_URL_RE.sub("", data_url.strip(), count=1)
    try:
        raw = base64.b64decode(payload, validate=True)
    except (binascii.Error, ValueError):
        raise InvalidImage("Image is not valid base64 data.") from None
    if not raw:
        raise InvalidImage("Image is empty.")

    try:
        image = Image.open(io.BytesIO(raw))
        image.load()
    except (UnidentifiedImageError, OSError):
        raise InvalidImage("Could not decode that image.") from None

    image = image.convert("RGB")
    if max(image.size) > MAX_IMAGE_EDGE:
        scale = MAX_IMAGE_EDGE / max(image.size)
        new_size = (max(1, int(image.width * scale)), max(1, int(image.height * scale)))
        image = image.resize(new_size, Image.LANCZOS)

    return np.asarray(image, dtype=np.uint8)


def cosine_distance(a, b):
    a = np.asarray(a, dtype=np.float64)
    b = np.asarray(b, dtype=np.float64)
    norms = np.linalg.norm(a) * np.linalg.norm(b)
    if norms == 0:
        return 1.0
    return float(1.0 - np.dot(a, b) / norms)


class BaseFaceBackend:
    name = "base"

    @property
    def threshold(self):
        return float(settings.FACE_RECOGNITION["THRESHOLD"])

    def embed(self, image):
        """Return a list[float] embedding for the single face in `image`."""
        raise NotImplementedError

    def compare(self, known_embedding, candidate_embedding):
        """Return (is_match, distance)."""
        distance = cosine_distance(known_embedding, candidate_embedding)
        return distance <= self.threshold, distance


class DisabledFaceBackend(BaseFaceBackend):
    """Used when FACE_RECOGNITION_ENABLED is false or DeepFace is missing.

    It refuses rather than approving, so turning face recognition off degrades
    to "nobody can face-verify" instead of "everybody passes".
    """

    name = "disabled"

    def __init__(self, reason="Face recognition is not enabled on this server."):
        self.reason = reason

    def embed(self, image):
        raise BackendUnavailable(self.reason)

    def compare(self, known_embedding, candidate_embedding):
        raise BackendUnavailable(self.reason)


class DeepFaceBackend(BaseFaceBackend):
    name = "deepface"

    def __init__(self, model_name, detector_backend):
        from deepface import DeepFace  # imported lazily; pulls in TensorFlow

        self._deepface = DeepFace
        self.model_name = model_name
        self.detector_backend = detector_backend

    def embed(self, image):
        # DeepFace treats a numpy array as if it came from cv2.imread, i.e. BGR.
        bgr = image[:, :, ::-1]
        try:
            faces = self._deepface.represent(
                img_path=bgr,
                model_name=self.model_name,
                detector_backend=self.detector_backend,
                enforce_detection=True,
                align=True,
            )
        except ValueError as exc:
            # DeepFace signals "no face here" with a ValueError.
            if "face could not be detected" in str(exc).lower():
                raise NoFaceDetected(
                    "No face detected. Face the camera in good light and retry."
                ) from exc
            raise InvalidImage(str(exc)) from exc

        if not faces:
            raise NoFaceDetected("No face detected in the image.")
        if len(faces) > 1:
            raise MultipleFacesDetected(
                f"{len(faces)} faces detected — only one person may be in frame."
            )
        return [float(v) for v in faces[0]["embedding"]]


@functools.lru_cache(maxsize=1)
def get_face_backend():
    """The process-wide backend. Cached: model weights load once, not per request."""
    config = settings.FACE_RECOGNITION
    if not config["ENABLED"]:
        return DisabledFaceBackend()
    try:
        return DeepFaceBackend(
            model_name=config["MODEL"], detector_backend=config["DETECTOR"]
        )
    except ImportError:
        logger.warning(
            "FACE_RECOGNITION_ENABLED is true but deepface is not installed; "
            "face verification will return 503. Run: pip install deepface"
        )
        return DisabledFaceBackend(
            "Face recognition dependencies are not installed on this server."
        )
