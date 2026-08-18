import logging
from uuid import uuid4

from django.conf import settings
from django.db import IntegrityError, transaction
from django.db.models import Count
from django.utils import timezone
from rest_framework import status
from rest_framework.decorators import (
    api_view,
    parser_classes,
    permission_classes,
    throttle_classes,
)
from rest_framework.parsers import FormParser, JSONParser, MultiPartParser
from rest_framework.permissions import AllowAny, IsAuthenticated
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle

from Auth.models import Role, User
from common.geo import haversine_metres
from common.integrity import (
    DeviceIntegrityUnavailable,
    consume_nonce,
    get_integrity_verifier,
    integrity_required,
    issue_nonce,
    nonce_ttl_seconds,
)
from common.permissions import IsStudent, IsTeacherOrAdmin
from Face_Recognation.http import face_error_response
from Face_Recognation.models import FaceEnrollment
from Face_Recognation.services import (
    FaceRecognitionError,
    decode_image,
    get_face_backend,
)

from .models import (
    AttendanceRecord,
    Course,
    Enrollment,
    Repeat,
    Session,
    generate_verification_code,
)
from .recurrence import expand
from .selectors import (
    get_course,
    get_course_by_code,
    get_managed_course,
    get_session,
    get_visible_course,
)
from .serializers import (
    ArchiveCourseSerializer,
    AttendanceRecordSerializer,
    CourseCreateSerializer,
    CourseNameSerializer,
    CourseRegistrationSerializer,
    CourseSerializer,
    EnrolledCourseSerializer,
    ManualAttendanceSerializer,
    MarkAttendanceSerializer,
    SessionCreateSerializer,
    SessionSerializer,
    SessionSlotSerializer,
    StudentAttendanceSerializer,
    StudentSerializer,
    UsernameAvailabilitySerializer,
)

logger = logging.getLogger(__name__)


def _validated(serializer_class, request):
    serializer = serializer_class(data=request.data)
    serializer.is_valid(raise_exception=True)
    return serializer.validated_data


def _wants_archived(request):
    """Whether the caller asked to see archived courses too."""
    raw = request.data.get("include_archived") or request.query_params.get(
        "include_archived"
    )
    return str(raw).strip().lower() in {"1", "true", "yes", "on"}


def _present_session_ids(user, course):
    return set(
        AttendanceRecord.objects.filter(
            student=user, session__course=course
        ).values_list("session_id", flat=True)
    )


# --- Courses ---------------------------------------------------------------


@api_view(["POST"])
@permission_classes([IsTeacherOrAdmin])
def create_new_course(request):
    data = _validated(CourseCreateSerializer, request)
    try:
        # The owner is whoever is authenticated. The old version read the
        # teacher from the request body, so anyone could create a course "as"
        # someone else — and it saved the row twice, once via the serializer and
        # once directly, leaving two courses sharing one code.
        #
        # The atomic block is required, not decorative: an IntegrityError marks
        # the surrounding transaction unusable, so it has to be caught around a
        # savepoint we can roll back to.
        with transaction.atomic():
            course = Course.objects.create(
                name=data["name"],
                teacher=request.user,
                verification_code=generate_verification_code(),
            )
    except IntegrityError:
        # Lost a race against a concurrent create with the same name.
        return Response(
            {"detail": "Course name already taken."}, status=status.HTTP_409_CONFLICT
        )
    return Response(CourseSerializer(course).data, status=status.HTTP_201_CREATED)


@api_view(["POST"])
@permission_classes([IsAuthenticated])
def show_created(request):
    """Courses taught by the caller (or, for an admin, by anyone)."""
    if not (request.user.is_teacher or request.user.is_admin):
        return Response([], status=status.HTTP_200_OK)

    queryset = Course.objects.select_related("teacher").annotate(
        enrolled_count=Count("enrollments", distinct=True)
    )
    # Archived courses are hidden unless asked for, so a teacher's list stays
    # about what they are currently teaching.
    if not _wants_archived(request):
        queryset = queryset.filter(archived_at__isnull=True)
    if request.user.is_admin:
        teacher_username = request.data.get("teacher")
        if teacher_username:
            queryset = queryset.filter(teacher__username=teacher_username)
    else:
        queryset = queryset.filter(teacher=request.user)

    return Response(CourseSerializer(queryset, many=True).data)


