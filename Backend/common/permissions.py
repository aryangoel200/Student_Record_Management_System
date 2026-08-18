"""Role-based permission classes.

DRF denies by default (see DEFAULT_PERMISSION_CLASSES); these narrow access
further by role. Object-level ownership — "is this *your* course?" — is checked
in Home.selectors, because it needs a database lookup.
"""

from rest_framework.permissions import BasePermission

from Auth.models import Role


class _RolePermission(BasePermission):
    allowed_roles = ()

    def has_permission(self, request, view):
        user = request.user
        return bool(
            user
            and user.is_authenticated
            and user.is_active
            and user.role in self.allowed_roles
        )


class IsStudent(_RolePermission):
    message = "Only students can perform this action."
    allowed_roles = (Role.STUDENT,)


class IsTeacher(_RolePermission):
    message = "Only teachers can perform this action."
    allowed_roles = (Role.TEACHER,)


class IsAdmin(_RolePermission):
    message = "Only administrators can perform this action."
    allowed_roles = (Role.ADMIN,)


class IsTeacherOrAdmin(_RolePermission):
    message = "Only teachers and administrators can perform this action."
    allowed_roles = (Role.TEACHER, Role.ADMIN)
