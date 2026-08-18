"""Root URL configuration.

    /admin/              Django admin (admin role only)
    /api/Auth/           registration, login, tokens, user management
    /api/Home/           courses, sessions, attendance
    /api/Face_Recog/     face enrolment
"""

from django.contrib import admin
from django.urls import include, path

urlpatterns = [
    path("admin/", admin.site.urls),
    path("api/Auth/", include("Auth.urls")),
    path("api/Face_Recog/", include("Face_Recognation.urls")),
    path("api/Home/", include("Home.urls")),
]