@api_view(["POST"])
@permission_classes([IsAuthenticated])
def show_enrolled(request):
    """Courses the caller is enrolled in."""
    queryset = (
        Course.objects.filter(enrollments__student=request.user)
        .select_related("teacher")
        .distinct()
    )
    if not _wants_archived(request):
        queryset = queryset.filter(archived_at__isnull=True)
    return Response(EnrolledCourseSerializer(queryset, many=True).data)


@api_view(["POST"])
@permission_classes([IsStudent])
def course_registration(request):
    data = _validated(CourseRegistrationSerializer, request)
    course = get_course_by_code(data["verification_code_entered"])

    with transaction.atomic():
        _, created = Enrollment.objects.get_or_create(
            course=course, student=request.user
        )
    if not created:
        return Response(
            {"detail": "You are already enrolled in this course."},
            status=status.HTTP_409_CONFLICT,
        )
    return Response(
        {
            "detail": f"Enrolled in {course.name}.",
            "course": EnrolledCourseSerializer(course).data,
        },
        status=status.HTTP_201_CREATED,
    )


@api_view(["POST"])
@permission_classes([IsTeacherOrAdmin])
def show_students(request):
    data = _validated(CourseNameSerializer, request)
    course = get_managed_course(request.user, data["course_name"])
    students = User.objects.filter(enrollments__course=course).order_by("username")
    return Response(StudentSerializer(students, many=True).data)


@api_view(["POST"])
@permission_classes([IsTeacherOrAdmin])
def delete_course(request):
    data = _validated(CourseNameSerializer, request)
    course = get_managed_course(request.user, data["course_name"])
    # Sessions, attendance rows and enrolments all cascade — no hand-rolled
    # multi-table cleanup that can leave the database half-updated.
    course.delete()
    return Response(status=status.HTTP_204_NO_CONTENT)


@api_view(["POST"])
@permission_classes([IsTeacherOrAdmin])
def course_stats(request):
    data = _validated(CourseNameSerializer, request)
    course = get_managed_course(request.user, data["course_name"])

    num_enrolled = course.enrollments.count()
    sessions = list(
        course.sessions.annotate(present=Count("attendance_records")).order_by(
            "-present"
        )
    )
    num_sessions = len(sessions)
    total_present = sum(s.present for s in sessions)
    avg_rate = total_present / num_sessions if num_sessions else 0.0

    payload = {
        "course_name": course.name,
        "num_enrolled": num_enrolled,
        "num_sessions": num_sessions,
        # Mean number of students present per session.
        "avg_rate": round(avg_rate, 2),
        # ...and that as a percentage of the enrolled cohort.
        "attendance_rate_pct": (
            round(100 * avg_rate / num_enrolled, 1) if num_enrolled else 0.0
        ),
        "best_session": None,
    }
    if sessions and sessions[0].present:
        best = sessions[0]
        payload["best_session"] = {
            "date": best.date,
            "start_time": best.start_time,
            "end_time": best.end_time,
            "present": best.present,
        }
    return Response(payload)


# --- Sessions --------------------------------------------------------------


@api_view(["POST"])
@permission_classes([IsTeacherOrAdmin])
def create_new_session(request):
    """Create one session, or a whole recurring series.

    Occurrences are written as individual rows sharing a `series_id`. Slots that
    already exist are skipped rather than failing the request, so extending a
    series over dates that partly overlap an existing one is not an error.
    """
    data = _validated(SessionCreateSerializer, request)
    course = get_managed_course(request.user, data["course_name"])

    if course.is_archived:
        return Response(
            {"detail": f"{course.name} is archived. Restore it to add sessions."},
            status=status.HTTP_409_CONFLICT,
        )

    repeat = data.get("repeat", Repeat.NONE)
    dates = expand(
        start=data["date"],
        repeat=repeat,
        interval=data.get("repeat_interval", 1),
        count=data.get("repeat_count", 1),
        until=data.get("repeat_until"),
    )
    series_id = uuid4() if repeat != Repeat.NONE else None

    created, skipped = [], []
    for occurrence in dates:
        try:
            with transaction.atomic():
                created.append(
                    Session.objects.create(
                        course=course,
                        date=occurrence,
                        start_time=data["start_time"],
                        end_time=data["end_time"],
                        lat=data["lat"],
                        lon=data["lon"],
                        radius_m=data["radius_m"],
                        repeat=repeat,
                        series_id=series_id,
                    )
                )
        except IntegrityError:
            skipped.append(occurrence)

    if not created:
        return Response(
            {"detail": "A session already exists for every date in that series."},
            status=status.HTTP_409_CONFLICT,
        )

    payload = {
        "created": SessionSerializer(created, many=True).data,
        "skipped": [str(d) for d in skipped],
        "series_id": str(series_id) if series_id else None,
    }
    if skipped:
        payload["detail"] = (
            f"Created {len(created)} session(s); {len(skipped)} already existed."
        )
    return Response(payload, status=status.HTTP_201_CREATED)


