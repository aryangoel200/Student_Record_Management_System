# Face_Recognation

Face enrolment and matching for the attendance system.

- `services.py` — the recognition backends. `BaseFaceBackend` defines the
  interface (`embed`, `compare`); `DeepFaceBackend` implements it;
  `DisabledFaceBackend` is used when the feature is off or its dependencies are
  missing, and fails closed. No Django or DRF imports live in this file.
- `http.py` — maps recognition errors onto HTTP statuses, shared with
  `Home.mark_attendance` so both report failures identically.
- `models.py` — `FaceEnrollment`, one stored embedding per user. Photographs are
  never kept, and embeddings are never returned to a client.
- `views.py` — enrolment only. The subject is always `request.user`.

**Verification does not live here.** Matching happens inside
`Home.mark_attendance`, in the same request that writes the attendance row.
The old standalone `/image_verification` endpoint returned a success flag and
left the browser to call `/Home/attendance` itself, which made the face check
trivially skippable.

## Tuning

Configure via environment variables rather than editing code — see
`Backend/.env.example`:

| Variable | Default | Effect |
|---|---|---|
| `FACE_RECOGNITION_ENABLED` | `true` | `false` → endpoints return 503 |
| `FACE_RECOGNITION_MODEL` | `Facenet512` | DeepFace model |
| `FACE_DETECTOR_BACKEND` | `opencv` | DeepFace detector |
| `FACE_MATCH_THRESHOLD` | `0.30` | Max cosine distance; lower is stricter |

`0.30` is DeepFace's published default for Facenet512 with cosine distance.
Raise it if legitimate students are being rejected, lower it to be stricter.

## Using a different library

Add a class to `services.py` implementing `embed(image) -> list[float]`, set its
`name`, and return it from `get_face_backend()`. Nothing outside that file needs
to change. `model_name` is stored alongside each embedding, so users enrolled
under a previous backend are asked to re-enrol rather than silently compared
against incompatible vectors.
