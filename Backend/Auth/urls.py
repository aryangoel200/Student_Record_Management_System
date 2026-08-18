from django.urls import path
from rest_framework_simplejwt.views import TokenRefreshView

from . import views

urlpatterns = [
    path("register", views.UserRegistrationView.as_view(), name="register"),
    path("login", views.UserLoginView.as_view(), name="login"),
    path("logout", views.LogoutView.as_view(), name="logout"),
    path("token/refresh", TokenRefreshView.as_view(), name="token_refresh"),
    path("profile", views.UserProfileView.as_view(), name="profile"),
    # Admin-only user management.
    path("users", views.UserListView.as_view(), name="user_list"),
    path("users/<str:username>/role", views.UserRoleView.as_view(), name="user_role"),
]