@api_view(["POST"])
@permission_classes([IsTeacherOrAdmin])
def delete_session_series(request):
    """Remove every remaining occurrence that shares a series_id."""
    series_id = request.data.get("series_id")
    if not series_id:
        return Response(
            {"detail": "series_id is required."}, status=status.HTTP_400_BAD_REQUEST
        )

    sessions = Session.objects.filter(series_id=series_id).select_related("course")
    first = sessions.first()
    if first is None:
        return Response({"detail": "No such series."}, status=status.HTTP_404_NOT_FOUND)

    # Ownership is checked on the course the series belongs to.
    get_managed_course(request.user, first.course.name)
    deleted = sessions.count()
    sessions.delete()
    return Response({"detail": f"Deleted {deleted} session(s)."})


@api_view(["POST"])
@permission_classes([IsAuthenticated])
def show_sessions(request):
    """Every session of a course, annotated with the caller's own presence."""
    data = _validated(CourseNameSerializer, request)
    course = get_visible_course(request.user, data["course_name"])
    sessions = course.sessions.select_related("course")
    context = {"present_session_ids": _present_session_ids(request.user, course)}
    return Response(SessionSerializer(sessions, many=True, context=context).data)


@api_view(["POST"])
@permission_classes([IsAuthenticated])
def show_active_sessions(request):
    """Sessions running right now that the caller has not yet marked."""
    data = _validated(CourseNameSerializer, request)
    course = get_visible_course(request.user, data["course_name"])

    now = timezone.localtime()
    open_sessions = [
        s
        for s in course.sessions.select_related("course").filter(date=now.date())
        if s.is_open(now)
    ]
    already_marked = _present_session_ids(request.user, course)
    pending = [s for s in open_sessions if s.id not in already_marked]
    return Response(SessionSerializer(pending, many=True).data)


@api_view(["POST"])
@permission_classes([IsTeacherOrAdmin])
def show_students_in_session(request):
    data = _validated(SessionSlotSerializer, request)
    course = get_managed_course(request.user, data["course_name"])
    session = get_session(
        course, data["date"], data["start_time"], data["end_time"]
    )
    records = session.attendance_records.select_related("student")
    return Response(AttendanceRecordSerializer(records, many=True).data)


@api_view(["POST"])
@permission_classes([IsTeacherOrAdmin])
def delete_session(request):
    data = _validated(SessionSlotSerializer, request)
    course = get_managed_course(request.user, data["course_name"])
    session = get_session(
        course, data["date"], data["start_time"], data["end_time"]
    )
    session.delete()
    return Response(status=status.HTTP_204_NO_CONTENT)


# --- Attendance ------------------------------------------------------------


def _check_device_integrity(user, data):
    """Verify the Play Integrity token. Returns a Response on failure, else None."""
    token = (data.get("integrity_token") or "").strip()
    nonce = (data.get("integrity_nonce") or "").strip()

    if not token or not nonce:
        return Response(
            {
                "detail": "This server requires a device integrity token. Request "
                "a challenge from Home/attendance/challenge first."
            },
            status=status.HTTP_428_PRECONDITION_REQUIRED,
        )

    # Burn the nonce before verifying: single-use means single-use whatever the
    # verdict turns out to be, otherwise a failed attempt leaves it replayable.
    if not consume_nonce(user, nonce):
        return Response(
            {"detail": "Integrity challenge is unknown, expired, or already used."},
            status=status.HTTP_403_FORBIDDEN,
        )

    try:
        verdict = get_integrity_verifier().verify(token, nonce)
    except DeviceIntegrityUnavailable as exc:
        logger.error("Device integrity check unavailable: %s", exc)
        return Response(
            {"detail": str(exc)}, status=status.HTTP_503_SERVICE_UNAVAILABLE
        )

    if not verdict.ok:
        logger.warning(
            "Device integrity failed for %s: %s", user.username, verdict.reason
        )
        return Response({"detail": verdict.reason}, status=status.HTTP_403_FORBIDDEN)
    return None


