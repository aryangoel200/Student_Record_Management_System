# Backend — Attendance System API

Django REST Framework API for the Student Record Management System: accounts and
roles, courses and sessions, and face- and location-verified attendance.

---

## Contents

- [Quick start](#quick-start)
- [Layout](#layout)
- [Architecture and design patterns](#architecture-and-design-patterns)
- [The security model](#the-security-model)
- [Roles and permissions](#roles-and-permissions)
- [API reference](#api-reference)
- [Data model](#data-model)
- [Configuration](#configuration)
- [Testing](#testing)
- [What changed from the original implementation](#what-changed-from-the-original-implementation)

---

## Quick start

Python 3.10–3.12. Use 3.12 if you want face recognition — TensorFlow has no 3.13
wheels yet.

```bash
cd Backend
python3.12 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

cp .env.example .env            # then edit
python manage.py migrate
python manage.py createsuperuser   # this account gets the "admin" role
python manage.py runserver
```

No MySQL, no `cmake`, no dlib compile. The database is a SQLite file at
`Backend/db.sqlite3`.

Face recognition is an optional extra, because it is a large download:

```bash
pip install -r requirements-face.txt
# then set FACE_RECOGNITION_ENABLED=true in Backend/.env
```

---

## Layout

```
Backend/
├── AttendanceSysytemServer/   # project config + root URLconf
├── common/                    # framework-agnostic shared code
│   ├── exceptions.py          #   uniform API error envelope
│   ├── geo.py                 #   haversine distance / geofence maths
│   └── permissions.py         #   role-based DRF permission classes
├── Auth/                      # User model, roles, registration, JWT
├── Home/                      # courses, enrolments, sessions, attendance
│   ├── models.py              #   schema + database constraints
│   ├── selectors.py           #   authorised lookups (object-level access)
│   ├── serializers.py         #   input validation + output shaping
│   └── views.py               #   orchestration only
└── Face_Recognation/          # face enrolment and matching
    ├── services.py            #   recognition backends (no Django imports)
    ├── http.py                #   maps recognition errors → HTTP statuses
    └── models.py              #   stored embeddings
```

---

## Architecture and design patterns

### Layered views: validate → authorise → act

Each view does the same four things in the same order, and each step lives in its
own module:

```
request
  │
  ├─ serializers.py   parse and validate input      → 400 on bad input
  ├─ permissions.py   is this *kind* of user allowed? → 403 on wrong role
  ├─ selectors.py     is this *their* object?         → 403 / 404
  └─ views.py         orchestrate, write, respond
```

Views end up short and readable, because the interesting rules live somewhere
they can be reused and tested directly. `Home/views.py` is roughly 400 lines
covering seventeen endpoints.

### Two-tier authorisation

Role and ownership are deliberately separate concerns:

| Tier | Answers | Lives in | Failure |
|---|---|---|---|
| Role | "May teachers do this at all?" | `common/permissions.py` | 403 |
| Ownership | "Is this *your* course?" | `Home/selectors.py` | 403 / 404 |

The second tier is the one that needs a database query, which is why it is a
selector function rather than a DRF permission class:

```python
def get_managed_course(user, name):
    """The course, if `user` owns it or is an admin. Otherwise 403."""
    course = get_course(name)
    if user.is_admin or course.teacher_id == user.pk:
        return course
    raise PermissionDenied("You do not teach this course.")
```

Every management endpoint routes through `get_managed_course`, so "can this
teacher touch this course?" is answered in exactly one place.

### Strategy + null object for face recognition

`Face_Recognation/services.py` defines a `BaseFaceBackend` interface —
`embed(image)` and `compare(a, b)` — with `DeepFaceBackend` as the concrete
implementation. `get_face_backend()` is the single, `lru_cache`d factory, so
model weights load once per process rather than per request.

Swapping in InsightFace, or `face_recognition`, or a cloud API means adding one
class to that file. Nothing else in the codebase changes.

`DisabledFaceBackend` is the null-object case, used when the feature is off or
the dependency is missing. It is written to **fail closed**: every method raises
`BackendUnavailable`, which becomes a 503. Turning face recognition off means
"nobody can face-verify", never "everybody passes".

`common/integrity.py` repeats the same shape for device attestation —
`BaseIntegrityVerifier`, a `PlayIntegrityVerifier`, a fail-closed
`DisabledIntegrityVerifier`, and one cached factory. Two optional heavyweight
integrations, one pattern, both safe by default.

`services.py` imports no Django and no DRF. Error-to-status mapping is a
separate module, `http.py`, shared by the enrolment view and the attendance view
so the two report identical failures identically.

### Constraints in the database, not in view code

Uniqueness and validity are enforced by the schema:

```python
constraints = [
    models.UniqueConstraint(fields=["session", "student"],
                            name="unique_attendance_per_session"),
]
```

Views catch `IntegrityError` and translate it to a 409. This is race-free in a
way that a `filter(...).exists()` check before an insert is not.

> One subtlety worth knowing: an `IntegrityError` poisons the surrounding
> transaction, so the `create()` has to be wrapped in `transaction.atomic()`
> *before* the `except` can safely run further queries. The test suite caught
> this the hard way.

### Uniform error envelope

`common/exceptions.py` installs a DRF exception handler so every failure — from
any endpoint — has the same shape:

```json
{ "detail": "You do not teach this course.",
  "errors": { "start_time": ["Enter a valid time."] } }
```

paired with a real status code. The original API returned HTTP 200 with a `msg`
string for every error, which forced the frontend to compare against English
prose to find out whether a request had succeeded.

### Configuration through the environment

`settings.py` reads everything deployment-specific from the environment, with
`.env` support for local work and small typed helpers (`env_bool`, `env_list`,
`env_float`). Missing `DJANGO_SECRET_KEY` raises `ImproperlyConfigured` when
`DEBUG` is off, so a production boot fails loudly instead of quietly running on a
committed key.

---

## The security model

**The server never trusts a client's claim that a check passed.**

That is the whole design principle, and it is why the face and location checks
moved. Previously the browser ran both and then called `/Home/attendance` if it
liked the result, so bypassing them was one `curl` away.

`POST /api/Home/attendance` now performs every check itself, in the request that
writes the row:

```
1. identity      the student is taken from the JWT, never the request body
2. enrolment     they are enrolled in the course
3. timing        the session is open right now (server clock)
4. location      not a mock provider; fix precise enough;
                 haversine(reported position, session) ≤ session.radius_m
5. attestation   Play Integrity, against a single-use nonce   (when required)
6. face          the captured frame matches their stored embedding
7. write         INSERT, guarded by a unique constraint
```

Ordering is deliberate: the free local checks run before the network call, which
runs before the face model is loaded.

### Trusting the position

A geofence only proves *what the client said its coordinates were*. Three things
turn that into something worth relying on:

- **Mock-provider flag** — Android's `Location.isMock`, forwarded by the app.
  Catches the fake-GPS apps behind most casual abuse.
- **Accuracy gate** — a coarse network fix can span a whole neighbourhood, and
  would otherwise satisfy a 100 m geofence from well outside it. Fixes vaguer
  than `MAX_LOCATION_ACCURACY_M` are refused.
- **Play Integrity** — Google attests the request came from a genuine,
  unmodified build of your app on a genuine device. This is what makes the first
  two trustworthy: without it, a patched APK simply reports `is_mock=false`.

Integrity tokens are bound to a **single-use nonce** from
`POST Home/attendance/challenge`, so a token captured once cannot be replayed
against a later session. The nonce is burned on first use whatever the verdict.

Supporting decisions:

- **Roles are server-assigned.** Public signup always creates a `student`; the
  serializer ignores any `role` in the payload. Only an admin can promote.
- **Face enrolment is self-scoped.** The subject is always `request.user`.
- **Only embeddings are stored**, never photographs, and they are never returned
  to a client.
- **Short-lived access tokens** (30 min) with rotation and blacklisting on
  logout, replacing 15-day tokens that could not be revoked.
- **Throttling** on authentication (20/min) and face endpoints (30/min).
- **Manual overrides stay auditable** — teacher corrections are recorded with
  `method="manual"` and `marked_by`, so a corrected register is distinguishable
  from a verified one.

---

## Roles and permissions

Three roles: `student`, `teacher`, `admin`. An admin can do anything a teacher
can, on any course, plus user management.

| Action | Student | Teacher | Admin |
|---|:---:|:---:|:---:|
| Register / log in | ✅ | ✅ | ✅ |
| Enrol in a course with a code | ✅ | — | — |
| Enrol a face | ✅ | ✅ | ✅ |
| Mark own attendance (face + geofence) | ✅ | — | — |
| Create / delete a course | — | own | any |
| Create / delete a session | — | own | any |
| View roster, stats, who is present | — | own | any |
| Manually mark or unmark a student | — | own | any |
| List users, change roles | — | — | ✅ |
| Django admin at `/admin/` | — | — | ✅ |

"own" means the course they teach — enforced by `get_managed_course`.

How people get roles:

```bash
python manage.py createsuperuser        # → admin
```
```http
PATCH /api/Auth/users/<username>/role
{"role": "teacher"}                     # admin only
```

An admin cannot demote themselves, which stops the last admin locking everyone
out.

---

## API reference

All paths are prefixed `/api/`. Bodies and responses are JSON. List endpoints
return a bare array.

### Auth

| Method | Path | Access | Purpose |
|---|---|---|---|
| POST | `Auth/register` | public | Create a student account, return tokens |
| POST | `Auth/login` | public | Return tokens |
| POST | `Auth/logout` | authenticated | Blacklist a refresh token |
| POST | `Auth/token/refresh` | public | Exchange refresh for a new access token |
| GET / PATCH | `Auth/profile` | authenticated | Read or edit own name/email |
| GET | `Auth/users` | admin | List accounts (`?role=` filter) |
| PATCH | `Auth/users/<username>/role` | admin | Promote or demote |

### Courses and enrolment

| Method | Path | Access | Purpose |
|---|---|---|---|
| POST | `Home/course` | teacher, admin | Create a course, returns its code |
| POST | `Home/show_created` | authenticated | Courses you teach |
| POST | `Home/show_enrolled` | authenticated | Courses you are enrolled in |
| POST | `Home/course_registration` | student | Enrol using a verification code |
| POST | `Home/show_students` | owner, admin | Roster |
| POST | `Home/course_stats` | owner, admin | Enrolment and attendance summary |
| POST | `Home/delete_course` | owner, admin | Delete (cascades) |
| POST | `Home/username_availability` | public | Pre-signup check |

### Sessions

| Method | Path | Access | Purpose |
|---|---|---|---|
| POST | `Home/create` | owner, admin | Create a session with a geofence |
| POST | `Home/show_sessions` | enrolled, owner, admin | All sessions + your presence |
| POST | `Home/show_active_sessions` | enrolled, owner, admin | Open now and unmarked |
| POST | `Home/show_students_in_session` | owner, admin | Who is present |
| POST | `Home/delete_session` | owner, admin | Delete (cascades) |

### Attendance

| Method | Path | Access | Purpose |
|---|---|---|---|
| POST | `Home/attendance` | student | Mark self — face + geofence verified |
| POST | `Home/attendance/manual` | owner, admin | Teacher override |
| POST/DELETE | `Home/attendance/remove` | owner, admin | Remove a record |

| POST | `Home/attendance/challenge` | student | Nonce for a Play Integrity token |

#### Marking attendance

`POST Home/attendance` takes `multipart/form-data` — send the frame as a JPEG
file part. Base64 costs a third more bytes, which matters on a student's data
plan. JSON with a `data:` URL still works for testing.

```bash
curl -X POST $API/Home/attendance -H "Authorization: Bearer $TOKEN" \
  -F course_name=CS210 -F date=2026-03-10 \
  -F start_time=10:00 -F end_time=11:00 \
  -F lat=15.3925 -F lon=73.8785 \
  -F location_accuracy_m=8.4 -F is_mock_location=false \
  -F "image=@frame.jpg;type=image/jpeg"
```

| Field | Required | Source on Android |
|---|---|---|
| `course_name`, `date`, `start_time`, `end_time` | ✅ | The session being marked |
| `lat`, `lon` | ✅ | `FusedLocationProviderClient` |
| `image` | ✅ | CameraX capture, JPEG ~720p |
| `location_accuracy_m` | optional | `Location.getAccuracy()` |
| `is_mock_location` | optional | `Location.isMock` (API 31+) |
| `integrity_token`, `integrity_nonce` | when required | Play Integrity + `attendance/challenge` |

Times are parsed, so `"10:00"` and `"10:00:00"` identify the same session.

Failure statuses, all with a `detail` string:

| Status | Meaning |
|---|---|
| 400 | Missing or malformed field, or an undecodable image |
| 403 | Not enrolled, mocked location, imprecise fix, outside the geofence, failed attestation, or the face did not match |
| 409 | Session not open, or already marked |
| 422 | No face, or several faces, in the frame |
| 428 | No face enrolled, or an integrity token is required and absent |
| 503 | Face recognition or integrity checking unavailable on this server |

### Face

| Method | Path | Access | Purpose |
|---|---|---|---|
| POST | `Face_Recog/register_image` | authenticated | Enrol/re-enrol **your own** face |
| GET / DELETE | `Face_Recog/enrollment` | authenticated | Inspect or withdraw it |

---

## Data model

```
User ──< Course (teacher)
 │         │
 │         ├──< Enrollment >── User          unique (course, student)
 │         │
 │         └──< Session                      unique (course, date, start, end)
 │                 │                         check  (end_time > start_time)
 │                 └──< AttendanceRecord >── User
 │                                           unique (session, student)
 └──── FaceEnrollment (1:1)
```

`User` is the single account record. `Course.students` is a `ManyToManyField`
through `Enrollment`.

`AttendanceRecord` also stores `method` (`face` / `manual`), `marked_by`, and the
position and distance the student reported, so a register can be audited after
the fact.

---

## Configuration

Set in `Backend/.env` — see `.env.example` for the annotated list.

| Variable | Default | Notes |
|---|---|---|
| `DJANGO_SECRET_KEY` | — | Required unless `DEBUG` |
| `DJANGO_DEBUG` | `false` | |
| `DJANGO_ALLOWED_HOSTS` | `localhost,127.0.0.1` | Comma-separated |
| `DJANGO_DB_PATH` | `db.sqlite3` | Relative to `Backend/` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,...` | |
| `FACE_RECOGNITION_ENABLED` | `true` | `false` → face endpoints 503 |
| `FACE_RECOGNITION_MODEL` | `Facenet512` | DeepFace model |
| `FACE_DETECTOR_BACKEND` | `opencv` | DeepFace detector |
| `FACE_MATCH_THRESHOLD` | `0.30` | Max cosine distance; lower is stricter |
| `MAX_FACE_IMAGE_BYTES` | `8388608` | Largest accepted frame |
| `DEFAULT_SESSION_RADIUS_M` | `100` | Geofence radius for new sessions |
| `REJECT_MOCK_LOCATION` | `true` | Refuse fixes from a mock provider |
| `MAX_LOCATION_ACCURACY_M` | `100` | Refuse fixes vaguer than this |
| `DEVICE_INTEGRITY_REQUIRED` | `false` | Opt in to Play Integrity; fails closed once on |
| `ANDROID_PACKAGE_NAME` | — | Required when integrity is on |
| `PLAY_INTEGRITY_CREDENTIALS` | — | Service-account JSON key path |
| `INTEGRITY_NONCE_TTL_SECONDS` | `300` | Challenge lifetime |

Tuning the match threshold: `0.30` is DeepFace's published default for
Facenet512 with cosine distance. Lower it to reject more aggressively; raise it
if legitimate students are being turned away. It is an environment variable
precisely so it can be tuned without a code change.

---

## Testing

```bash
python manage.py test              # whole suite
python manage.py test Home         # one app
```

102 tests covering the role matrix, course ownership, the attendance rules
(geofence, session window, duplicate marking, face mismatch, missing enrolment,
disabled backend), multipart upload, and the device-integrity flow including
nonce replay. The face model and the integrity verifier are both stubbed, so the
suite runs in under a minute with only `requirements.txt` installed.

The tests are written to pin down behaviour that the earlier implementation got
wrong, and are named accordingly:

```
test_attendance_is_recorded_for_the_token_holder_not_a_body_field
test_outside_the_geofence_is_refused_server_side
test_cannot_enrol_a_face_on_behalf_of_someone_else
test_disabled_face_backend_refuses_rather_than_waving_through
test_client_cannot_choose_its_own_role
```

`Home/tests.py` freezes `timezone.localtime()` so "is this session open?" never
depends on when the suite happens to run.

---

## What changed from the original implementation

| Area | Before | Now |
|---|---|---|
| Endpoint auth | No `DEFAULT_PERMISSION_CLASSES` → every `Home`/`Face_Recog` endpoint public | Deny by default; explicit opt-out for the three public endpoints |
| Face check | Browser called `/image_verification`, then called `/attendance` itself | Verified inside the attendance write |
| Geofence | Computed in the browser; server never saw the student's position | Haversine on the server against `session.radius_m` |
| Identity | `student_Id` read from the request body | Taken from the JWT |
| Roles | Boolean, client-supplied, also meaning "has a face registered" | `student`/`teacher`/`admin`, server-assigned; `face_enrolled` is separate |
| Ownership | Unchecked — any teacher could delete any course | `get_managed_course` on every management endpoint |
| Database | MySQL; relations as JSON strings in `TextField`s | SQLite; foreign keys, M2M, unique + check constraints |
| Accounts | Two rows per person (`Auth.User` + `Home.person_table`), synced by two frontend calls | One `User` |
| Face library | `face_recognition` + dlib + cmake (source build) | DeepFace behind a swappable interface; optional install |
| Face storage | PNG + `.npy` on disk under a hardcoded absolute path | Embedding in the database |
| Errors | HTTP 200 + `msg` prose for every failure | Real status codes + `{"detail": ...}` |
| Course codes | `chr(random.randint(50,100))` × 5, no uniqueness check | 8 chars from `secrets`, collision-checked, unambiguous alphabet |
| Tokens | 15-day access token, no revocation | 30-min access, rotating refresh, blacklist on logout |
| Secrets | Key, DB password, `DEBUG=True` committed | Environment variables; `.gitignore` added |
| Tests | Four empty `tests.py` stubs | 102 tests |

### Known gaps

- **DeepFace has not been exercised end to end.** The tests stub the backend
  out. The integration path — import, weight download, embed — is unverified.
- **Play Integrity has not been tested against live Google infrastructure.** The
  policy around it — nonce issue, single use, replay rejection, fail-closed
  behaviour, verdict handling — is fully tested against a stub verifier. The
  literal `decodeIntegrityToken` call in `PlayIntegrityVerifier` is not.
- **The `.npy` face encodings remain in git history.** They are untracked now,
  but purging them from past commits requires a history rewrite.
- **The frontend is only partly migrated.** The axios client and auth hooks are
  updated; the components still expect the old request and response shapes.
- Liveness detection is out of scope — a photograph of a face will still pass.
  The geofence and the session window limit the exposure, they do not remove it.
