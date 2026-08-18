from django.urls import path

from . import views

urlpatterns = [
    # Courses
    path("course", views.create_new_course, name="create_course"),
    path("show_created", views.show_created, name="show_created"),
    path("show_enrolled", views.show_enrolled, name="show_enrolled"),
    path("course_registration", views.course_registration, name="course_registration"),
    path("show_students", views.show_students, name="show_students"),
    path("delete_course", views.delete_course, name="delete_course"),
    path("course_stats", views.course_stats, name="course_stats"),
    # Sessions
    path("create", views.create_new_session, name="create_session"),
    path("show_sessions", views.show_sessions, name="show_sessions"),
    path("show_active_sessions", views.show_active_sessions, name="show_active_sessions"),
    path(
        "show_students_in_session",
        views.show_students_in_session,
        name="show_students_in_session",
    ),
    path("delete_session", views.delete_session, name="delete_session"),
    # Attendance
    path("attendance", views.mark_attendance, name="mark_attendance"),
    path(
        "attendance/manual",
        views.mark_attendance_manual,
        name="mark_attendance_manual",
    ),
    path("attendance/remove", views.unmark_attendance, name="unmark_attendance"),
    # Public
    path("username_availability", views.username_availability, name="username_availability"),
]

# Removed:
#   `student`  — signup created a second row for the same person in a separate
#                table; there is now one User record.
#   `session_attendance_details` — queried columns that had been dropped by
#                migration 0005 and raised AttributeError on every call.
#                `show_students_in_session` covers it.
