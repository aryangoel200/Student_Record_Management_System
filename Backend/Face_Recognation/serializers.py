from rest_framework import serializers

from .models import FaceEnrollment


class FaceImageSerializer(serializers.Serializer):
    image = serializers.CharField(
        help_text="data: URL of a captured frame, e.g. data:image/png;base64,..."
    )


class FaceEnrollmentSerializer(serializers.ModelSerializer):
    username = serializers.CharField(source="user.username", read_only=True)

    class Meta:
        model = FaceEnrollment
        # The embedding itself is never sent to a client.
        fields = ["username", "model_name", "created_at", "updated_at"]
