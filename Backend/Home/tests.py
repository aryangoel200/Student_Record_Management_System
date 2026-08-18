"""End-to-end checks for the RBAC matrix and the attendance rules.

The attendance tests matter most: they pin down the behaviour that the old
implementation got wrong, where the face match and the geofence ran in the
browser and the server took the result on trust.
"""

import base64
import io
from datetime import date, datetime, time
from unittest import mock

from django.core.cache import cache
from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import TestCase, override_settings
from django.utils import timezone as dj_timezone
from PIL import Image
from rest_framework import status
from rest_framework.test import APIClient

from Auth.models import Role, User
from common.integrity import DeviceIntegrityUnavailable, IntegrityVerdict
from Face_Recognation.models import FaceEnrollment
from Face_Recognation.services import NoFaceDetected

from .models import AttendanceRecord, Course, Enrollment, Repeat, Session

# A point on the IIT Goa campus, and one ~1.5 km away.
CAMPUS_LAT, CAMPUS_LON = 15.3925, 73.8785
FAR_LAT, FAR_LON = 15.4060, 73.8785

# 10:30 on a fixed day, so "is this session open?" never depends on when the
# suite happens to run.
FROZEN_NOW = dj_timezone.make_aware(
    datetime(2026, 3, 10, 10, 30), dj_timezone.get_current_timezone()
)


_real_localtime = dj_timezone.localtime


def freeze_now(moment=FROZEN_NOW):
    """Pin `timezone.localtime()` for both Home.models and Home.views.

    Passing `new=` rather than `return_value=` keeps the mock out of the test
    method's signature, and delegating when an argument is supplied leaves
    `localtime(some_datetime)` (used when serialising) behaving normally.
    """

    def fake_localtime(value=None, timezone=None):
        if value is None:
            return moment
        return _real_localtime(value, timezone)

    return mock.patch("django.utils.timezone.localtime", new=fake_localtime)


def sample_image_data_url():
    buffer = io.BytesIO()
    Image.new("RGB", (64, 64), (128, 128, 128)).save(buffer, format="PNG")
    encoded = base64.b64encode(buffer.getvalue()).decode()
    return f"data:image/png;base64,{encoded}"


class StubFaceBackend:
    """Stands in for DeepFace so tests neither download nor run a model."""

    name = "stub"
    threshold = 0.30

    def __init__(self, matches=True, raises=None):
        self.matches = matches
        self.raises = raises

    def embed(self, image):
        if self.raises:
            raise self.raises
        return [1.0, 0.0, 0.0]

    def compare(self, known, candidate):
        return (True, 0.05) if self.matches else (False, 0.85)


class BaseAPITestCase(TestCase):
    def setUp(self):
        cache.clear()  # throttle buckets
        self.client = APIClient()

        self.teacher = User.objects.create_user(
            username="teach1", name="Dr Sinha", email="t1@example.edu",
            password="a-good-password-1", role=Role.TEACHER,
        )
        self.other_teacher = User.objects.create_user(
            username="teach2", name="Other Teacher", email="t2@example.edu",
            password="a-good-password-1", role=Role.TEACHER,
        )
        self.student = User.objects.create_user(
            username="2021BCS001", name="Asha", email="s1@example.edu",
            password="a-good-password-1",
        )
        self.other_student = User.objects.create_user(
            username="2021BCS002", name="Ravi", email="s2@example.edu",
            password="a-good-password-1",
        )
        self.admin = User.objects.create_superuser(
            username="admin1", name="Admin", email="a@example.edu",
            password="a-good-password-1",
        )

        self.course = Course.objects.create(
            name="CS210", teacher=self.teacher, verification_code="ABCD2345"
        )
        Enrollment.objects.create(course=self.course, student=self.student)

    def as_(self, user):
        self.client.force_authenticate(user=user)

    def make_session(self, **overrides):
        defaults = {
            "course": self.course,
            "date": date(2026, 3, 10),
            "start_time": time(10, 0),
            "end_time": time(11, 0),
            "lat": CAMPUS_LAT,
            "lon": CAMPUS_LON,
            "radius_m": 100.0,
        }
        return Session.objects.create(**{**defaults, **overrides})

    def slot(self, session, **extra):
        return {
            "course_name": session.course.name,
            "date": str(session.date),
            "start_time": str(session.start_time),
            "end_time": str(session.end_time),
            **extra,
        }


class AuthenticationRequiredTests(BaseAPITestCase):
    def test_every_home_endpoint_rejects_anonymous_callers(self):
        """DRF used to default to AllowAny here — all of these were wide open."""
        endpoints = [
            "/api/Home/course",
            "/api/Home/create",
            "/api/Home/attendance",
            "/api/Home/attendance/manual",
            "/api/Home/course_registration",
            "/api/Home/show_created",
            "/api/Home/show_enrolled",
            "/api/Home/show_sessions",
            "/api/Home/show_active_sessions",
            "/api/Home/show_students",
            "/api/Home/show_students_in_session",
            "/api/Home/delete_course",
            "/api/Home/delete_session",
            "/api/Home/course_stats",
        ]
        for url in endpoints:
            with self.subTest(url=url):
                response = self.client.post(url, {}, format="json")
                self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)

    def test_username_availability_stays_public(self):
        response = self.client.post(
            "/api/Home/username_availability", {"username": "nobody"}, format="json"
        )
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertTrue(response.data["available"])


