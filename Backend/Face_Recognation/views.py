import logging

from rest_framework import status
from rest_framework.decorators import api_view, permission_classes, throttle_classes
from rest_framework.permissions import IsAuthenticated
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle

from .http import face_error_response
from .models import FaceEnrollment
from .serializers import FaceEnrollmentSerializer, FaceImageSerializer
from .services import FaceRecognitionError, decode_image, get_face_backend

logger = logging.getLogger(__name__)


@api_view(["POST"])
@permission_classes([IsAuthenticated])
@throttle_classes([ScopedRateThrottle])
def register_image(request):
    """Enrol (or re-enrol) the *calling user's* reference face.

    The subject is always request.user. The old endpoint took a student_Id from
    the request body, so anyone could overwrite anyone else's reference face.
    """
    serializer = FaceImageSerializer(data=request.data)
    serializer.is_valid(raise_exception=True)

    backend = get_face_backend()
    try:
        image = decode_image(serializer.validated_data["image"])
        embedding = backend.embed(image)
    except FaceRecognitionError as exc:
        return face_error_response(exc)

    enrollment, created = FaceEnrollment.objects.update_or_create(
        user=request.user,
        defaults={"embedding": embedding, "model_name": backend.name},
    )
    logger.info(
        "Face %s for %s", "enrolled" if created else "re-enrolled", request.user.username
    )
    return Response(
        FaceEnrollmentSerializer(enrollment).data,
        status=status.HTTP_201_CREATED if created else status.HTTP_200_OK,
    )


register_image.throttle_scope = "face"


@api_view(["GET", "DELETE"])
@permission_classes([IsAuthenticated])
def my_enrollment(request):
    """Inspect or withdraw your own face enrolment."""
    try:
        enrollment = request.user.face_enrollment
    except FaceEnrollment.DoesNotExist:
        if request.method == "DELETE":
            return Response(status=status.HTTP_204_NO_CONTENT)
        return Response(
            {"detail": "You have not enrolled a face yet."},
            status=status.HTTP_404_NOT_FOUND,
        )

    if request.method == "DELETE":
        enrollment.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)
    return Response(FaceEnrollmentSerializer(enrollment).data)
