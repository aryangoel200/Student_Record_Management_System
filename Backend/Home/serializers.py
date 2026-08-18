from django.conf import settings
from rest_framework import serializers

from Auth.models import Role, User

from .models import AttendanceRecord, Course, Session

# --- Output ----------------------------------------------------------------


class CourseSerializer(serializers.ModelSerializer):
    teacher = serializers.CharField(source="teacher.username", read_only=True)
    teacher_name = serializers.CharField(source="teacher.name", read_only=True)
    enrolled_count = serializers.IntegerField(read_only=True)

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
        ]


class EnrolledCourseSerializer(CourseSerializer):
    """What a student sees: the code is the teacher's to share, not ours."""

    class Meta(CourseSerializer.Meta):
        fields = ["id", "name", "teacher", "teacher_name", "created_at"]


class SessionSerializer(serializers.ModelSerializer):
    course_name = serializers.CharField(source="course.name", read_only=True)
    # Annotated per-request for the student viewing their own record.
    presence = serializers.SerializerMethodField()

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
        ]

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

    def validate_radius_m(self, value):
        return value

    def validate(self, attrs):
        attrs = super().validate(attrs)
        attrs.setdefault("radius_m", settings.DEFAULT_SESSION_RADIUS_M)
        return attrs


class CourseRegistrationSerializer(serializers.Serializer):
    verification_code_entered = serializers.CharField(max_length=16, trim_whitespace=True)

    def validate_verification_code_entered(self, value):
        return value.strip().upper()


class MarkAttendanceSerializer(SessionSlotSerializer):
    """A student marking themselves present.

    The image and the student's real coordinates are both required and both
    checked server-side. The client is not trusted to have done either.
    """

    lat = serializers.FloatField(min_value=-90.0, max_value=90.0)
    lon = serializers.FloatField(min_value=-180.0, max_value=180.0)
    image = serializers.CharField(
        help_text="data: URL of a captured frame, e.g. data:image/png;base64,..."
    )


class ManualAttendanceSerializer(SessionSlotSerializer):
    """A teacher correcting the register — no face or geofence check."""

    student_username = serializers.CharField(max_length=32)

    def validate_student_username(self, value):
        if not User.objects.filter(username=value, role=Role.STUDENT).exists():
            raise serializers.ValidationError("No student with that username.")
        return value


class UsernameAvailabilitySerializer(serializers.Serializer):
    username = serializers.CharField(max_length=32)
