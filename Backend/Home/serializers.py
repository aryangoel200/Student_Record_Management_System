from django.conf import settings
from rest_framework import serializers

from Auth.models import Role, User
from Face_Recognation.fields import FaceImageField

from .models import AttendanceRecord, Course, Repeat, Session

# --- Output ----------------------------------------------------------------


class CourseSerializer(serializers.ModelSerializer):
    teacher = serializers.CharField(source="teacher.username", read_only=True)
    teacher_name = serializers.CharField(source="teacher.name", read_only=True)
    enrolled_count = serializers.IntegerField(read_only=True)
    is_archived = serializers.BooleanField(read_only=True)

    class Meta:
        model = Course
        fields = [
            "id",
            "name",
            "verification_code",
            "teacher",
            "teacher_name",
            "enrolled_count",
            "created_at",
            "is_archived",
        ]


class EnrolledCourseSerializer(CourseSerializer):
    """What a student sees: the code is the teacher's to share, not ours."""

    class Meta(CourseSerializer.Meta):
        fields = ["id", "name", "teacher", "teacher_name", "created_at", "is_archived"]


class SessionSerializer(serializers.ModelSerializer):
    course_name = serializers.CharField(source="course.name", read_only=True)
    # Annotated per-request for the student viewing their own record.
    presence = serializers.SerializerMethodField()
    # Lets the client show one session as live without re-deriving it from the
    # clock, which would disagree with the server across time zones.
    is_open = serializers.SerializerMethodField()
    present_count = serializers.SerializerMethodField()

    class Meta:
        model = Session
        fields = [
            "id",
            "course_name",
            "date",
            "start_time",
            "end_time",
            "lat",
            "lon",
            "radius_m",
            "presence",
            "is_open",
            "present_count",
            "repeat",
            "series_id",
        ]

    def get_is_open(self, obj):
        return obj.is_open()

    def get_present_count(self, obj):
        # Only annotated where a teacher asked for it, to avoid an N+1.
        return getattr(obj, "present_count", None)

    def get_presence(self, obj):
        present_ids = self.context.get("present_session_ids")
        if present_ids is None:
            return None
        return "present" if obj.id in present_ids else "absent"


class StudentSerializer(serializers.ModelSerializer):
    class Meta:
        model = User
        fields = ["username", "name", "email"]


class AttendanceRecordSerializer(serializers.ModelSerializer):
    username = serializers.CharField(source="student.username", read_only=True)
    name = serializers.CharField(source="student.name", read_only=True)
    email = serializers.EmailField(source="student.email", read_only=True)

    class Meta:
        model = AttendanceRecord
        fields = [
            "username",
            "name",
            "email",
            "marked_at",
            "method",
            "distance_m",
        ]


# --- Input -----------------------------------------------------------------


class CourseCreateSerializer(serializers.Serializer):
    name = serializers.CharField(max_length=100, trim_whitespace=True)

    def validate_name(self, value):
        if Course.objects.filter(name__iexact=value).exists():
            raise serializers.ValidationError("Course name already taken.")
        return value


class CourseNameSerializer(serializers.Serializer):
    course_name = serializers.CharField(max_length=100)


class SessionSlotSerializer(CourseNameSerializer):
    """Identifies one session. Times are parsed, so "14:30" == "14:30:00"."""

    date = serializers.DateField()
    start_time = serializers.TimeField()
    end_time = serializers.TimeField()

    def validate(self, attrs):
        if attrs["end_time"] <= attrs["start_time"]:
            raise serializers.ValidationError(
                {"end_time": "End time must be after start time."}
            )
        return attrs


class SessionCreateSerializer(SessionSlotSerializer):
    lat = serializers.FloatField(min_value=-90.0, max_value=90.0)
    lon = serializers.FloatField(min_value=-180.0, max_value=180.0)
    radius_m = serializers.FloatField(min_value=1.0, required=False)

    # --- Optional repeat rule ---
    repeat = serializers.ChoiceField(
        choices=Repeat.choices, required=False, default=Repeat.NONE
    )
    repeat_interval = serializers.IntegerField(
        min_value=1, max_value=12, required=False, default=1,
        help_text="Every N days/weeks/months. 2 with weekly means fortnightly.",
    )
    repeat_count = serializers.IntegerField(
        min_value=1, max_value=60, required=False,
        help_text="How many occurrences in total, including the first.",
    )
    repeat_until = serializers.DateField(
        required=False, help_text="Stop on or before this date."
    )

    def validate(self, attrs):
        attrs = super().validate(attrs)
        attrs.setdefault("radius_m", settings.DEFAULT_SESSION_RADIUS_M)

        if attrs.get("repeat", Repeat.NONE) != Repeat.NONE:
            if not attrs.get("repeat_count") and not attrs.get("repeat_until"):
                raise serializers.ValidationError(
                    {"repeat_count": "Give repeat_count or repeat_until for a "
                                     "repeating session."}
                )
            until = attrs.get("repeat_until")
            if until and until < attrs["date"]:
                raise serializers.ValidationError(
                    {"repeat_until": "repeat_until cannot be before the first date."}
                )
        return attrs


class CourseRegistrationSerializer(serializers.Serializer):
    verification_code_entered = serializers.CharField(max_length=16, trim_whitespace=True)

    def validate_verification_code_entered(self, value):
        return value.strip().upper()


class MarkAttendanceSerializer(SessionSlotSerializer):
    """A student marking themselves present.

    The image and the student's real coordinates are both required and both
    checked server-side. The client is not trusted to have done either.

    Accepts `multipart/form-data` (what the Android client should send) as well
    as JSON.
    """

    lat = serializers.FloatField(min_value=-90.0, max_value=90.0)
    lon = serializers.FloatField(min_value=-180.0, max_value=180.0)
    image = FaceImageField()

    # --- Signals the Android client forwards from the platform ---
    location_accuracy_m = serializers.FloatField(
        required=False,
        min_value=0.0,
        help_text="Location.getAccuracy() — radius of 68% confidence, in metres.",
    )
    is_mock_location = serializers.BooleanField(
        required=False,
        default=False,
        help_text="Location.isMock (API 31+) or isFromMockProvider.",
    )
    integrity_token = serializers.CharField(
        required=False,
        allow_blank=True,
        help_text="Play Integrity token, required when DEVICE_INTEGRITY_REQUIRED is on.",
    )
    integrity_nonce = serializers.CharField(
        required=False,
        allow_blank=True,
        help_text="The nonce from POST Home/attendance/challenge.",
    )


class ManualAttendanceSerializer(SessionSlotSerializer):
    """A teacher correcting the register — no face or geofence check."""

    student_username = serializers.CharField(max_length=32)

    def validate_student_username(self, value):
        if not User.objects.filter(username=value, role=Role.STUDENT).exists():
            raise serializers.ValidationError("No student with that username.")
        return value


class StudentAttendanceSerializer(serializers.Serializer):
    """One row of a teacher's per-student dashboard."""

    username = serializers.CharField()
    name = serializers.CharField()
    email = serializers.EmailField()
    attended = serializers.IntegerField()
    total_sessions = serializers.IntegerField()
    attendance_pct = serializers.FloatField()
    manual_count = serializers.IntegerField()
    last_seen = serializers.DateField(allow_null=True)
    face_enrolled = serializers.BooleanField()


class ArchiveCourseSerializer(CourseNameSerializer):
    archived = serializers.BooleanField(
        help_text="true archives the course, false restores it."
    )


class UsernameAvailabilitySerializer(serializers.Serializer):
    username = serializers.CharField(max_length=32)
