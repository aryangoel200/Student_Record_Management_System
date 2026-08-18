import secrets

from django.conf import settings
from django.core.validators import MaxValueValidator, MinValueValidator
from django.db import IntegrityError, models, transaction
from django.utils import timezone

# Unambiguous alphabet: no O/0, I/1, or lookalikes, because students read these
# codes off a projector and type them in.
CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
CODE_LENGTH = 8
_CODE_ATTEMPTS = 10


def generate_verification_code():
    """A cryptographically random, collision-checked course code.

    The old implementation was ``chr(random.randint(50, 100))`` five times —
    predictable, and with no uniqueness check, so a collision silently enrolled
    students into whichever course matched first.
    """
    for _ in range(_CODE_ATTEMPTS):
        code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(CODE_LENGTH))
        if not Course.objects.filter(verification_code=code).exists():
            return code
    raise IntegrityError("Could not generate a unique course verification code.")


class Course(models.Model):
    name = models.CharField(max_length=100, unique=True)
    verification_code = models.CharField(max_length=16, unique=True, db_index=True)
    teacher = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="courses_taught",
    )
    students = models.ManyToManyField(
        settings.AUTH_USER_MODEL,
        through="Enrollment",
        related_name="courses_enrolled",
        blank=True,
    )
    created_at = models.DateTimeField(auto_now_add=True)
    archived_at = models.DateTimeField(
        null=True,
        blank=True,
        db_index=True,
        help_text="Set when the course is archived. Archiving hides it from the "
        "active lists and blocks new sessions; nothing is deleted, so past "
        "attendance stays intact and auditable.",
    )

    class Meta:
        ordering = ["name"]

    def __str__(self):
        return self.name

    @property
    def is_archived(self):
        return self.archived_at is not None

    def archive(self):
        if self.archived_at is None:
            self.archived_at = timezone.now()
            self.save(update_fields=["archived_at"])

    def unarchive(self):
        if self.archived_at is not None:
            self.archived_at = None
            self.save(update_fields=["archived_at"])

    @transaction.atomic
    def rotate_verification_code(self):
        self.verification_code = generate_verification_code()
        self.save(update_fields=["verification_code"])
        return self.verification_code


class Enrollment(models.Model):
    course = models.ForeignKey(
        Course, on_delete=models.CASCADE, related_name="enrollments"
    )
    student = models.ForeignKey(
        settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="enrollments"
    )
    enrolled_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        constraints = [
            models.UniqueConstraint(
                fields=["course", "student"], name="unique_enrollment_per_course"
            )
        ]
        ordering = ["student__username"]

    def __str__(self):
        return f"{self.student.username} in {self.course.name}"


class Repeat(models.TextChoices):
    """How a scheduled session repeats.

    Occurrences are written out as individual Session rows rather than being
    derived from this rule at read time, so every existing query, the geofence
    and per-occurrence deletion keep working untouched. The rule is recorded
    only so a series can be described back to the teacher.
    """

    NONE = "none", "Does not repeat"
    DAILY = "daily", "Daily"
    WEEKLY = "weekly", "Weekly"
    MONTHLY = "monthly", "Monthly"


class Session(models.Model):
    """A single class meeting students can mark attendance against."""

    course = models.ForeignKey(
        Course, on_delete=models.CASCADE, related_name="sessions"
    )
    date = models.DateField()
    start_time = models.TimeField()
    end_time = models.TimeField()
    lat = models.FloatField(
        validators=[MinValueValidator(-90.0), MaxValueValidator(90.0)]
    )
    lon = models.FloatField(
        validators=[MinValueValidator(-180.0), MaxValueValidator(180.0)]
    )
    radius_m = models.FloatField(
        default=100.0,
        validators=[MinValueValidator(1.0)],
        help_text="Geofence radius in metres.",
    )
    created_at = models.DateTimeField(auto_now_add=True)

    # Occurrences created together share this, so a whole series can be listed
    # or removed without guessing which rows belonged to it.
    series_id = models.UUIDField(null=True, blank=True, db_index=True)
    repeat = models.CharField(
        max_length=16, choices=Repeat.choices, default=Repeat.NONE
    )

    class Meta:
        constraints = [
            models.UniqueConstraint(
                fields=["course", "date", "start_time", "end_time"],
                name="unique_session_per_course_slot",
            ),
            models.CheckConstraint(
                check=models.Q(end_time__gt=models.F("start_time")),
                name="session_ends_after_it_starts",
            ),
        ]
        ordering = ["-date", "-start_time"]

    def __str__(self):
        return f"{self.course.name} {self.date} {self.start_time}-{self.end_time}"

    def is_open(self, at=None):
        """Is this session currently accepting attendance?"""
        at = at or timezone.localtime()
        return self.date == at.date() and self.start_time <= at.time() <= self.end_time


class AttendanceRecord(models.Model):
    class Method(models.TextChoices):
        FACE = "face", "Face + geofence"
        MANUAL = "manual", "Manual (teacher)"

    session = models.ForeignKey(
        Session, on_delete=models.CASCADE, related_name="attendance_records"
    )
    student = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="attendance_records",
    )
    marked_at = models.DateTimeField(auto_now_add=True)
    method = models.CharField(
        max_length=16, choices=Method.choices, default=Method.FACE
    )
    marked_by = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="attendance_marked_for_others",
        help_text="Set when a teacher marked this on the student's behalf.",
    )
    # Where the student actually was, for after-the-fact auditing.
    lat = models.FloatField(null=True, blank=True)
    lon = models.FloatField(null=True, blank=True)
    distance_m = models.FloatField(null=True, blank=True)

    class Meta:
        constraints = [
            models.UniqueConstraint(
                fields=["session", "student"], name="unique_attendance_per_session"
            )
        ]
        ordering = ["-marked_at"]

    def __str__(self):
        return f"{self.student.username} @ {self.session}"