class CourseTests(BaseAPITestCase):
    def test_teacher_creates_exactly_one_course(self):
        """The old view saved twice, leaving two rows sharing one code."""
        self.as_(self.teacher)
        response = self.client.post(
            "/api/Home/course", {"name": "CS222"}, format="json"
        )

        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        self.assertEqual(Course.objects.filter(name="CS222").count(), 1)
        self.assertEqual(response.data["teacher"], "teach1")

    def test_course_owner_is_the_caller_not_the_request_body(self):
        self.as_(self.teacher)
        response = self.client.post(
            "/api/Home/course",
            {"name": "CS999", "teacher": "teach2"},
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        self.assertEqual(Course.objects.get(name="CS999").teacher, self.teacher)

    def test_verification_codes_are_long_and_unique(self):
        self.as_(self.teacher)
        codes = set()
        for index in range(15):
            response = self.client.post(
                "/api/Home/course", {"name": f"C{index}"}, format="json"
            )
            codes.add(response.data["verification_code"])
        self.assertEqual(len(codes), 15)
        self.assertTrue(all(len(code) == 8 for code in codes))

    def test_students_cannot_create_courses(self):
        self.as_(self.student)
        response = self.client.post(
            "/api/Home/course", {"name": "CS404"}, format="json"
        )
        self.assertEqual(response.status_code, status.HTTP_403_FORBIDDEN)

    def test_duplicate_course_name_is_rejected(self):
        self.as_(self.teacher)
        response = self.client.post(
            "/api/Home/course", {"name": "CS210"}, format="json"
        )
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)

    def test_a_teacher_cannot_delete_another_teachers_course(self):
        self.as_(self.other_teacher)
        response = self.client.post(
            "/api/Home/delete_course", {"course_name": "CS210"}, format="json"
        )
        self.assertEqual(response.status_code, status.HTTP_403_FORBIDDEN)
        self.assertTrue(Course.objects.filter(name="CS210").exists())

    def test_owner_can_delete_and_cascade_removes_sessions_and_attendance(self):
        session = self.make_session()
        AttendanceRecord.objects.create(session=session, student=self.student)

        self.as_(self.teacher)
        response = self.client.post(
            "/api/Home/delete_course", {"course_name": "CS210"}, format="json"
        )

        self.assertEqual(response.status_code, status.HTTP_204_NO_CONTENT)
        self.assertFalse(Course.objects.filter(name="CS210").exists())
        self.assertEqual(Session.objects.count(), 0)
        self.assertEqual(AttendanceRecord.objects.count(), 0)
        self.assertEqual(Enrollment.objects.count(), 0)

    def test_admin_can_manage_any_course(self):
        self.as_(self.admin)
        response = self.client.post(
            "/api/Home/delete_course", {"course_name": "CS210"}, format="json"
        )
        self.assertEqual(response.status_code, status.HTTP_204_NO_CONTENT)

    def test_show_created_lists_only_your_own_courses(self):
        Course.objects.create(
            name="CS777", teacher=self.other_teacher, verification_code="ZZZZ9999"
        )
        self.as_(self.teacher)
        response = self.client.post("/api/Home/show_created", {}, format="json")
        self.assertEqual([c["name"] for c in response.data], ["CS210"])