@api_view(["POST"])
@permission_classes([IsStudent])
def attendance_challenge(request):
    """Issue a single-use nonce to bind a Play Integrity token to this attempt.

    Always available, so the client can follow one flow regardless of how the
    server is configured; `integrity_required` tells the app whether it actually
    needs to go and fetch a token.
    """
    return Response(
        {
            "nonce": issue_nonce(request.user),
            "expires_in": nonce_ttl_seconds(),
            "integrity_required": integrity_required(),
        }
    )


@api_view(["POST"])
@permission_classes([IsStudent])
@throttle_classes([ScopedRateThrottle])
@parser_classes([MultiPartParser, FormParser, JSONParser])
def mark_attendance(request):
    """A student marks themselves present.

    Every check that makes this trustworthy happens here, server-side, in the
    request that writes the row:

      1. the caller is the student being marked  (identity from the JWT)
      2. they are enrolled in the course
      3. the session is open right now
      4. the position is not mocked, is precise enough, and is inside the
         session's geofence
      5. the device passes Play Integrity  (when the server requires it)
      6. the captured frame matches their enrolled face

    Previously 4 and 6 ran in the browser and their result was passed to the
    server as a claim, which made both trivially skippable.

    Accepts multipart/form-data — send the frame as a JPEG file part rather than
    a base64 data URL, which costs a third more bytes on a mobile connection.
    """
    data = _validated(MarkAttendanceSerializer, request)

    course = get_course(data["course_name"])
    if not course.enrollments.filter(student=request.user).exists():
        return Response(
            {"detail": "You are not enrolled in this course."},
            status=status.HTTP_403_FORBIDDEN,
        )

    session = get_session(course, data["date"], data["start_time"], data["end_time"])
    if not session.is_open():
        return Response(
            {"detail": "That session is not open for attendance right now."},
            status=status.HTTP_409_CONFLICT,
        )

    if AttendanceRecord.objects.filter(session=session, student=request.user).exists():
        return Response(
            {"detail": "Your attendance for this session is already marked."},
            status=status.HTTP_409_CONFLICT,
        )

    # 4a. Platform location signals. Free to check, and they decide whether the
    # coordinates below are worth believing at all.
    if settings.REJECT_MOCK_LOCATION and data.get("is_mock_location"):
        logger.warning(
            "Attendance refused for %s: mock location provider reported",
            request.user.username,
        )
        return Response(
            {"detail": "Attendance cannot be marked from a mocked location."},
            status=status.HTTP_403_FORBIDDEN,
        )

    accuracy_m = data.get("location_accuracy_m")
    if accuracy_m is not None and accuracy_m > settings.MAX_LOCATION_ACCURACY_M:
        return Response(
            {
                "detail": "Your location fix is too imprecise. Move outdoors or "
                "enable high-accuracy location and try again.",
                "accuracy_m": round(accuracy_m, 1),
                "required_accuracy_m": settings.MAX_LOCATION_ACCURACY_M,
            },
            status=status.HTTP_403_FORBIDDEN,
        )

    # 4b. Geofence — cheap, so check it before loading the face model.
    distance_m = haversine_metres(
        data["lat"], data["lon"], session.lat, session.lon
    )
    if distance_m > session.radius_m:
        logger.info(
            "Attendance refused for %s: %.0fm away (limit %.0fm)",
            request.user.username,
            distance_m,
            session.radius_m,
        )
        return Response(
            {
                "detail": "You are not within the session's location to mark attendance.",
                "distance_m": round(distance_m, 1),
                "allowed_radius_m": session.radius_m,
            },
            status=status.HTTP_403_FORBIDDEN,
        )

    # 5. Device attestation, if this deployment requires it. Runs before the
    # face model because it is the cheaper of the two remaining checks, and
    # because it is what makes the mock-location flag above trustworthy.
    if integrity_required():
        verdict_response = _check_device_integrity(request.user, data)
        if verdict_response is not None:
            return verdict_response

    # 6. Face match against the stored enrolment.
    try:
        enrollment = request.user.face_enrollment
    except FaceEnrollment.DoesNotExist:
        return Response(
            {"detail": "Enrol your face before marking attendance."},
            status=status.HTTP_428_PRECONDITION_REQUIRED,
        )

    backend = get_face_backend()
    try:
        image = decode_image(data["image"])
        candidate = backend.embed(image)
    except FaceRecognitionError as exc:
        # Covers an unusable image, no face, several faces, and a backend that
        # is switched off — the last of which must surface as 503 rather than
        # being mistaken for a stale enrolment below.
        return face_error_response(exc)

    if enrollment.model_name != backend.name:
        return Response(
            {
                "detail": "Your enrolled face was captured with a different model. "
                "Please enrol your face again."
            },
            status=status.HTTP_428_PRECONDITION_REQUIRED,
        )

    matched, distance = backend.compare(enrollment.embedding, candidate)

    if not matched:
        logger.warning(
            "Face mismatch for %s (distance %.3f, threshold %.3f)",
            request.user.username,
            distance,
            backend.threshold,
        )
        return Response(
            {"detail": "Face did not match your enrolled photo."},
            status=status.HTTP_403_FORBIDDEN,
        )

    try:
        with transaction.atomic():
            record = AttendanceRecord.objects.create(
                session=session,
                student=request.user,
                method=AttendanceRecord.Method.FACE,
                lat=data["lat"],
                lon=data["lon"],
                distance_m=round(distance_m, 1),
            )
    except IntegrityError:
        # Two concurrent submissions; the unique constraint settles it.
        return Response(
            {"detail": "Your attendance for this session is already marked."},
            status=status.HTTP_409_CONFLICT,
        )

    return Response(
        {
            "detail": "Attendance marked successfully.",
            "record": AttendanceRecordSerializer(record).data,
        },
        status=status.HTTP_201_CREATED,
    )


