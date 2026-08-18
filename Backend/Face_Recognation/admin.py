from django.contrib import admin

from .models import FaceEnrollment


@admin.register(FaceEnrollment)
class FaceEnrollmentAdmin(admin.ModelAdmin):
    list_display = ["user", "model_name", "created_at", "updated_at"]
    search_fields = ["user__username", "user__name"]
    # Embeddings are personal data and meaningless to read; don't render them.
    exclude = ["embedding"]
    readonly_fields = ["user", "model_name", "created_at", "updated_at"]

    def has_add_permission(self, request):
        return False