class EnrollmentTests(BaseAPITestCase):
    def test_student_enrols_with_a_valid_code(self):
        self.as_(self.other_student)
        response = self.client.post(
            "/api/Home/course_registration",
            {"verification_code_entered": "ABCD2345"},
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        self.assertTrue(
            Enrollment.objects.filter(
                course=self.course, student=self.other_student
            ).exists()
        )

    def test_enrolling_twice_conflicts_and_creates_no_duplicate(self):
        self.as_(self.student)
        response = self.client.post(
            "/api/Home/course_registration",
            {"verification_code_entered": "ABCD2345"},
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_409_CONFLICT)
        self.assertEqual(
            Enrollment.objects.filter(course=self.course, student=self.student).count(),
            1,
        )

    def test_bad_code_is_a_404(self):
        self.as_(self.other_student)
        response = self.client.post(
            "/api/Home/course_registration",
            {"verification_code_entered": "NOPENOPE"},
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_404_NOT_FOUND)

    def test_teacher_cannot_enrol_as_a_student(self):
        self.as_(self.other_teacher)
        response = self.client.post(
            "/api/Home/course_registration",
            {"verification_code_entered": "ABCD2345"},
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_403_FORBIDDEN)


class SessionTests(BaseAPITestCase):
    payload = {
        "course_name": "CS210",
        "date": "2026-03-10",
        "start_time": "10:00",
        "end_time": "11:00",
        "lat": CAMPUS_LAT,
        "lon": CAMPUS_LON,
    }

    def test_owner_creates_a_session_with_a_default_radius(self):
        self.as_(self.teacher)
        response = self.client.post("/api/Home/create", self.payload, format="json")
        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        self.assertEqual(Session.objects.count(), 1)
        self.assertGreater(Session.objects.get().radius_m, 0)

    def test_non_owner_cannot_create_a_session_on_that_course(self):
        self.as_(self.other_teacher)
        response = self.client.post("/api/Home/create", self.payload, format="json")
        self.assertEqual(response.status_code, status.HTTP_403_FORBIDDEN)
        self.assertEqual(Session.objects.count(), 0)

    def test_duplicate_slot_conflicts(self):
        self.as_(self.teacher)
        self.client.post("/api/Home/create", self.payload, format="json")
        response = self.client.post("/api/Home/create", self.payload, format="json")
        self.assertEqual(response.status_code, status.HTTP_409_CONFLICT)
        self.assertEqual(Session.objects.count(), 1)

    def test_end_time_must_follow_start_time(self):
        self.as_(self.teacher)
        response = self.client.post(
            "/api/Home/create",
            {**self.payload, "start_time": "11:00", "end_time": "10:00"},
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)

    def test_time_formats_with_and_without_seconds_identify_the_same_session(self):
        """Slots are parsed, so "10:00" and "10:00:00" are the same key."""
        session = self.make_session()
        self.as_(self.teacher)
        response = self.client.post(
            "/api/Home/show_students_in_session",
            self.slot(session, start_time="10:00", end_time="11:00"),
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_200_OK)

    @freeze_now()
    def test_active_sessions_lists_only_what_is_running_now(self):
        running = self.make_session()
        self.make_session(start_time=time(14, 0), end_time=time(15, 0))
        self.make_session(date=date(2026, 3, 11))

        self.as_(self.student)
        response = self.client.post(
            "/api/Home/show_active_sessions", {"course_name": "CS210"}, format="json"
        )
        self.assertEqual([s["id"] for s in response.data], [running.id])

    @freeze_now()
    def test_active_sessions_hides_ones_already_marked(self):
        running = self.make_session()
        AttendanceRecord.objects.create(session=running, student=self.student)

        self.as_(self.student)
        response = self.client.post(
            "/api/Home/show_active_sessions", {"course_name": "CS210"}, format="json"
        )
        self.assertEqual(response.data, [])

    def test_unenrolled_student_cannot_see_a_courses_sessions(self):
        self.make_session()
        self.as_(self.other_student)
        response = self.client.post(
            "/api/Home/show_sessions", {"course_name": "CS210"}, format="json"
        )
        self.assertEqual(response.status_code, status.HTTP_403_FORBIDDEN)

    def test_sessions_are_annotated_with_the_callers_presence(self):
        present = self.make_session()
        absent = self.make_session(start_time=time(14, 0), end_time=time(15, 0))
        AttendanceRecord.objects.create(session=present, student=self.student)

        self.as_(self.student)
        response = self.client.post(
            "/api/Home/show_sessions", {"course_name": "CS210"}, format="json"
        )
        presence = {s["id"]: s["presence"] for s in response.data}
        self.assertEqual(presence[present.id], "present")
        self.assertEqual(presence[absent.id], "absent")


class MarkAttendanceTests(BaseAPITestCase):
    def setUp(self):
        super().setUp()
        self.session = self.make_session()
        FaceEnrollment.objects.create(
            user=self.student, embedding=[1.0, 0.0, 0.0], model_name="stub"
        )
        self.image = sample_image_data_url()

    def mark(self, user=None, backend=None, **overrides):
        self.as_(user or self.student)
        payload = self.slot(
            self.session, lat=CAMPUS_LAT, lon=CAMPUS_LON, image=self.image
        )
        payload.update(overrides)
        with mock.patch(
            "Home.views.get_face_backend", return_value=backend or StubFaceBackend()
        ):
            return self.client.post("/api/Home/attendance", payload, format="json")

    @freeze_now()
    def test_happy_path_marks_the_caller_present(self):
        response = self.mark()
        self.assertEqual(response.status_code, status.HTTP_201_CREATED)

        record = AttendanceRecord.objects.get()
        self.assertEqual(record.student, self.student)
        self.assertEqual(record.session, self.session)
        self.assertEqual(record.method, AttendanceRecord.Method.FACE)
        self.assertIsNotNone(record.distance_m)

    @freeze_now()
    def test_image_is_required(self):
        """Without a photo there is nothing to verify, so this must not pass."""
        self.as_(self.student)
        payload = self.slot(self.session, lat=CAMPUS_LAT, lon=CAMPUS_LON)
        response = self.client.post("/api/Home/attendance", payload, format="json")

        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertEqual(AttendanceRecord.objects.count(), 0)

    @freeze_now()
    def test_coordinates_are_required(self):
        self.as_(self.student)
        payload = self.slot(self.session, image=self.image)
        response = self.client.post("/api/Home/attendance", payload, format="json")

        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertEqual(AttendanceRecord.objects.count(), 0)

    @freeze_now()
    def test_outside_the_geofence_is_refused_server_side(self):
        """The browser used to decide this; now the server does."""
        response = self.mark(lat=FAR_LAT, lon=FAR_LON)

        self.assertEqual(response.status_code, status.HTTP_403_FORBIDDEN)
        self.assertGreater(response.data["distance_m"], self.session.radius_m)
        self.assertEqual(AttendanceRecord.objects.count(), 0)

    @freeze_now()
    def test_face_mismatch_is_refused(self):
        response = self.mark(backend=StubFaceBackend(matches=False))

        self.assertEqual(response.status_code, status.HTTP_403_FORBIDDEN)
        self.assertEqual(AttendanceRecord.objects.count(), 0)

    @freeze_now()
    def test_no_face_in_frame_is_refused(self):
        response = self.mark(
            backend=StubFaceBackend(raises=NoFaceDetected("No face detected."))
        )

        self.assertEqual(response.status_code, status.HTTP_422_UNPROCESSABLE_ENTITY)
        self.assertEqual(AttendanceRecord.objects.count(), 0)

    @freeze_now()
    def test_student_without_a_face_enrolment_is_told_to_enrol(self):
        FaceEnrollment.objects.all().delete()
        # Creating the enrolment in setUp primed the reverse-relation cache on
        # this instance; re-fetch so the view sees the real state.
        self.student = User.objects.get(pk=self.student.pk)
        response = self.mark()

        self.assertEqual(response.status_code, status.HTTP_428_PRECONDITION_REQUIRED)
        self.assertEqual(AttendanceRecord.objects.count(), 0)

    @freeze_now()
    def test_unenrolled_student_cannot_mark(self):
        response = self.mark(user=self.other_student)

        self.assertEqual(response.status_code, status.HTTP_403_FORBIDDEN)
        self.assertEqual(AttendanceRecord.objects.count(), 0)

    @freeze_now()
    def test_cannot_mark_the_same_session_twice(self):
        self.assertEqual(self.mark().status_code, status.HTTP_201_CREATED)
        self.assertEqual(self.mark().status_code, status.HTTP_409_CONFLICT)
        self.assertEqual(AttendanceRecord.objects.count(), 1)

    def test_cannot_mark_a_session_that_is_not_running(self):
        later = dj_timezone.make_aware(
            datetime(2026, 3, 10, 18, 0), dj_timezone.get_current_timezone()
        )
        with freeze_now(later):
            response = self.mark()
        self.assertEqual(response.status_code, status.HTTP_409_CONFLICT)
        self.assertEqual(AttendanceRecord.objects.count(), 0)

    @freeze_now()
    def test_teachers_cannot_use_the_student_endpoint(self):
        response = self.mark(user=self.teacher)
        self.assertEqual(response.status_code, status.HTTP_403_FORBIDDEN)

    @freeze_now()
    def test_attendance_is_recorded_for_the_token_holder_not_a_body_field(self):
        """You cannot mark a friend present by naming them in the payload."""
        response = self.mark(student_Id="2021BCS002", student_username="2021BCS002")

        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        self.assertEqual(AttendanceRecord.objects.get().student, self.student)

    @freeze_now()
    def test_disabled_face_backend_refuses_rather_than_waving_through(self):
        from Face_Recognation.services import DisabledFaceBackend

        response = self.mark(backend=DisabledFaceBackend())

        self.assertEqual(response.status_code, status.HTTP_503_SERVICE_UNAVAILABLE)
        self.assertEqual(AttendanceRecord.objects.count(), 0)


class MultipartUploadTests(BaseAPITestCase):
    """The Android client posts multipart/form-data, not a base64 data URL."""

    def setUp(self):
        super().setUp()
        self.session = self.make_session()
        FaceEnrollment.objects.create(
            user=self.student, embedding=[1.0, 0.0, 0.0], model_name="stub"
        )

    def jpeg_upload(self, name="frame.jpg", size=(64, 64)):
        buffer = io.BytesIO()
        Image.new("RGB", size, (90, 110, 130)).save(buffer, format="JPEG", quality=80)
        return SimpleUploadedFile(name, buffer.getvalue(), content_type="image/jpeg")

    def post_multipart(self, **overrides):
        self.as_(self.student)
        payload = self.slot(
            self.session,
            lat=str(CAMPUS_LAT),
            lon=str(CAMPUS_LON),
            image=self.jpeg_upload(),
        )
        payload.update(overrides)
        with mock.patch(
            "Home.views.get_face_backend", return_value=StubFaceBackend()
        ):
            return self.client.post(
                "/api/Home/attendance", payload, format="multipart"
            )

    @freeze_now()
    def test_a_jpeg_file_part_is_accepted(self):
        response = self.post_multipart()

        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        self.assertEqual(AttendanceRecord.objects.get().student, self.student)

    @freeze_now()
    def test_multipart_string_fields_are_coerced(self):
        """Every multipart field arrives as text, including lat/lon and booleans."""
        response = self.post_multipart(is_mock_location="false")
        self.assertEqual(response.status_code, status.HTTP_201_CREATED)

    @freeze_now()
    def test_face_enrolment_also_accepts_a_file_part(self):
        self.as_(self.other_student)
        with mock.patch(
            "Face_Recognation.views.get_face_backend", return_value=StubFaceBackend()
        ):
            response = self.client.post(
                "/api/Face_Recog/register_image",
                {"image": self.jpeg_upload()},
                format="multipart",
            )
        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        self.assertTrue(
            FaceEnrollment.objects.filter(user=self.other_student).exists()
        )

    @freeze_now()
    def test_an_oversized_upload_is_rejected(self):
        oversized = SimpleUploadedFile(
            "big.jpg", b"\xff" * (9 * 1024 * 1024), content_type="image/jpeg"
        )
        response = self.post_multipart(image=oversized)

        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertEqual(AttendanceRecord.objects.count(), 0)

    @freeze_now()
    def test_a_non_image_part_is_rejected(self):
        junk = SimpleUploadedFile(
            "notes.txt", b"definitely not a photograph", content_type="text/plain"
        )
        response = self.post_multipart(image=junk)

        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertEqual(AttendanceRecord.objects.count(), 0)


class LocationSignalTests(BaseAPITestCase):
    """Mock-provider and accuracy gates on the reported position."""

    def setUp(self):
        super().setUp()
        self.session = self.make_session()
        FaceEnrollment.objects.create(
            user=self.student, embedding=[1.0, 0.0, 0.0], model_name="stub"
        )
        self.image = sample_image_data_url()

    def mark(self, **overrides):
        self.as_(self.student)
        payload = self.slot(
            self.session, lat=CAMPUS_LAT, lon=CAMPUS_LON, image=self.image
        )
        payload.update(overrides)
        with mock.patch(
            "Home.views.get_face_backend", return_value=StubFaceBackend()
        ):
            return self.client.post("/api/Home/attendance", payload, format="json")

    @freeze_now()
    def test_a_mocked_location_is_refused(self):
        response = self.mark(is_mock_location=True)

        self.assertEqual(response.status_code, status.HTTP_403_FORBIDDEN)
        self.assertEqual(AttendanceRecord.objects.count(), 0)

    @freeze_now()
    def test_the_mock_flag_defaults_to_false_when_absent(self):
        """Older clients that never send the field must still work."""
        self.assertEqual(self.mark().status_code, status.HTTP_201_CREATED)

    @freeze_now()
    @override_settings(REJECT_MOCK_LOCATION=False)
    def test_the_mock_gate_can_be_switched_off(self):
        response = self.mark(is_mock_location=True)
        self.assertEqual(response.status_code, status.HTTP_201_CREATED)

    @freeze_now()
    @override_settings(MAX_LOCATION_ACCURACY_M=50.0)
    def test_an_imprecise_fix_is_refused(self):
        response = self.mark(location_accuracy_m=500.0)

        self.assertEqual(response.status_code, status.HTTP_403_FORBIDDEN)
        self.assertEqual(response.data["accuracy_m"], 500.0)
        self.assertEqual(AttendanceRecord.objects.count(), 0)

    @freeze_now()
    @override_settings(MAX_LOCATION_ACCURACY_M=50.0)
    def test_a_precise_fix_passes(self):
        self.assertEqual(
            self.mark(location_accuracy_m=12.5).status_code, status.HTTP_201_CREATED
        )

    @freeze_now()
    def test_accuracy_is_optional(self):
        self.assertEqual(self.mark().status_code, status.HTTP_201_CREATED)


class StubIntegrityVerifier:
    name = "stub"

    def __init__(self, ok=True, reason="Device failed attestation.", raises=None):
        self.ok = ok
        self.reason = reason
        self.raises = raises
        self.seen_nonce = None

    def verify(self, token, nonce):
        if self.raises:
            raise self.raises
        self.seen_nonce = nonce
        return (
            IntegrityVerdict.passed()
            if self.ok
            else IntegrityVerdict.failed(self.reason)
        )


@override_settings(
    DEVICE_INTEGRITY={
        "REQUIRED": True,
        "PACKAGE_NAME": "ac.in.iitgoa.attendance",
        "CREDENTIALS_FILE": "",
        "NONCE_TTL_SECONDS": 300,
    }
)
class DeviceIntegrityTests(BaseAPITestCase):
    def setUp(self):
        super().setUp()
        self.session = self.make_session()
        FaceEnrollment.objects.create(
            user=self.student, embedding=[1.0, 0.0, 0.0], model_name="stub"
        )
        self.image = sample_image_data_url()

    def challenge(self, user=None):
        self.as_(user or self.student)
        response = self.client.post("/api/Home/attendance/challenge", {}, format="json")
        return response.data["nonce"]

    def mark(self, verifier=None, user=None, **overrides):
        self.as_(user or self.student)
        payload = self.slot(
            self.session, lat=CAMPUS_LAT, lon=CAMPUS_LON, image=self.image
        )
        payload.update(overrides)
        with mock.patch(
            "Home.views.get_face_backend", return_value=StubFaceBackend()
        ), mock.patch(
            "Home.views.get_integrity_verifier",
            return_value=verifier or StubIntegrityVerifier(),
        ):
            return self.client.post("/api/Home/attendance", payload, format="json")

    def test_challenge_issues_a_nonce_and_advertises_the_requirement(self):
        self.as_(self.student)
        response = self.client.post("/api/Home/attendance/challenge", {}, format="json")

        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertTrue(response.data["nonce"])
        self.assertTrue(response.data["integrity_required"])
        self.assertEqual(response.data["expires_in"], 300)

    def test_challenge_requires_a_student(self):
        self.as_(self.teacher)
        response = self.client.post("/api/Home/attendance/challenge", {}, format="json")
        self.assertEqual(response.status_code, status.HTTP_403_FORBIDDEN)

    def test_two_challenges_return_different_nonces(self):
        self.assertNotEqual(self.challenge(), self.challenge())

    @freeze_now()
    def test_a_valid_token_and_nonce_are_accepted(self):
        nonce = self.challenge()
        verifier = StubIntegrityVerifier()
        response = self.mark(
            verifier=verifier, integrity_token="tok", integrity_nonce=nonce
        )

        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        # The nonce reached the verifier, so the token really is bound to it.
        self.assertEqual(verifier.seen_nonce, nonce)

    @freeze_now()
    def test_a_missing_token_is_refused(self):
        response = self.mark()

        self.assertEqual(response.status_code, status.HTTP_428_PRECONDITION_REQUIRED)
        self.assertEqual(AttendanceRecord.objects.count(), 0)

    @freeze_now()
    def test_an_unknown_nonce_is_refused(self):
        response = self.mark(integrity_token="tok", integrity_nonce="never-issued")

        self.assertEqual(response.status_code, status.HTTP_403_FORBIDDEN)
        self.assertEqual(AttendanceRecord.objects.count(), 0)

    @freeze_now()
    def test_a_nonce_cannot_be_replayed(self):
        nonce = self.challenge()
        first = self.mark(integrity_token="tok", integrity_nonce=nonce)
        self.assertEqual(first.status_code, status.HTTP_201_CREATED)

        AttendanceRecord.objects.all().delete()
        second = self.mark(integrity_token="tok", integrity_nonce=nonce)

        self.assertEqual(second.status_code, status.HTTP_403_FORBIDDEN)
        self.assertEqual(AttendanceRecord.objects.count(), 0)

    @freeze_now()
    def test_another_students_nonce_is_not_usable(self):
        stolen = self.challenge(user=self.other_student)
        response = self.mark(
            user=self.student, integrity_token="tok", integrity_nonce=stolen
        )

        self.assertEqual(response.status_code, status.HTTP_403_FORBIDDEN)
        self.assertEqual(AttendanceRecord.objects.count(), 0)

    @freeze_now()
    def test_a_failed_verdict_is_refused(self):
        nonce = self.challenge()
        response = self.mark(
            verifier=StubIntegrityVerifier(ok=False, reason="Rooted device."),
            integrity_token="tok",
            integrity_nonce=nonce,
        )

        self.assertEqual(response.status_code, status.HTTP_403_FORBIDDEN)
        self.assertEqual(response.data["detail"], "Rooted device.")
        self.assertEqual(AttendanceRecord.objects.count(), 0)

    @freeze_now()
    def test_a_failed_verdict_still_burns_the_nonce(self):
        nonce = self.challenge()
        self.mark(
            verifier=StubIntegrityVerifier(ok=False),
            integrity_token="tok",
            integrity_nonce=nonce,
        )
        retry = self.mark(integrity_token="tok", integrity_nonce=nonce)

        self.assertEqual(retry.status_code, status.HTTP_403_FORBIDDEN)
        self.assertEqual(AttendanceRecord.objects.count(), 0)

    @freeze_now()
    def test_an_unconfigured_verifier_fails_closed(self):
        """Requiring integrity without the deps must refuse, never skip."""
        nonce = self.challenge()
        response = self.mark(
            verifier=StubIntegrityVerifier(
                raises=DeviceIntegrityUnavailable("Not installed.")
            ),
            integrity_token="tok",
            integrity_nonce=nonce,
        )

        self.assertEqual(response.status_code, status.HTTP_503_SERVICE_UNAVAILABLE)
        self.assertEqual(AttendanceRecord.objects.count(), 0)


class IntegrityNotRequiredTests(BaseAPITestCase):
    """With the feature off, clients need not send a token at all."""

    def setUp(self):
        super().setUp()
        self.session = self.make_session()
        FaceEnrollment.objects.create(
            user=self.student, embedding=[1.0, 0.0, 0.0], model_name="stub"
        )

    @freeze_now()
    def test_attendance_works_without_any_integrity_fields(self):
        self.as_(self.student)
        payload = self.slot(
            self.session,
            lat=CAMPUS_LAT,
            lon=CAMPUS_LON,
            image=sample_image_data_url(),
        )
        with mock.patch(
            "Home.views.get_face_backend", return_value=StubFaceBackend()
        ):
            response = self.client.post(
                "/api/Home/attendance", payload, format="json"
            )
        self.assertEqual(response.status_code, status.HTTP_201_CREATED)

    def test_challenge_reports_that_integrity_is_optional(self):
        self.as_(self.student)
        response = self.client.post("/api/Home/attendance/challenge", {}, format="json")
        self.assertFalse(response.data["integrity_required"])


class ManualAttendanceTests(BaseAPITestCase):
    def setUp(self):
        super().setUp()
        self.session = self.make_session()

    def test_owner_can_mark_a_student_present_without_face_or_location(self):
        self.as_(self.teacher)
        response = self.client.post(
            "/api/Home/attendance/manual",
            self.slot(self.session, student_username="2021BCS001"),
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_201_CREATED)

        record = AttendanceRecord.objects.get()
        self.assertEqual(record.method, AttendanceRecord.Method.MANUAL)
        self.assertEqual(record.marked_by, self.teacher)

    def test_other_teachers_cannot_touch_this_register(self):
        self.as_(self.other_teacher)
        response = self.client.post(
            "/api/Home/attendance/manual",
            self.slot(self.session, student_username="2021BCS001"),
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_403_FORBIDDEN)
        self.assertEqual(AttendanceRecord.objects.count(), 0)

    def test_students_cannot_use_the_override(self):
        self.as_(self.student)
        response = self.client.post(
            "/api/Home/attendance/manual",
            self.slot(self.session, student_username="2021BCS001"),
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_403_FORBIDDEN)

    def test_cannot_mark_a_student_who_is_not_enrolled(self):
        self.as_(self.teacher)
        response = self.client.post(
            "/api/Home/attendance/manual",
            self.slot(self.session, student_username="2021BCS002"),
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)

    def test_owner_can_remove_an_attendance_row(self):
        AttendanceRecord.objects.create(session=self.session, student=self.student)
        self.as_(self.teacher)
        response = self.client.post(
            "/api/Home/attendance/remove",
            self.slot(self.session, student_username="2021BCS001"),
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_204_NO_CONTENT)
        self.assertEqual(AttendanceRecord.objects.count(), 0)


class RosterAndStatsTests(BaseAPITestCase):
    def test_only_the_owner_sees_the_student_roster(self):
        self.as_(self.other_teacher)
        self.assertEqual(
            self.client.post(
                "/api/Home/show_students", {"course_name": "CS210"}, format="json"
            ).status_code,
            status.HTTP_403_FORBIDDEN,
        )

        self.as_(self.teacher)
        response = self.client.post(
            "/api/Home/show_students", {"course_name": "CS210"}, format="json"
        )
        self.assertEqual([s["username"] for s in response.data], ["2021BCS001"])

    def test_course_stats_are_owner_only_and_handle_an_empty_course(self):
        self.as_(self.other_teacher)
        self.assertEqual(
            self.client.post(
                "/api/Home/course_stats", {"course_name": "CS210"}, format="json"
            ).status_code,
            status.HTTP_403_FORBIDDEN,
        )

        self.as_(self.teacher)
        response = self.client.post(
            "/api/Home/course_stats", {"course_name": "CS210"}, format="json"
        )
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.data["num_enrolled"], 1)
        self.assertEqual(response.data["num_sessions"], 0)
        self.assertEqual(response.data["avg_rate"], 0.0)
        self.assertIsNone(response.data["best_session"])

    def test_course_stats_reports_the_best_attended_session(self):
        busy = self.make_session()
        self.make_session(start_time=time(14, 0), end_time=time(15, 0))
        AttendanceRecord.objects.create(session=busy, student=self.student)

        self.as_(self.teacher)
        response = self.client.post(
            "/api/Home/course_stats", {"course_name": "CS210"}, format="json"
        )
        self.assertEqual(response.data["num_sessions"], 2)
        self.assertEqual(response.data["best_session"]["present"], 1)
        self.assertEqual(response.data["attendance_rate_pct"], 50.0)

    def test_missing_course_is_a_404(self):
        self.as_(self.teacher)
        response = self.client.post(
            "/api/Home/show_students", {"course_name": "NOPE"}, format="json"
        )
        self.assertEqual(response.status_code, status.HTTP_404_NOT_FOUND)


