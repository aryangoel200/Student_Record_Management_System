import base64
import io
from unittest import mock

from django.core.cache import cache
from django.test import TestCase
from PIL import Image
from rest_framework import status
from rest_framework.test import APIClient

from Auth.models import User

from .models import FaceEnrollment
from .services import (
    DisabledFaceBackend,
    InvalidImage,
    NoFaceDetected,
    cosine_distance,
    decode_image,
)


def image_data_url(size=(64, 64), colour=(120, 120, 120)):
    buffer = io.BytesIO()
    Image.new("RGB", size, colour).save(buffer, format="PNG")
    return "data:image/png;base64," + base64.b64encode(buffer.getvalue()).decode()


class StubBackend:
    name = "stub"
    threshold = 0.30

    def __init__(self, raises=None):
        self.raises = raises

    def embed(self, image):
        if self.raises:
            raise self.raises
        return [0.1, 0.2, 0.3]


class DecodeImageTests(TestCase):
    def test_decodes_a_data_url_to_an_rgb_array(self):
        array = decode_image(image_data_url())
        self.assertEqual(array.shape, (64, 64, 3))

    def test_accepts_bare_base64_without_the_data_prefix(self):
        payload = image_data_url().split(",", 1)[1]
        self.assertEqual(decode_image(payload).shape, (64, 64, 3))

    def test_oversized_images_are_downscaled(self):
        array = decode_image(image_data_url(size=(3000, 1500)))
        self.assertEqual(max(array.shape[:2]), 1280)

    def test_rejects_junk(self):
        for payload in ["", "not-base64!!", "data:image/png;base64,QUJD"]:
            with self.subTest(payload=payload):
                with self.assertRaises(InvalidImage):
                    decode_image(payload)


class CosineDistanceTests(TestCase):
    def test_identical_vectors_have_zero_distance(self):
        self.assertAlmostEqual(cosine_distance([1, 2, 3], [1, 2, 3]), 0.0, places=6)

    def test_opposite_vectors_are_far_apart(self):
        self.assertAlmostEqual(cosine_distance([1, 0], [-1, 0]), 2.0, places=6)

    def test_zero_vector_does_not_divide_by_zero(self):
        self.assertEqual(cosine_distance([0, 0], [1, 1]), 1.0)


class RegisterImageTests(TestCase):
    def setUp(self):
        cache.clear()
        self.client = APIClient()
        self.alice = User.objects.create_user(
            username="alice", name="Alice", email="alice@example.edu",
            password="a-good-password-1",
        )
        self.bob = User.objects.create_user(
            username="bob", name="Bob", email="bob@example.edu",
            password="a-good-password-1",
        )
        self.image = image_data_url()

    def post(self, payload, backend=None):
        with mock.patch(
            "Face_Recognation.views.get_face_backend",
            return_value=backend or StubBackend(),
        ):
            return self.client.post(
                "/api/Face_Recog/register_image", payload, format="json"
            )

    def test_requires_authentication(self):
        response = self.post({"image": self.image})
        self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)

    def test_enrols_the_calling_user(self):
        self.client.force_authenticate(self.alice)
        response = self.post({"image": self.image})

        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        self.assertEqual(FaceEnrollment.objects.get().user, self.alice)

    def test_cannot_enrol_a_face_on_behalf_of_someone_else(self):
        """The old endpoint took student_Id from the body, so this was possible."""
        self.client.force_authenticate(self.alice)
        response = self.post({"image": self.image, "student_Id": "bob"})

        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        self.assertEqual(FaceEnrollment.objects.count(), 1)
        self.assertEqual(FaceEnrollment.objects.get().user, self.alice)
        self.assertFalse(FaceEnrollment.objects.filter(user=self.bob).exists())

    def test_re_enrolling_replaces_rather_than_duplicates(self):
        self.client.force_authenticate(self.alice)
        self.post({"image": self.image})
        response = self.post({"image": image_data_url(colour=(10, 10, 10))})

        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(FaceEnrollment.objects.filter(user=self.alice).count(), 1)

    def test_no_face_detected_is_reported_not_stored(self):
        self.client.force_authenticate(self.alice)
        response = self.post(
            {"image": self.image}, backend=StubBackend(raises=NoFaceDetected("nope"))
        )

        self.assertEqual(response.status_code, status.HTTP_422_UNPROCESSABLE_ENTITY)
        self.assertEqual(FaceEnrollment.objects.count(), 0)

    def test_disabled_backend_returns_503(self):
        self.client.force_authenticate(self.alice)
        response = self.post({"image": self.image}, backend=DisabledFaceBackend())

        self.assertEqual(response.status_code, status.HTTP_503_SERVICE_UNAVAILABLE)
        self.assertEqual(FaceEnrollment.objects.count(), 0)

    def test_embedding_is_never_sent_back_to_the_client(self):
        self.client.force_authenticate(self.alice)
        response = self.post({"image": self.image})
        self.assertNotIn("embedding", response.data)

    def test_user_can_view_and_withdraw_their_enrolment(self):
        self.client.force_authenticate(self.alice)
        self.post({"image": self.image})

        self.assertEqual(
            self.client.get("/api/Face_Recog/enrollment").status_code,
            status.HTTP_200_OK,
        )
        self.assertEqual(
            self.client.delete("/api/Face_Recog/enrollment").status_code,
            status.HTTP_204_NO_CONTENT,
        )
        self.assertEqual(FaceEnrollment.objects.count(), 0)

    def test_the_old_image_verification_endpoint_is_gone(self):
        """It returned success and left the browser to call /Home/attendance."""
        self.client.force_authenticate(self.alice)
        response = self.client.post(
            "/api/Face_Recog/image_verification", {"image": self.image}, format="json"
        )
        self.assertEqual(response.status_code, status.HTTP_404_NOT_FOUND)
