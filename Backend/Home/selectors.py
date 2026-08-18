"""Lookup helpers that enforce object-level authorisation.

Role checks (``IsTeacherOrAdmin``) say *what kind* of user may call an endpoint.
These say whether this particular user may touch this particular course — the
check the old code never made, which let any teacher delete any other teacher's
course.
"""

from rest_framework.exceptions import NotFound, PermissionDenied

from .models import Course, Session


def get_course(name):
    try:
        return Course.objects.select_related("teacher").get(name=name)
    except Course.DoesNotExist:
        raise NotFound("No such course exists.") from None


def get_course_by_code(code):
    try:
        return Course.objects.select_related("teacher").get(verification_code=code)
    except Course.DoesNotExist:
        raise NotFound("Invalid verification code.") from None


def get_managed_course(user, name):
    """The course, if `user` owns it or is an admin. Otherwise 403."""
    course = get_course(name)
    if user.is_admin or course.teacher_id == user.pk:
        return course
    raise PermissionDenied("You do not teach this course.")


def get_visible_course(user, name):
    """The course, if `user` owns it, is an admin, or is enrolled in it."""
    course = get_course(name)
    if user.is_admin or course.teacher_id == user.pk:
        return course
    if course.enrollments.filter(student=user).exists():
        return course
    raise PermissionDenied("You are not enrolled in this course.")


def get_session(course, date, start_time, end_time):
    try:
        return Session.objects.select_related("course", "course__teacher").get(
            course=course, date=date, start_time=start_time, end_time=end_time
        )
    except Session.DoesNotExist:
        raise NotFound("No such session exists.") from None
