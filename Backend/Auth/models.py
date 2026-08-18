from django.contrib.auth.models import AbstractBaseUser, BaseUserManager
from django.core.validators import RegexValidator
from django.db import models


class Role(models.TextChoices):
    """The three roles the system authorises against.

    STUDENT  enrols in courses and marks their own attendance.
    TEACHER  owns courses, runs sessions, and can correct attendance.
    ADMIN    everything a teacher can do on any course, plus user management.
    """

    STUDENT = "student", "Student"
    TEACHER = "teacher", "Teacher"
    ADMIN = "admin", "Admin"


username_validator = RegexValidator(
    r"^[A-Za-z0-9._-]+$",
    "Username may only contain letters, digits, and the characters . _ -",
)


class UserManager(BaseUserManager):
    def create_user(self, username, name, email, password=None, role=Role.STUDENT):
        if not username:
            raise ValueError("Users must have a username.")
        if not email:
            raise ValueError("Users must have an email address.")

        user = self.model(
            username=username,
            email=self.normalize_email(email),
            name=name,
            role=role,
        )
        user.set_password(password)
        user.full_clean(exclude=["password"])
        user.save(using=self._db)
        return user

    def create_superuser(self, username, name, email, password=None):
        return self.create_user(
            username=username,
            name=name,
            email=email,
            password=password,
            role=Role.ADMIN,
        )

    def get_by_natural_key(self, username):
        return self.get(username=username)


class User(AbstractBaseUser):
    """The single account record.

    This replaces the old split between ``Auth.User`` and ``Home.person_table``,
    which were two rows for one person kept in sync by two separate frontend
    calls — if the second failed you got an account with no profile.
    """

    username = models.CharField(
        verbose_name="Username",
        max_length=32,
        unique=True,
        validators=[username_validator],
        help_text="Institute roll number for students.",
    )
    name = models.CharField(max_length=255)
    email = models.EmailField(max_length=255, unique=True)
    role = models.CharField(
        max_length=16,
        choices=Role.choices,
        default=Role.STUDENT,
        db_index=True,
    )
    is_active = models.BooleanField(default=True)
    date_joined = models.DateTimeField(auto_now_add=True)

    objects = UserManager()

    USERNAME_FIELD = "username"
    REQUIRED_FIELDS = ["name", "email"]

    class Meta:
        ordering = ["username"]

    def __str__(self):
        return f"{self.username} ({self.get_role_display()})"

    @property
    def is_student(self):
        return self.role == Role.STUDENT

    @property
    def is_teacher(self):
        return self.role == Role.TEACHER

    @property
    def is_admin(self):
        return self.role == Role.ADMIN

    @property
    def is_staff(self):
        """Controls access to the Django admin site."""
        return self.role == Role.ADMIN

    @property
    def face_enrolled(self):
        """Whether this user has a stored face embedding.

        Kept separate from ``role``. The old code overloaded the ``role``
        boolean to mean "has registered a face", which is why finishing face
        enrolment used to promote a student to teacher.
        """
        return hasattr(self, "face_enrollment")

    def has_perm(self, perm, obj=None):
        return self.is_admin

    def has_module_perms(self, app_label):
        return self.is_admin