# --- New feature coverage --------------------------------------------------


class RecurrenceExpansionTests(TestCase):
    """The date maths, isolated from HTTP."""

    def test_no_repeat_yields_only_the_start(self):
        from .recurrence import expand

        self.assertEqual(expand(date(2026, 3, 10), Repeat.NONE), [date(2026, 3, 10)])

    def test_weekly_steps_by_seven_days(self):
        from .recurrence import expand

        dates = expand(date(2026, 3, 10), Repeat.WEEKLY, count=3)
        self.assertEqual(
            dates, [date(2026, 3, 10), date(2026, 3, 17), date(2026, 3, 24)]
        )

    def test_interval_two_weekly_is_fortnightly(self):
        from .recurrence import expand

        dates = expand(date(2026, 3, 10), Repeat.WEEKLY, interval=2, count=3)
        self.assertEqual(
            dates, [date(2026, 3, 10), date(2026, 3, 24), date(2026, 4, 7)]
        )

    def test_monthly_keeps_the_day_of_month(self):
        from .recurrence import expand

        dates = expand(date(2026, 1, 15), Repeat.MONTHLY, count=3)
        self.assertEqual(
            dates, [date(2026, 1, 15), date(2026, 2, 15), date(2026, 3, 15)]
        )

    def test_monthly_skips_months_that_are_too_short(self):
        """The 31st recurring monthly must never silently land on the 28th."""
        from .recurrence import expand

        dates = expand(date(2026, 1, 31), Repeat.MONTHLY, count=4)
        self.assertNotIn(date(2026, 2, 28), dates)
        self.assertEqual(dates[0], date(2026, 1, 31))
        self.assertTrue(all(d.day == 31 for d in dates))

    def test_until_stops_the_series(self):
        from .recurrence import expand

        dates = expand(
            date(2026, 3, 10), Repeat.WEEKLY, count=60, until=date(2026, 3, 25)
        )
        self.assertEqual(dates, [date(2026, 3, 10), date(2026, 3, 17), date(2026, 3, 24)])

    def test_count_is_capped(self):
        from .recurrence import expand
        from .recurrence import MAX_OCCURRENCES

        dates = expand(date(2026, 3, 10), Repeat.DAILY, count=10_000)
        self.assertEqual(len(dates), MAX_OCCURRENCES)


