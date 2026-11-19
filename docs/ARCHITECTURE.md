# Architecture

```
Browser (HTML + CSS + vanilla JS modules)        Java 21 / Spring Boot                     PostgreSQL
 ├─ public pages, login/register/recover  ──►  AuthFilter (session, roles, CSRF, origin)
 ├─ student.html  (PWA shell)             ──►  StudentAuthController  StudentController ─► students, devices,
 ├─ lecturer.html (live projector, SSE)   ──►  LecturerController  ── LiveBroker (SSE)     webauthn_credentials,
 └─ admin.html    (admin + super admin)   ──►  AdminController  SuperAdminController       biometric_enrollments,
                                               AttendanceService ChallengeService          attendance_* ,
                                               PresenceService  AnalyticsService           audit_logs, ...
                                               FaceService ─► FaceEngine (OpenCV YuNet+SFace)
                                               WebAuthnService (webauthn4j)  ReportService (POI/OpenPDF)
```

## Source layout
```
src/main/java/gh/edu/gctu/smartattend/
  config/      properties, bootstrap super admin, security headers
  common/      Db (parameterised SQL helper), errors, audit, settings, notifications
  security/    sessions, cookies, auth filter, crypto, keys, devices, flows, rate limiting, fraud alerts, staff accounts
  webauthn/    passkey ceremonies
  face/        FaceEngine interface, OpenCV implementation, liveness, FaceService
  attendance/  calendar/timetable, challenges, presence, attendance engine, analytics, SSE broker
  student/     registration/sign-in/recovery API, student API
  lecturer/    lecturer API
  admin/       admin API, register import, super-admin API
  reports/     CSV / XLSX / PDF
src/main/resources/
  db/migration/V1__schema.sql   tables, constraints, indexes, append-only audit trigger
  db/migration/V2__seed_gctu.sql  GCTU structure, six courses, lecturers, venues, week exceptions, policies
  static/                       HTML pages, css/app.css, js/*.js, manifest + service worker
```

## Database tables
institutions, departments, programmes, academic_years, semesters, academic_weeks, class_groups, app_users,
students, lecturers, administrators, courses, course_assignments, course_enrollments, class_timetables,
timetable_exceptions, venues, proximity_devices, devices, webauthn_credentials, biometric_enrollments,
face_challenges, auth_sessions, auth_flows, attendance_sessions, attendance_challenges, attendance_attempts,
attendance_records, attendance_verification_events, correction_requests, fraud_alerts, notifications,
recovery_requests, import_batches, audit_logs, system_settings.

UUID primary keys everywhere; index numbers are a unique business key, never a primary key.

## Attendance state machine
`begin` (OTP/QR validated, attempt created, 4-minute window) → `face` (single-use challenge) → `passkey`
(single-use WebAuthn challenge stored on the attempt) → `submit` (session row locked `FOR SHARE`, all required
steps re-checked, presence evaluated, `INSERT … ON CONFLICT DO NOTHING RETURNING`). Statuses: PRESENT, LATE,
ABSENT (auto on close / manual), PENDING_VERIFICATION (flagged evidence), REJECTED, EXCUSED.

## Attendance percentage
`attended eligible sessions ÷ eligible sessions × 100`, where eligible = CLOSED (not cancelled) sessions of the
course for the student's class; LATE counts as attended and EXCUSED is excluded from the denominator by default
(both configurable); PENDING_VERIFICATION counts as not attended until approved.
