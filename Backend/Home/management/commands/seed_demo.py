"""Populate the database with demo data for development.

    python manage.py seed_demo
    python manage.py seed_demo --reset      # wipe demo rows first
    python manage.py seed_demo --lat 15.39 --lon 73.87

Creates teachers, students, courses, a spread of past sessions with realistic
attendance, and one session that is open *right now* so the mark-attendance
flow can be exercised end to end.

Safe to re-run: everything is get_or_create'd. Refuses to run when DEBUG is
off, so it cannot be pointed at a real deployment by accident.
"""

import random
from datetime import date, timedelta

from django.conf import settings
from django.core.management.base import BaseCommand, CommandError
from django.db import transaction
from django.utils import timezone

from Auth.models import Role, User
from Home.models import (
    AttendanceRecord,
    Course,
    Enrollment,
    Session,
    generate_verification_code,
)

DEMO_PASSWORD = "attendance-demo-1"

# Marks demo accounts so --reset can find them again without touching
# anything a developer created by hand.
DEMO_EMAIL_DOMAIN = "demo.iitgoa.ac.in"

TEACHERS = [
    ("prof.sinha", "Dr Sharad Sinha"),
    ("prof.rao", "Dr Meera Rao"),
]

STUDENTS = [
    ("2021BCS001", "Asha Rao"),
    ("2021BCS002", "Ravi Kumar"),
    ("2021BCS003", "Priya Nair"),
    ("2021BCS004", "Imran Shaikh"),
    ("2021BCS005", "Neha Desai"),
    ("2021BCS006", "Karan Mehta"),
    ("2021BCS007", "Sneha Pillai"),
    ("2021BCS008", "Arjun Bhat"),
]

# course name -> (teacher username, how many of the students enrol)
COURSES = {
    "CS210 Digital Circuits": ("prof.sinha", 8),
    "CS222 Algorithm Design": ("prof.sinha", 6),
    "CS102 Software Labs": ("prof.rao", 5),
}

# Default anchor: IIT Goa, Farmagudi.
DEFAULT_LAT, DEFAULT_LON = 15.3925, 73.8785


