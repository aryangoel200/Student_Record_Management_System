from django import forms
from django.contrib import admin
from django.contrib.auth.admin import UserAdmin as BaseUserAdmin
from django.contrib.auth.forms import ReadOnlyPasswordHashField
from django.contrib.auth.password_validation import validate_password

from .models import User


class UserCreationForm(forms.ModelForm):
    """Django's built-in version is bound to auth.User, so we need our own."""

    password1 = forms.CharField(label="Password", widget=forms.PasswordInput)
    password2 = forms.CharField(
        label="Password confirmation", widget=forms.PasswordInput
    )

    class Meta:
        model = User
        fields = ["username", "name", "email", "role"]

    def clean_password2(self):
        password1 = self.cleaned_data.get("password1")
        password2 = self.cleaned_data.get("password2")
        if password1 and password2 and password1 != password2:
            raise forms.ValidationError("Passwords do not match.")
        validate_password(password2)
        return password2

    def save(self, commit=True):
        user = super().save(commit=False)
        user.set_password(self.cleaned_data["password1"])
        if commit:
            user.save()
        return user


class UserChangeForm(forms.ModelForm):
    password = ReadOnlyPasswordHashField(
        label="Password",
        help_text="Raw passwords are not stored. Change it via the "
        '<a href="../password/">password form</a>.',
    )

    class Meta:
        model = User
        fields = ["username", "name", "email", "role", "is_active", "password"]


@admin.register(User)
class UserAdmin(BaseUserAdmin):
    form = UserChangeForm
    add_form = UserCreationForm

    list_display = ["username", "name", "email", "role", "is_active", "date_joined"]
    list_filter = ["role", "is_active"]
    search_fields = ["username", "name", "email"]
    ordering = ["username"]
    filter_horizontal = []

    fieldsets = [
        (None, {"fields": ["username", "password"]}),
        ("Personal info", {"fields": ["name", "email"]}),
        ("Access", {"fields": ["role", "is_active"]}),
    ]
    add_fieldsets = [
        (
            None,
            {
                "classes": ["wide"],
                "fields": [
                    "username",
                    "name",
                    "email",
                    "role",
                    "password1",
                    "password2",
                ],
            },
        )
    ]
