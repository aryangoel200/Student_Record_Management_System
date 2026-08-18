from django.core.cache import cache
from django.test import TestCase
from rest_framework import status
from rest_framework.test import APIClient

from .models import Role, User


class AuthTestCase(TestCase):
    def setUp(self):
        # Throttle state lives in the default cache; a dirty cache makes later
        # tests fail with 429 for no reason.
        cache.clear()
        self.client = APIClient()

    def authenticate(self, user):
        self.client.force_authenticate(user=user)
        return user


class RegistrationTests(AuthTestCase):
    payload = {
        "username": "2021BCS001",
        "name": "Asha Rao",
        "email": "asha@example.edu",
        "password": "correct-horse-9",
        "password2": "correct-horse-9",
    }

    def test_registration_creates_a_student_and_returns_tokens(self):
        response = self.client.post("/api/Auth/register", self.payload, format="json")

        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        self.assertEqual(response.data["user"]["role"], Role.STUDENT)
        self.assertIn("access", response.data["token"])
        self.assertIn("refresh", response.data["token"])
        self.assertFalse(response.data["user"]["face_enrolled"])

    def test_client_cannot_choose_its_own_role(self):
        """Signup used to take `role` straight from the form."""
        response = self.client.post(
            "/api/Auth/register", {**self.payload, "role": Role.ADMIN}, format="json"
        )

        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        self.assertEqual(User.objects.get(username="2021BCS001").role, Role.STUDENT)

    def test_password_must_be_confirmed(self):
        response = self.client.post(
            "/api/Auth/register",
            {**self.payload, "password2": "something-else"},
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertFalse(User.objects.filter(username="2021BCS001").exists())

    def test_weak_password_is_rejected(self):
        response = self.client.post(
            "/api/Auth/register",
            {**self.payload, "password": "12345678", "password2": "12345678"},
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)

    def test_duplicate_username_is_rejected(self):
        self.client.post("/api/Auth/register", self.payload, format="json")
        response = self.client.post(
            "/api/Auth/register",
            {**self.payload, "email": "other@example.edu"},
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)


class LoginTests(AuthTestCase):
    def setUp(self):
        super().setUp()
        self.user = User.objects.create_user(
            username="teach1", name="Dr Sinha", email="t@example.edu",
            password="a-good-password-1", role=Role.TEACHER,
        )

    def test_login_succeeds_with_correct_credentials(self):
        response = self.client.post(
            "/api/Auth/login",
            {"username": "teach1", "password": "a-good-password-1"},
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.data["user"]["role"], Role.TEACHER)

    def test_wrong_password_returns_401_not_200(self):
        """The old endpoint returned 404 with a body the client had to parse."""
        response = self.client.post(
            "/api/Auth/login",
            {"username": "teach1", "password": "wrong"},
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)
        self.assertIn("detail", response.data)

    def test_inactive_user_cannot_log_in(self):
        self.user.is_active = False
        self.user.save(update_fields=["is_active"])
        response = self.client.post(
            "/api/Auth/login",
            {"username": "teach1", "password": "a-good-password-1"},
            format="json",
        )
        self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)


class ProfileTests(AuthTestCase):
    def setUp(self):
        super().setUp()
        self.student = User.objects.create_user(
            username="stu1", name="Ravi", email="r@example.edu",
            password="a-good-password-1",
        )

    def test_profile_requires_authentication(self):
        self.assertEqual(
            self.client.get("/api/Auth/profile").status_code,
            status.HTTP_401_UNAUTHORIZED,
        )

    def test_profile_returns_role_and_face_status(self):
        self.authenticate(self.student)
        response = self.client.get("/api/Auth/profile")
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.data["role"], Role.STUDENT)
        self.assertFalse(response.data["face_enrolled"])

    def test_role_cannot_be_changed_through_the_profile_endpoint(self):
        """This is exactly what the old PUT /Auth/image_register/<pk> allowed."""
        self.authenticate(self.student)
        response = self.client.patch(
            "/api/Auth/profile", {"role": Role.TEACHER}, format="json"
        )
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.student.refresh_from_db()
        self.assertEqual(self.student.role, Role.STUDENT)


class RoleManagementTests(AuthTestCase):
    def setUp(self):
        super().setUp()
        self.admin = User.objects.create_superuser(
            username="admin1", name="Admin", email="a@example.edu",
            password="a-good-password-1",
        )
        self.student = User.objects.create_user(
            username="stu1", name="Ravi", email="r@example.edu",
            password="a-good-password-1",
        )

    def test_admin_can_promote_a_student_to_teacher(self):
        self.authenticate(self.admin)
        response = self.client.patch(
            "/api/Auth/users/stu1/role", {"role": Role.TEACHER}, format="json"
        )
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.student.refresh_from_db()
        self.assertEqual(self.student.role, Role.TEACHER)

    def test_student_cannot_promote_themselves(self):
        self.authenticate(self.student)
        response = self.client.patch(
            "/api/Auth/users/stu1/role", {"role": Role.ADMIN}, format="json"
        )
        self.assertEqual(response.status_code, status.HTTP_403_FORBIDDEN)
        self.student.refresh_from_db()
        self.assertEqual(self.student.role, Role.STUDENT)

    def test_admin_cannot_demote_themselves(self):
        """Guards against locking every admin out of the system."""
        self.authenticate(self.admin)
        response = self.client.patch(
            "/api/Auth/users/admin1/role", {"role": Role.STUDENT}, format="json"
        )
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)

    def test_only_admins_can_list_users(self):
        self.authenticate(self.student)
        self.assertEqual(
            self.client.get("/api/Auth/users").status_code, status.HTTP_403_FORBIDDEN
        )
        self.authenticate(self.admin)
        self.assertEqual(
            self.client.get("/api/Auth/users").status_code, status.HTTP_200_OK
        )


class SuperuserTests(TestCase):
    def test_create_superuser_gets_the_admin_role_and_django_admin_access(self):
        admin = User.objects.create_superuser(
            username="root", name="Root", email="root@example.edu",
            password="a-good-password-1",
        )
        self.assertEqual(admin.role, Role.ADMIN)
        self.assertTrue(admin.is_admin)
        self.assertTrue(admin.is_staff)
