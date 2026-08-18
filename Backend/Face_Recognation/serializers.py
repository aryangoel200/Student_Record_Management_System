from rest_framework import serializers

from .fields import FaceImageField
from .models import FaceEnrollment


class FaceImageSerializer(serializers.Serializer):
    """Accepts multipart (Android) or a base64 data URL (JSON clients)."""

    image = FaceImageField()


class FaceEnrollmentSerializer(serializers.ModelSerializer):
    username = serializers.CharField(source="user.username", read_only=True)

    class Meta:
        model = FaceEnrollment
        # The embedding itself is never sent to a client.
        fields = ["username", "model_name", "created_at", "updated_at"]
