# Student Record Management System

Under the guidance of esteemed institute professor Dr. Sharad Sinha, our team of four students at IIT Goa developed this project. Our project was designed with the primary objective of developing an efficient student record management system. This system aimed to alleviate the burden on teachers by automating the process of tracking student attendance. Furthermore, we integrated modern technologies, specifically artificial intelligence, to mitigate instances of proxy attendance, ensuring a more accurate and reliable record-keeping process.

## Tech-Stack

Our web app consists of three components: the front end, the back end, and the database.

- **Frontend**: ReactJS with Material UI and Redux Toolkit, for a dynamic and responsive interface.
- **Backend**: Django REST Framework, with JWT authentication and role-based access control.
- **Database**: SQLite, so the project runs from a fresh clone with no database server to install. The schema uses proper foreign keys and database-level constraints. *(Originally MySQL.)*
- **Face Recognition**: [DeepFace](https://github.com/serengil/deepface) — a pre-built library installed straight from PyPI, behind a swappable interface. *(We first built our own model with TensorFlow and Keras, then a `face_recognition` + dlib setup that had to be compiled from source; both were dropped, the first for hardware constraints and the second because the build step made the project hard to set up.)*

## What's so special about us?

While offering a reliable attendance system and enhancing traditional student record management with modern technologies, we've incorporated additional functionalities to elevate user experience and simplify record maintenance for teachers:

- **Two-pronged anti-proxy security.** Marking attendance requires both a face that matches your enrolled photo and a device physically inside the classroom's geofence. Both checks run **on the server**, in the same request that records the attendance — a student cannot skip them by calling the API directly.
- **Role-based access control.** Three roles — student, teacher, admin — with ownership enforced per course, so a teacher can only manage the courses they actually teach.
- **Teacher overrides for when technology fails.** Given the fluctuating bandwidth of campus WiFi, teachers can correct the register by hand. Overrides are recorded as such and attributed to the teacher who made them, so a corrected entry stays distinguishable from a verified one.
- **Data integrity by construction.** Uniqueness and validity live in the database schema, so double enrolment, duplicate sessions, and double-marked attendance are impossible rather than merely discouraged.

## Architecture

```
frontend/          React SPA
   │  JWT over HTTPS
Backend/
   ├── Auth/               accounts, roles, tokens
   ├── Home/               courses, sessions, attendance
   ├── Face_Recognation/   face enrolment and matching
   └── common/             permissions, geofence maths, error handling
```

Each API endpoint follows the same four steps, and each step lives in its own
module: **validate** the input (serializers) → **authorise** the role
(permission classes) → **authorise** the object (selectors) → **act** (views).

The governing principle is that the server never trusts a client's claim that a
check passed. Marking attendance verifies identity, enrolment, session timing,
location, and face — all server-side — before writing the row.

📖 **[Full backend documentation →](Backend/README.md)** — design patterns, the
security model, the permission matrix, and the complete API reference.

## Getting started

```bash
# Backend  (Python 3.10–3.12)
cd Backend
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
python manage.py migrate
python manage.py createsuperuser     # this account gets the "admin" role
python manage.py runserver

# Frontend
cd frontend
npm install && npm start
```

Face recognition is an optional extra, since it is a large download:

```bash
pip install -r requirements-face.txt   # then set FACE_RECOGNITION_ENABLED=true
```

With it disabled the server runs normally and the face endpoints return HTTP 503 — they refuse rather than letting unverified attendance through.

Run the test suite with `python manage.py test` from `Backend/` (78 tests, ~30s, no ML dependencies needed).

> **Note:** the backend has been reworked; the frontend is still being migrated to the new API. See [Project Set Up](Project%20Set%20Up) for current status.

## Team Details and their Contributions

1. Shobhit Chauhan -- Frontend Developer, Database Management
2. Aayush Yadav -- Geolocation Functionality, Frontend Developer
3. Rohan Manro -- Database Management, Full-Stack Developer, Machine Learning Model Development
4. Aryan Goel -- Full-Stack Developer, Machine Learning Model Development
5. Umar Sayed (Special Contributor) -- Backend Development
