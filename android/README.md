# Android client

Native Kotlin app for the attendance system — Jetpack Compose with Material 3,
talking to the Django API in [`../Backend`](../Backend/README.md).

Covers both journeys: students enrol in courses, register a face and mark
attendance; teachers and admins create courses and sessions, review rosters and
stats, and correct the register.

---

## Build

Needs JDK 17 or 21 and the Android SDK. Android Studio bundles both.

```bash
cd android
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # to a connected device or emulator
```

From the command line without Android Studio, point Gradle at an SDK by
creating `local.properties`:

```properties
sdk.dir=/path/to/android-sdk
```

### Reaching the API

Debug builds target `http://127.0.0.1:8000/api/` through an **adb reverse
tunnel**, which must be set up once per device per adb connection:

```bash
adb reverse tcp:8000 tcp:8000            # or ./run-dev.sh, which does this
cd ../Backend && ./.venv/bin/python manage.py runserver 0.0.0.0:8000
```

**Not `10.0.2.2`.** That alias only resolves to the host on the emulator's NAT
interface (`eth0`), and modern emulator images route app traffic over their
virtual WiFi (`wlan0`) instead — where `10.0.2.2` is not the host and every
request times out after 20 s. The symptom is a `SocketTimeoutException` whose
message names a source address like `/10.0.2.16`, i.e. the wlan0 interface.
A reverse tunnel goes over adb, so the guest network is irrelevant, and it is
the only option that also works on a phone plugged in over USB.

Re-run `adb reverse` after restarting an emulator or replugging a device — the
tunnel does not survive.

Release builds use the HTTPS URL in `app/build.gradle.kts`; change it before
shipping.

`applicationId` is `edu.iitgoa.attendance`. If you enable Play Integrity it must
match the Play Console package name and the server's `ANDROID_PACKAGE_NAME`.

## Stack

| Concern | Choice |
|---|---|
| UI | Jetpack Compose, Material 3, dynamic colour |
| Navigation | navigation-compose |
| Networking | Retrofit + OkHttp + kotlinx.serialization |
| Auth refresh | OkHttp `Authenticator` |
| Token storage | DataStore (app-private) |
| Camera | CameraX |
| Location | `FusedLocationProviderClient` |
| Attestation | Play Integrity |
| DI | A hand-written `AppContainer` |

No Hilt or KSP. The graph is about a dozen objects, and a plain container keeps
annotation processing out of the build entirely.

## Layout

```
data/
  remote/     Retrofit API, DTOs, auth interceptor, token authenticator
  local/      TokenStore (DataStore)
  repo/       AuthRepository, CourseRepository, AttendanceRepository
  AppContainer.kt, SessionManager.kt, ApiResult.kt
ui/
  auth/       login, signup
  student/    home, face enrolment, sessions, mark attendance
  teacher/    courses, course detail (sessions/students/stats), session register
  common/     permission gate, error banner, empty states
util/         CameraX capture, image processing, location, Play Integrity
```

Screens are stateless composables driven by a ViewModel `StateFlow`, so they can
be previewed and tested without a network.

## The rule this app follows

**The app collects evidence. The server decides.**

Marking attendance gathers a camera frame, a location fix and — where the server
requires it — a Play Integrity token, and posts all three. It never decides
whether the face matched or whether the student was close enough.

That is not an accident of implementation. The original web client ran both
checks in the browser and then called the attendance endpoint if it liked the
result, which made the whole anti-proxy story bypassable with one `curl`. Moving
either judgement back into this app would reintroduce exactly that hole.

The client-side pieces that *do* matter are the ones the server cannot see for
itself:

- `Location.isMock` — forwarded, not acted on. The server refuses mocked fixes.
- `Location.getAccuracy()` — forwarded so the server can reject a fix too vague
  to place someone in a classroom.
- A Play Integrity token — what makes the first two believable, since a patched
  APK could otherwise just report `is_mock=false`.

## Attendance flow

```
1. POST Home/attendance/challenge      -> nonce (single-use), integrity_required
2. if required: Play Integrity token bound to that nonce
3. CameraX capture -> rotate, downscale to 720px, JPEG q80  (~150 KB)
4. FusedLocationProvider high-accuracy fix
5. POST Home/attendance as multipart/form-data
```

The nonce is fetched per attempt and never cached — that is what stops a
captured token being replayed for a later session.

Frames go up as raw JPEG file parts rather than base64 data URLs, which is a
third fewer bytes on mobile data. They are never written to storage.

## Session handling

`AuthInterceptor` attaches the access token; `TokenAuthenticator` refreshes on
401 and retries, synchronising so concurrent 401s trigger one refresh rather
than one each. When the refresh token itself is rejected, `SessionManager` flips
to signed-out and the UI returns to login.

Access tokens last 30 minutes. Tokens live in DataStore, which the Android
sandbox protects from other apps but does not encrypt at rest — another reason
the server treats Play Integrity, not the token alone, as its trust anchor.

## Errors

Every API failure carries a `detail` string written for a person, so the UI
shows the server's own words — "You are not within the session's location to
mark attendance" — rather than mapping status codes to invented copy.
`ApiResult` turns exceptions into that message once, in `apiCall`, so no screen
has a try/catch.

## Not done yet

- **No tests.** The ViewModels are constructor-injected and take repository
  interfaces, so they are testable; nothing is written.
- **No liveness detection.** A photograph of a face will still pass. The
  geofence, the session window and Play Integrity narrow the opportunity; they
  do not close it.
- **No offline queue.** Marking attendance requires connectivity.
- **Nothing has run on a device.** This builds and produces an APK; it has not
  been installed or exercised against a live server.
- **Default launcher icon**, and no admin screens for promoting users — that is
  in the Django admin.
