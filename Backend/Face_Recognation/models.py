from django.conf import settings
from django.db import models


class FaceEnrollment(models.Model):
    """The reference face for one user.

    Only the embedding is kept — never the photo. The old code wrote both a PNG
    and a .npy file to disk (under a hardcoded absolute path), which is how
    personal biometric data ended up committed to the repository.
    """

    user = models.OneToOneField(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="face_enrollment",
    )
    embedding = models.JSONField(help_text="Face embedding vector (list of floats).")
    model_name = models.CharField(
        max_length=64,
        help_text="Backend that produced the embedding. Vectors from different "
        "models are not comparable.",
    )
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        verbose_name = "face enrollment"
        verbose_name_plural = "face enrollments"

    def __str__(self):
        return f"Face enrollment for {self.user.username}"