class Command(BaseCommand):
    help = "Create demo teachers, students, courses, sessions and attendance."

    def add_arguments(self, parser):
        parser.add_argument(
            "--reset",
            action="store_true",
            help="Delete existing demo data before seeding.",
        )
        parser.add_argument(
            "--lat", type=float, default=DEFAULT_LAT,
            help="Latitude to anchor sessions on.",
        )
        parser.add_argument(
            "--lon", type=float, default=DEFAULT_LON,
            help="Longitude to anchor sessions on.",
        )
        parser.add_argument(
            "--past-sessions", type=int, default=6,
            help="How many finished sessions to create per course.",
        )
        parser.add_argument(
            "--seed", type=int, default=20260318,
            help="Random seed, so attendance patterns are reproducible.",
        )

    @transaction.atomic
    def handle(self, *args, **options):
        if not settings.DEBUG:
            raise CommandError(
                "seed_demo refuses to run with DEBUG off — it creates accounts "
                "with a well-known password."
            )

        rng = random.Random(options["seed"])
        lat, lon = options["lat"], options["lon"]

        if options["reset"]:
            self._reset()

        teachers = {u: self._user(u, n, Role.TEACHER) for u, n in TEACHERS}
        students = [self._user(u, n, Role.STUDENT) for u, n in STUDENTS]
        admin = self._user("admin", "Administrator", Role.ADMIN)

        for course_name, (teacher_username, enrol_count) in COURSES.items():
            course = self._course(course_name, teachers[teacher_username])
            cohort = students[:enrol_count]
            for student in cohort:
                Enrollment.objects.get_or_create(course=course, student=student)

            self._past_sessions(
                course, cohort, rng, lat, lon, options["past_sessions"]
            )
            self._open_session(course, lat, lon)

        self._report(lat, lon, admin)

    # --- pieces -----------------------------------------------------------

    def _reset(self):
        demo_users = User.objects.filter(email__endswith=DEMO_EMAIL_DOMAIN)
        courses = Course.objects.filter(name__in=COURSES)
        counts = (courses.count(), demo_users.count())
        # Sessions, enrolments and attendance all cascade from these.
        courses.delete()
        demo_users.delete()
        self.stdout.write(
            self.style.WARNING(
                f"Removed {counts[0]} demo course(s) and {counts[1]} demo user(s)."
            )
        )

    def _user(self, username, name, role):
        user = User.objects.filter(username=username).first()
        if user:
            return user
        return User.objects.create_user(
            username=username,
            name=name,
            email=f"{username.lower()}@{DEMO_EMAIL_DOMAIN}",
            password=DEMO_PASSWORD,
            role=role,
        )

    def _course(self, name, teacher):
        course = Course.objects.filter(name=name).first()
        if course:
            return course
        return Course.objects.create(
            name=name,
            teacher=teacher,
            verification_code=generate_verification_code(),
        )

    def _past_sessions(self, course, cohort, rng, lat, lon, count):
        """Finished sessions on previous days, with a plausible spread.

        Each student gets their own attendance habit, so the roster looks like
        a real class rather than a coin flip: a few near-perfect, most good,
        one or two who rarely show up.
        """
        habits = {
            student: rng.uniform(0.45, 0.98) for student in cohort
        }

        today = timezone.localdate()
        for index in range(count):
            session_date = today - timedelta(days=(index + 1) * 2)
            session, created = Session.objects.get_or_create(
                course=course,
                date=session_date,
                start_time=self._time(10, 0),
                end_time=self._time(11, 0),
                defaults={"lat": lat, "lon": lon, "radius_m": 100.0},
            )
            if not created:
                continue

            for student, habit in habits.items():
                if rng.random() > habit:
                    continue
                # A small share are teacher corrections, so the UI's
                # "Marked by teacher" chip has something to show.
                manual = rng.random() < 0.12
                AttendanceRecord.objects.create(
                    session=session,
                    student=student,
                    method=(
                        AttendanceRecord.Method.MANUAL
                        if manual
                        else AttendanceRecord.Method.FACE
                    ),
                    marked_by=course.teacher if manual else None,
                    lat=None if manual else lat,
                    lon=None if manual else lon,
                    distance_m=None if manual else round(rng.uniform(2, 60), 1),
                )

    def _open_session(self, course, lat, lon):
        """One session open right now, so attendance can be marked live.

        Windows are clamped inside the day to avoid straddling midnight, which
        the `end_time > start_time` constraint would reject.
        """
        now = timezone.localtime()
        start = max(0, now.hour - 1)
        end = min(23, now.hour + 2)
        if end <= start:
            start, end = 0, 23

        Session.objects.get_or_create(
            course=course,
            date=now.date(),
            start_time=self._time(start, 0),
            end_time=self._time(end, 0),
            defaults={"lat": lat, "lon": lon, "radius_m": 150.0},
        )

    @staticmethod
    def _time(hour, minute):
        from datetime import time as _time

        return _time(hour, minute)

    def _report(self, lat, lon, admin):
        ok = self.style.SUCCESS
        self.stdout.write(ok("\nDemo data ready.\n"))

        self.stdout.write(f"Password for every account below: {DEMO_PASSWORD}\n")

        self.stdout.write(ok("\nTeachers"))
        for username, name in TEACHERS:
            owned = Course.objects.filter(teacher__username=username)
            self.stdout.write(f"  {username:<14} {name}")
            for course in owned:
                self.stdout.write(
                    f"      {course.name}  join code: {course.verification_code}"
                )

        self.stdout.write(ok("\nStudents"))
        for username, name in STUDENTS:
            user = User.objects.filter(username=username).first()
            if not user:
                continue
            enrolled = user.enrollments.count()
            present = user.attendance_records.count()
            self.stdout.write(
                f"  {username:<14} {name:<16} {enrolled} course(s), "
                f"{present} attendance record(s)"
            )

        self.stdout.write(ok("\nAdmin"))
        self.stdout.write(f"  {admin.username:<14} {admin.name}")

        open_now = Session.objects.filter(date=timezone.localdate())
        self.stdout.write(ok(f"\nOpen right now: {open_now.count()} session(s)"))
        for session in open_now:
            self.stdout.write(
                f"  {session.course.name}  "
                f"{session.start_time:%H:%M}–{session.end_time:%H:%M}  "
                f"radius {session.radius_m:.0f} m"
            )

        self.stdout.write(
            ok("\nSessions are anchored at ")
            + f"{lat}, {lon}. Point the emulator there with:"
        )
        # adb takes longitude first, which catches everyone out at least once.
        self.stdout.write(f"  adb emu geo fix {lon} {lat}\n")

        self.stdout.write(
            self.style.WARNING(
                "Students have no face enrolled — that is deliberate, so the "
                "enrolment flow can be tested. Marking attendance returns 428 "
                "until a face is registered from the app.\n"
            )
        )