mark_attendance.throttle_scope = "face"


@api_view(["POST"])
@permission_classes([IsTeacherOrAdmin])
def mark_attendance_manual(request):
    """Teacher override, for when the camera or the WiFi lets a student down.

    No face or geofence check — that is the point of an override — but it is
    restricted to the course owner, recorded as `manual`, and attributed to the
    teacher who did it, so corrections stay auditable.
    """
    data = _validated(ManualAttendanceSerializer, request)
    course = get_managed_course(request.user, data["course_name"])
    session = get_session(course, data["date"], data["start_time"], data["end_time"])

    student = User.objects.get(username=data["student_username"], role=Role.STUDENT)
    if not course.enrollments.filter(student=student).exists():
        return Response(
            {"detail": f"{student.username} is not enrolled in this course."},
            status=status.HTTP_400_BAD_REQUEST,
        )

    with transaction.atomic():
        record, created = AttendanceRecord.objects.get_or_create(
            session=session,
            student=student,
            defaults={
                "method": AttendanceRecord.Method.MANUAL,
                "marked_by": request.user,
            },
        )
    if not created:
        return Response(
            {"detail": f"{student.username} is already marked present."},
            status=status.HTTP_409_CONFLICT,
        )

    logger.info(
        "%s manually marked %s present for %s",
        request.user.username,
        student.username,
        session,
    )
    return Response(
        AttendanceRecordSerializer(record).data, status=status.HTTP_201_CREATED
    )


@api_view(["DELETE", "POST"])
@permission_classes([IsTeacherOrAdmin])
def unmark_attendance(request):
    """Remove an attendance row — the "teachers can edit the list" feature."""
    data = _validated(ManualAttendanceSerializer, request)
    course = get_managed_course(request.user, data["course_name"])
    session = get_session(course, data["date"], data["start_time"], data["end_time"])

    deleted, _ = AttendanceRecord.objects.filter(
        session=session, student__username=data["student_username"]
    ).delete()
    if not deleted:
        return Response(
            {"detail": "That student is not marked present for this session."},
            status=status.HTTP_404_NOT_FOUND,
        )
    logger.info(
        "%s removed attendance for %s on %s",
        request.user.username,
        data["student_username"],
        session,
    )
    return Response(status=status.HTTP_204_NO_CONTENT)


# --- Today -----------------------------------------------------------------


