# SmartAttend security model

## Layers that stop proxy attendance

| Layer | What it proves | Where enforced |
|---|---|---|
| Official register | The index number exists on a register an administrator verified; one account per index | `StudentAuthController`, unique `students.user_id`, `students.index_number` |
| Rotating challenge | The student saw the lecturer's screen in the last 60 s | `ChallengeService` — OTP from `SecureRandom`, stored as HMAC-SHA256(server key, session‖otp); QR = 192-bit opaque token stored as SHA-256 |
| Live face check | The person present matches the enrolled student (1:1) and is live | `FaceService`, `LivenessEvaluator` |
| Passkey | The registered phone was used, unlocked by its owner (UV required) | `WebAuthnService` (webauthn4j) |
| Presence evidence | Network / GPS consistent with the venue | `PresenceService` (evidence, not proof) |
| Server timing | Session open, attempt < 4 min old, challenge unexpired | `AttendanceService` |
| One record per session | DB `UNIQUE(session_id, student_id)` + `ON CONFLICT DO NOTHING` | Schema; tested with concurrent submits |

Success is shown only after the database transaction returns the inserted row.

## Authentication & sessions
- Students: passkeys (no passwords). Staff: BCrypt(12) passwords, 12+ chars, 3 character classes, temporary passwords force a change.
- Sessions: 256-bit random cookie (`HttpOnly`, `SameSite=Lax`, `Secure` in production); only SHA-256 stored; 8 h absolute and 2 h idle expiry; revoked on password change, suspension, device revocation and recovery.
- Lockouts: 5 failed sign-ins → 15 min lock (persisted). Rate limits on index lookups, staff login, face checks, attendance attempts; wrong OTPs capped per session.

## Authorisation
`AuthFilter` maps every `/api/**` path to roles server-side; `CourseAccess` adds object-level checks (lecturers only act on assigned courses); student queries are always scoped to the session's own student id; students have no write endpoint for attendance. Security policies (`security.*`, `face.*`, `biometric.*`) require Super Administrator. Lecturers cannot disable face/passkey requirements on a session.

Row Level Security is **not** used: browsers never connect to the database directly (unlike a Supabase client app), so all access goes through the Java service, which enforces the rules above. Use a dedicated database role for the app.

## Web protections
CSRF token header for all authenticated state changes, plus an Origin/Referer allow-list on every unsafe request; strict Content-Security-Policy (`script-src 'self'`, no inline scripts or styles) and output escaping for XSS; parameterised SQL only; validated uploads (size, type by magic bytes, row limits); spreadsheet-formula neutralisation in exports; generic error messages without stack traces; HSTS, `X-Frame-Options: DENY`, `nosniff`, restrictive `Permissions-Policy`.

## Encryption boundaries (honest statement)
- **In transit:** HTTPS (TLS terminated at the hosting platform).
- **At rest:** face templates are encrypted by the application with AES-256-GCM; the student id is bound as associated data so a template cannot be swapped to another account. Other data relies on the database provider's disk encryption.
- **Not end-to-end:** the server must read codes, face frames and records to validate them. Face frames are processed in memory and discarded; no photos are stored.
- **Keys:** `SMARTATTEND_BIOMETRIC_KEY`, `SMARTATTEND_HMAC_KEY` come from the environment / secret manager, never the repo.

## Face verification limits
- Models: YuNet (detection, landmarks) and SFace (128-d embedding, LFW 99.4 %). Threshold 0.42 cosine (OpenCV's reference is 0.363; stricter here), configurable.
- Liveness: random CENTER/LEFT/RIGHT sequence, single-use, 150 s expiry; yaw from landmarks; identical frames rejected; identity must stay consistent through the sequence. A tilted printed photo cannot fake yaw (tested with real models).
- **Not defeated by this alone:** a real-time deepfake or injected virtual camera that follows instructions. Mitigated by the other layers (passkey on the registered phone, rotating classroom code, human review) — and replaceable by a certified PAD provider via `FaceEngine`.
- Accuracy varies with lighting and camera quality; failures fall back to retries, then supervised verification. Repeated failures raise alerts; they never auto-penalise.
- Optional duplicate-enrolment check flags (does not block) a new face that matches another student's template.

## Proximity limits
- **Network:** only meaningful if the server sees campus-internal IPs (on-premise) or a known campus egress IP. VPNs and mobile data change IPs.
- **GPS:** spoofable and imprecise indoors; recorded as evidence; a clear mismatch flags the record for review.
- **BLE beacons:** registrable per venue, but browsers can't scan advertisements → native app required.
- Default policy `EVIDENCE` never rejects on location alone; `STRICT` requires a positive network or GPS signal.

## Fraud signals (reviewed by humans)
Same browser used by multiple student accounts, repeated wrong codes, repeated face failures, QR from another session, location inconsistency, new-device sign-in, duplicate face at enrolment.

## Audit
`audit_logs` is append-only (a trigger blocks UPDATE/DELETE). Logged: sign-ins and failures, registrations, consent, imports, register verification, record corrections (before/after), manual attendance changes, approvals, suspensions, recovery decisions, setting changes, report exports and admin views of student profiles.

## Privacy (Act 843)
Explicit consent before enrolment; privacy notice at `/privacy.html`; templates used only for attendance and recovery; no 1:N identification across other databases; retention (default 730 days) enforced by a daily job; deletion on request by administrators; administrators see enrolment status only.
