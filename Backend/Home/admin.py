from django.contrib import admin

from .models import AttendanceRecord, Course, Enrollment, Session


class EnrollmentInline(admin.TabularInline):
    model = Enrollment
    extra = 0
    autocomplete_fields = ["student"]


@admin.register(Course)
class CourseAdmin(admin.ModelAdmin):
    list_display = ["name", "verification_code", "teacher", "created_at"]
    search_fields = ["name", "verification_code", "teacher__username"]
    autocomplete_fields = ["teacher"]
    readonly_fields = ["verification_code", "created_at"]
    inlines = [EnrollmentInline]


@admin.register(Session)
class SessionAdmin(admin.ModelAdmin):
    list_display = ["course", "date", "start_time", "end_time", "radius_m"]
    list_filter = ["date", "course"]
    search_fields = ["course__name"]
    autocomplete_fields = ["course"]


@admin.register(AttendanceRecord)
class AttendanceRecordAdmin(admin.ModelAdmin):
    list_display = ["student", "session", "method", "marked_at", "distance_m"]
    list_filter = ["method", "session__course"]
    search_fields = ["student__username", "student__name", "session__course__name"]
    readonly_fields = ["marked_at"]