@api_view(["GET", "POST"])
@permission_classes([IsAuthenticated])
def today(request):
    """Everything happening today, across every course the caller is part of.

    Ordered by start time, with the live one first, so a client can render a
    single chronological agenda instead of making the user open each course.
    """
    now = timezone.localtime()
    user = request.user

    if user.is_teacher or user.is_admin:
        courses = Course.objects.filter(archived_at__isnull=True)
        if not user.is_admin:
            courses = courses.filter(teacher=user)
    else:
        courses = Course.objects.filter(
            enrollments__student=user, archived_at__isnull=True
        )

    sessions = list(
        Session.objects.filter(course__in=courses, date=now.date())
        .select_related("course", "course__teacher")
        .annotate(present_count=Count("attendance_records", distinct=True))
        .order_by("start_time", "end_time")
    )

    # Only a student has attendance of their own. Passing an empty set for a
    # teacher would render every one of their own classes as "absent".
    is_staff = user.is_teacher or user.is_admin
    context = {}
    if not is_staff:
        context["present_session_ids"] = set(
            AttendanceRecord.objects.filter(
                student=user, session__in=sessions
            ).values_list("session_id", flat=True)
        )

    serialized = SessionSerializer(sessions, many=True, context=context).data

    # Live first, then upcoming, then finished — each group already in time
    # order from the query above.
    def bucket(item, session):
        if session.is_open(now):
            return 0
        return 1 if session.start_time > now.time() else 2

    ordered = [
        payload
        for _, payload in sorted(
            (
                (bucket(payload, session), payload)
                for payload, session in zip(serialized, sessions)
            ),
            key=lambda pair: pair[0],
        )
    ]

    return Response(
        {
            "date": str(now.date()),
            "now": now.strftime("%H:%M:%S"),
            "open_count": sum(1 for s in sessions if s.is_open(now)),
            "sessions": ordered,
        }
    )


# --- Archiving -------------------------------------------------------------


@api_view(["POST"])
@permission_classes([IsTeacherOrAdmin])
def archive_course(request):
    """Archive or restore a course. Nothing is deleted either way."""
    data = _validated(ArchiveCourseSerializer, request)
    course = get_managed_course(request.user, data["course_name"])

    if data["archived"]:
        course.archive()
        detail = f"{course.name} archived."
    else:
        course.unarchive()
        detail = f"{course.name} restored."

    logger.info("%s: %s", request.user.username, detail)
    return Response({"detail": detail, "course": CourseSerializer(course).data})


# --- Teacher dashboard ------------------------------------------------------


@api_view(["POST"])
@permission_classes([IsTeacherOrAdmin])
def course_student_stats(request):
    """Per-student attendance for one course.

    One query for sessions and one for records, then aggregated in Python —
    rosters are class-sized, and this keeps the shape obvious.
    """
    data = _validated(CourseNameSerializer, request)
    course = get_managed_course(request.user, data["course_name"])

    session_ids = list(course.sessions.values_list("id", flat=True))
    total = len(session_ids)

    records = AttendanceRecord.objects.filter(
        session_id__in=session_ids
    ).select_related("student", "session")

    tally = {}
    for record in records:
        entry = tally.setdefault(
            record.student_id, {"attended": 0, "manual": 0, "last_seen": None}
        )
        entry["attended"] += 1
        if record.method == AttendanceRecord.Method.MANUAL:
            entry["manual"] += 1
        seen = record.session.date
        if entry["last_seen"] is None or seen > entry["last_seen"]:
            entry["last_seen"] = seen

    rows = []
    students = User.objects.filter(enrollments__course=course).order_by("username")
    for student in students:
        entry = tally.get(student.pk, {"attended": 0, "manual": 0, "last_seen": None})
        rows.append(
            {
                "username": student.username,
                "name": student.name,
                "email": student.email,
                "attended": entry["attended"],
                "total_sessions": total,
                "attendance_pct": (
                    round(100 * entry["attended"] / total, 1) if total else 0.0
                ),
                "manual_count": entry["manual"],
                "last_seen": entry["last_seen"],
                "face_enrolled": student.face_enrolled,
            }
        )

    # Lowest attendance first: the students a teacher needs to notice.
    rows.sort(key=lambda row: (row["attendance_pct"], row["username"]))

    return Response(
        {
            "course_name": course.name,
            "total_sessions": total,
            "students": StudentAttendanceSerializer(rows, many=True).data,
        }
    )


# --- Public ----------------------------------------------------------------


@api_view(["POST"])
@permission_classes([AllowAny])
def username_availability(request):
    data = _validated(UsernameAvailabilitySerializer, request)
    taken = User.objects.filter(username__iexact=data["username"]).exists()
    return Response({"available": not taken})
