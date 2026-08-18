from django.urls import path

from . import views

urlpatterns = [
    path("register_image", views.register_image, name="register_image"),
    path("enrollment", views.my_enrollment, name="my_face_enrollment"),
]

# NOTE: the old `image_verification` endpoint is deliberately gone.
# It returned {"message": "success"} and left it to the browser to then call
# /Home/attendance — so skipping the face check was one direct request away.
# Verification now happens inside Home.mark_attendance, in the same transaction
# that writes the attendance row.
