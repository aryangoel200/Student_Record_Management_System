from django.contrib.auth.password_validation import validate_password
from django.core.exceptions import ValidationError as DjangoValidationError
from rest_framework import serializers

from .models import Role, User


class UserProfileSerializer(serializers.ModelSerializer):
    """What the client is told about a user. Never includes the password."""

    face_enrolled = serializers.BooleanField(read_only=True)

    class Meta:
        model = User
        fields = ["username", "name", "email", "role", "face_enrolled", "date_joined"]
        read_only_fields = ["username", "role", "face_enrolled", "date_joined"]


class UserRegistrationSerializer(serializers.ModelSerializer):
    password = serializers.CharField(
        write_only=True, style={"input_type": "password"}, min_length=8
    )
    password2 = serializers.CharField(
        write_only=True, style={"input_type": "password"}
    )

    class Meta:
        model = User
        fields = ["username", "name", "email", "password", "password2"]

    def validate_email(self, value):
        if User.objects.filter(email__iexact=value).exists():
            raise serializers.ValidationError("That email address is already in use.")
        return value.lower()

    def validate(self, attrs):
        if attrs["password"] != attrs["password2"]:
            raise serializers.ValidationError(
                {"password2": "Password and Confirm Password do not match."}
            )
        # Run Django's validators (length, common passwords, all-numeric, and
        # similarity to the username/name/email).
        probe = User(
            username=attrs["username"], name=attrs["name"], email=attrs["email"]
        )
        try:
            validate_password(attrs["password"], user=probe)
        except DjangoValidationError as exc:
            raise serializers.ValidationError({"password": list(exc.messages)}) from exc
        return attrs

    def create(self, validated_data):
        validated_data.pop("password2")
        # Role is deliberately NOT taken from the request. Public signup always
        # creates a student; an admin promotes teachers via /Auth/users/<u>/role.
        return User.objects.create_user(role=Role.STUDENT, **validated_data)


class UserLoginSerializer(serializers.Serializer):
    username = serializers.CharField(max_length=32)
    password = serializers.CharField(
        write_only=True, style={"input_type": "password"}
    )


class LogoutSerializer(serializers.Serializer):
    refresh = serializers.CharField()


class UserRoleSerializer(serializers.ModelSerializer):
    """Admin-only: change a user's role."""

    class Meta:
        model = User
        fields = ["role"]

    def validate_role(self, value):
        request = self.context.get("request")
        if request and self.instance and self.instance.pk == request.user.pk:
            if value != Role.ADMIN:
                raise serializers.ValidationError(
                    "You cannot remove your own admin role."
                )
        return value