class RecurringSessionApiTests(BaseAPITestCase):
    def payload(self, **extra):
        base = {
            "course_name": "CS210",
            "date": "2026-03-10",
            "start_time": "10:00",
            "end_time": "11:00",
            "lat": CAMPUS_LAT,
            "lon": CAMPUS_LON,
        }
        base.update(extra)
        return base

    def test_weekly_series_creates_one_row_per_occurrence(self):
        self.as_(self.teacher)
        response = self.client.post(
            "/api/Home/create",
            self.payload(repeat="weekly", repeat_count=4),
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        self.assertEqual(len(response.data["created"]), 4)
        self.assertEqual(Session.objects.count(), 4)

        # All share one series id, so the batch can be managed together.
        series = {s.series_id for s in Session.objects.all()}
        self.assertEqual(len(series), 1)
        self.assertIsNotNone(series.pop())

    def test_monthly_series_over_a_short_month(self):
        self.as_(self.teacher)
        response = self.client.post(
            "/api/Home/create",
            self.payload(date="2026-01-31", repeat="monthly", repeat_count=3),
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        for session in Session.objects.all():
            self.assertEqual(session.date.day, 31)

    def test_repeat_requires_a_bound(self):
        self.as_(self.teacher)
        response = self.client.post(
            "/api/Home/create", self.payload(repeat="weekly"), format="json"
        )
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertEqual(Session.objects.count(), 0)

    def test_overlapping_dates_are_skipped_not_fatal(self):
        self.make_session(date=date(2026, 3, 17))
        self.as_(self.teacher)
        response = self.client.post(
            "/api/Home/create",
            self.payload(repeat="weekly", repeat_count=3),
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        self.assertEqual(len(response.data["created"]), 2)
        self.assertEqual(response.data["skipped"], ["2026-03-17"])

    def test_a_single_session_has_no_series_id(self):
        self.as_(self.teacher)
        response = self.client.post("/api/Home/create", self.payload(), format="json")
        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        self.assertIsNone(response.data["series_id"])
        self.assertIsNone(Session.objects.get().series_id)

    def test_owner_can_delete_a_whole_series(self):
        self.as_(self.teacher)
        created = self.client.post(
            "/api/Home/create",
            self.payload(repeat="weekly", repeat_count=4),
            format="json",
        )
        series_id = created.data["series_id"]

        response = self.client.post(
            "/api/Home/delete_session_series",
            {"series_id": series_id},
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(Session.objects.count(), 0)

    def test_another_teacher_cannot_delete_the_series(self):
        self.as_(self.teacher)
        created = self.client.post(
            "/api/Home/create",
            self.payload(repeat="weekly", repeat_count=3),
            format="json",
        )
        self.as_(self.other_teacher)
        response = self.client.post(
            "/api/Home/delete_session_series",
            {"series_id": created.data["series_id"]},
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_403_FORBIDDEN)
        self.assertEqual(Session.objects.count(), 3)


class ArchiveCourseTests(BaseAPITestCase):
    def archive(self, archived=True, user=None):
        self.as_(user or self.teacher)
        return self.client.post(
            "/api/Home/archive_course",
            {"course_name": "CS210", "archived": archived},
            format="json",
        )

    def test_owner_can_archive_and_restore(self):
        self.assertEqual(self.archive().status_code, status.HTTP_200_OK)
        self.course.refresh_from_db()
        self.assertTrue(self.course.is_archived)

        self.assertEqual(self.archive(archived=False).status_code, status.HTTP_200_OK)
        self.course.refresh_from_db()
        self.assertFalse(self.course.is_archived)

    def test_archiving_deletes_nothing(self):
        session = self.make_session()
        AttendanceRecord.objects.create(session=session, student=self.student)
        self.archive()

        self.assertEqual(Session.objects.count(), 1)
        self.assertEqual(AttendanceRecord.objects.count(), 1)
        self.assertEqual(Enrollment.objects.count(), 1)

    def test_archived_courses_drop_out_of_both_lists(self):
        self.archive()

        self.as_(self.teacher)
        self.assertEqual(
            self.client.post("/api/Home/show_created", {}, format="json").data, []
        )
        self.as_(self.student)
        self.assertEqual(
            self.client.post("/api/Home/show_enrolled", {}, format="json").data, []
        )

    def test_archived_courses_can_still_be_listed_explicitly(self):
        self.archive()
        self.as_(self.teacher)
        response = self.client.post(
            "/api/Home/show_created", {"include_archived": True}, format="json"
        )
        self.assertEqual([c["name"] for c in response.data], ["CS210"])
        self.assertTrue(response.data[0]["is_archived"])

    def test_no_new_sessions_on_an_archived_course(self):
        self.archive()
        self.as_(self.teacher)
        response = self.client.post(
            "/api/Home/create",
            {
                "course_name": "CS210",
                "date": "2026-03-10",
                "start_time": "10:00",
                "end_time": "11:00",
                "lat": CAMPUS_LAT,
                "lon": CAMPUS_LON,
            },
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_409_CONFLICT)

    def test_students_cannot_archive(self):
        self.assertEqual(
            self.archive(user=self.student).status_code, status.HTTP_403_FORBIDDEN
        )

    def test_another_teacher_cannot_archive(self):
        self.assertEqual(
            self.archive(user=self.other_teacher).status_code,
            status.HTTP_403_FORBIDDEN,
        )


class TodayFeedTests(BaseAPITestCase):
    @freeze_now()
    def test_live_session_comes_first(self):
        later = self.make_session(start_time=time(14, 0), end_time=time(15, 0))
        live = self.make_session()  # 10:00-11:00, frozen now is 10:30
        earlier = self.make_session(start_time=time(8, 0), end_time=time(9, 0))

        self.as_(self.student)
        response = self.client.post("/api/Home/today", {}, format="json")

        self.assertEqual(response.status_code, status.HTTP_200_OK)
        ids = [s["id"] for s in response.data["sessions"]]
        self.assertEqual(ids[0], live.id)
        self.assertEqual(response.data["open_count"], 1)
        # Upcoming before finished.
        self.assertLess(ids.index(later.id), ids.index(earlier.id))

    @freeze_now()
    def test_is_open_is_reported_by_the_server(self):
        self.make_session()
        self.as_(self.student)
        response = self.client.post("/api/Home/today", {}, format="json")
        self.assertTrue(response.data["sessions"][0]["is_open"])

    @freeze_now()
    def test_student_sees_their_own_presence(self):
        session = self.make_session()
        AttendanceRecord.objects.create(session=session, student=self.student)

        self.as_(self.student)
        response = self.client.post("/api/Home/today", {}, format="json")
        self.assertEqual(response.data["sessions"][0]["presence"], "present")

    @freeze_now()
    def test_teacher_sees_a_live_present_count(self):
        session = self.make_session()
        AttendanceRecord.objects.create(session=session, student=self.student)

        self.as_(self.teacher)
        response = self.client.post("/api/Home/today", {}, format="json")
        self.assertEqual(response.data["sessions"][0]["present_count"], 1)

    @freeze_now()
    def test_spans_every_course_not_just_one(self):
        second = Course.objects.create(
            name="CS999", teacher=self.teacher, verification_code="QQQQ2222"
        )
        Enrollment.objects.create(course=second, student=self.student)
        self.make_session()
        Session.objects.create(
            course=second, date=date(2026, 3, 10), start_time=time(12, 0),
            end_time=time(13, 0), lat=CAMPUS_LAT, lon=CAMPUS_LON, radius_m=100.0,
        )

        self.as_(self.student)
        response = self.client.post("/api/Home/today", {}, format="json")
        names = {s["course_name"] for s in response.data["sessions"]}
        self.assertEqual(names, {"CS210", "CS999"})

    @freeze_now()
    def test_other_days_are_excluded(self):
        self.make_session(date=date(2026, 3, 11))
        self.as_(self.student)
        response = self.client.post("/api/Home/today", {}, format="json")
        self.assertEqual(response.data["sessions"], [])

    @freeze_now()
    def test_archived_courses_are_excluded(self):
        self.make_session()
        self.course.archive()
        self.as_(self.student)
        response = self.client.post("/api/Home/today", {}, format="json")
        self.assertEqual(response.data["sessions"], [])

    def test_requires_authentication(self):
        self.client.force_authenticate(user=None)
        self.assertEqual(
            self.client.post("/api/Home/today", {}, format="json").status_code,
            status.HTTP_401_UNAUTHORIZED,
        )


class CourseStudentStatsTests(BaseAPITestCase):
    def setUp(self):
        super().setUp()
        Enrollment.objects.create(course=self.course, student=self.other_student)

    def test_per_student_rows_with_percentages(self):
        a = self.make_session()
        b = self.make_session(start_time=time(14, 0), end_time=time(15, 0))
        AttendanceRecord.objects.create(session=a, student=self.student)
        AttendanceRecord.objects.create(session=b, student=self.student)
        AttendanceRecord.objects.create(
            session=a, student=self.other_student,
            method=AttendanceRecord.Method.MANUAL, marked_by=self.teacher,
        )

        self.as_(self.teacher)
        response = self.client.post(
            "/api/Home/course_student_stats", {"course_name": "CS210"}, format="json"
        )

        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.data["total_sessions"], 2)
        rows = {r["username"]: r for r in response.data["students"]}

        self.assertEqual(rows["2021BCS001"]["attended"], 2)
        self.assertEqual(rows["2021BCS001"]["attendance_pct"], 100.0)
        self.assertEqual(rows["2021BCS002"]["attended"], 1)
        self.assertEqual(rows["2021BCS002"]["attendance_pct"], 50.0)
        self.assertEqual(rows["2021BCS002"]["manual_count"], 1)

    def test_lowest_attendance_is_listed_first(self):
        a = self.make_session()
        AttendanceRecord.objects.create(session=a, student=self.student)

        self.as_(self.teacher)
        response = self.client.post(
            "/api/Home/course_student_stats", {"course_name": "CS210"}, format="json"
        )
        self.assertEqual(response.data["students"][0]["username"], "2021BCS002")

    def test_handles_a_course_with_no_sessions(self):
        self.as_(self.teacher)
        response = self.client.post(
            "/api/Home/course_student_stats", {"course_name": "CS210"}, format="json"
        )
        self.assertEqual(response.data["total_sessions"], 0)
        self.assertTrue(all(r["attendance_pct"] == 0.0 for r in response.data["students"]))

    def test_reports_whether_a_face_is_enrolled(self):
        FaceEnrollment.objects.create(
            user=self.student, embedding=[1.0], model_name="stub"
        )
        self.as_(self.teacher)
        response = self.client.post(
            "/api/Home/course_student_stats", {"course_name": "CS210"}, format="json"
        )
        rows = {r["username"]: r for r in response.data["students"]}
        self.assertTrue(rows["2021BCS001"]["face_enrolled"])
        self.assertFalse(rows["2021BCS002"]["face_enrolled"])

    def test_only_the_owner_can_see_it(self):
        for user in (self.other_teacher, self.student):
            self.as_(user)
            self.assertEqual(
                self.client.post(
                    "/api/Home/course_student_stats",
                    {"course_name": "CS210"},
                    format="json",
                ).status_code,
                status.HTTP_403_FORBIDDEN,
            )
