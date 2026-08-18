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
from django.test import TestCase
from django.utils import timezone as dj_timezone
from PIL import Image
from rest_framework import status
from rest_framework.test import APIClient

from Auth.models import Role, User
from Face_Recognation.models import FaceEnrollment
from Face_Recognation.services import NoFaceDetected

from .models import AttendanceRecord, Course, Enrollment, Session

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
