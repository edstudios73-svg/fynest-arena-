-- SmartAttend core schema
-- Internal primary keys are UUIDs. Official index numbers are unique business identifiers, never PKs.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------- Academic structure
CREATE TABLE institutions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT NOT NULL UNIQUE,
    short_name  TEXT NOT NULL UNIQUE,
    timezone    TEXT NOT NULL DEFAULT 'Africa/Accra',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE departments (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    institution_id UUID NOT NULL REFERENCES institutions(id),
    name           TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (institution_id, name)
);

CREATE TABLE programmes (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    department_id UUID NOT NULL REFERENCES departments(id),
    name          TEXT NOT NULL,
    award         TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (department_id, name)
);

CREATE TABLE academic_years (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    institution_id UUID NOT NULL REFERENCES institutions(id),
    label          TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (institution_id, label)
);

CREATE TABLE semesters (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    academic_year_id UUID NOT NULL REFERENCES academic_years(id),
    name             TEXT NOT NULL,
    start_date       DATE,            -- first day of teaching week 1 (must be confirmed by an administrator)
    end_date         DATE,
    teaching_weeks   INT  NOT NULL DEFAULT 13 CHECK (teaching_weeks BETWEEN 1 AND 30),
    is_current       BOOLEAN NOT NULL DEFAULT false,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (academic_year_id, name)
);
CREATE UNIQUE INDEX semesters_one_current ON semesters (is_current) WHERE is_current;

CREATE TABLE academic_weeks (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    semester_id UUID NOT NULL REFERENCES semesters(id) ON DELETE CASCADE,
    week_number INT  NOT NULL CHECK (week_number > 0),
    kind        TEXT NOT NULL DEFAULT 'TEACHING' CHECK (kind IN ('TEACHING','BREAK','EXAM','HOLIDAY')),
    note        TEXT,
    UNIQUE (semester_id, week_number)
);

CREATE TABLE class_groups (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    programme_id           UUID NOT NULL REFERENCES programmes(id),
    semester_id            UUID NOT NULL REFERENCES semesters(id),
    level                  INT  NOT NULL,
    name                   TEXT NOT NULL,
    expected_size          INT,
    expected_size_source   TEXT,
    register_status        TEXT NOT NULL DEFAULT 'UNVERIFIED' CHECK (register_status IN ('UNVERIFIED','VERIFIED')),
    register_verified_by   UUID,
    register_verified_at   TIMESTAMPTZ,
    register_verification_note TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (programme_id, semester_id, level, name)
);

-- ---------------------------------------------------------------- Identity
CREATE TABLE app_users (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role                 TEXT NOT NULL CHECK (role IN ('STUDENT','LECTURER','ADMIN','SUPER_ADMIN')),
    email                TEXT UNIQUE,
    password_hash        TEXT,
    status               TEXT NOT NULL DEFAULT 'ACTIVE'
                         CHECK (status IN ('PENDING_APPROVAL','ACTIVE','SUSPENDED','DISABLED')),
    failed_logins        INT  NOT NULL DEFAULT 0,
    locked_until         TIMESTAMPTZ,
    must_change_password BOOLEAN NOT NULL DEFAULT false,
    webauthn_user_handle BYTEA NOT NULL UNIQUE DEFAULT gen_random_bytes(32),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at        TIMESTAMPTZ
);

CREATE TABLE import_batches (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    filename        TEXT NOT NULL,
    sheet_name      TEXT,
    class_group_id  UUID NOT NULL REFERENCES class_groups(id),
    uploaded_by     UUID NOT NULL REFERENCES app_users(id),
    row_count       INT NOT NULL,
    valid_count     INT NOT NULL,
    invalid_count   INT NOT NULL,
    duplicate_count INT NOT NULL,
    enrol_all_courses BOOLEAN NOT NULL DEFAULT true,
    status          TEXT NOT NULL DEFAULT 'PREVIEW' CHECK (status IN ('PREVIEW','COMMITTED','CANCELLED')),
    preview         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    committed_at    TIMESTAMPTZ,
    committed_by    UUID REFERENCES app_users(id)
);

CREATE TABLE students (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID UNIQUE REFERENCES app_users(id),
    class_group_id  UUID NOT NULL REFERENCES class_groups(id),
    index_number    TEXT NOT NULL UNIQUE,
    title           TEXT,
    first_name      TEXT NOT NULL,
    last_name       TEXT NOT NULL,
    other_names     TEXT,
    register_status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (register_status IN ('ACTIVE','UNDER_REVIEW','WITHDRAWN')),
    import_batch_id UUID REFERENCES import_batches(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (index_number ~ '^[A-Za-z0-9/._-]{3,40}$')
);
CREATE INDEX students_class_group ON students(class_group_id);
CREATE INDEX students_name_search ON students (lower(last_name || ' ' || first_name || ' ' || coalesce(other_names,'')));

CREATE TABLE lecturers (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL UNIQUE REFERENCES app_users(id),
    title      TEXT,
    full_name  TEXT NOT NULL,
    staff_id   TEXT UNIQUE,
    department_id UUID REFERENCES departments(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE administrators (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL UNIQUE REFERENCES app_users(id),
    full_name      TEXT NOT NULL,
    institution_id UUID REFERENCES institutions(id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- Courses & timetable
CREATE TABLE venues (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    institution_id UUID NOT NULL REFERENCES institutions(id),
    code           TEXT NOT NULL,
    description    TEXT,
    latitude       DOUBLE PRECISION,
    longitude      DOUBLE PRECISION,
    radius_m       INT NOT NULL DEFAULT 60 CHECK (radius_m BETWEEN 10 AND 2000),
    network_cidrs  TEXT[] NOT NULL DEFAULT '{}',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (institution_id, code)
);

CREATE TABLE proximity_devices (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    venue_id    UUID NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    kind        TEXT NOT NULL CHECK (kind IN ('BLE_BEACON','WIFI_AP','LOCAL_NETWORK_NODE')),
    identifier  TEXT NOT NULL,
    metadata    JSONB NOT NULL DEFAULT '{}',
    active      BOOLEAN NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (venue_id, kind, identifier)
);

CREATE TABLE courses (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    programme_id   UUID NOT NULL REFERENCES programmes(id),
    code           TEXT UNIQUE,           -- may be pending confirmation (e.g. General Communication Skills I)
    alt_code       TEXT,
    title          TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE course_assignments (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    course_id   UUID NOT NULL REFERENCES courses(id),
    lecturer_id UUID NOT NULL REFERENCES lecturers(id),
    semester_id UUID NOT NULL REFERENCES semesters(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (course_id, lecturer_id, semester_id)
);
CREATE INDEX course_assignments_lecturer ON course_assignments(lecturer_id);

CREATE TABLE course_enrollments (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    course_id   UUID NOT NULL REFERENCES courses(id),
    student_id  UUID NOT NULL REFERENCES students(id),
    semester_id UUID NOT NULL REFERENCES semesters(id),
    status      TEXT NOT NULL DEFAULT 'ENROLLED' CHECK (status IN ('ENROLLED','DROPPED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (course_id, student_id, semester_id)
);
CREATE INDEX course_enrollments_student ON course_enrollments(student_id);

CREATE TABLE class_timetables (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    course_id      UUID NOT NULL REFERENCES courses(id),
    class_group_id UUID NOT NULL REFERENCES class_groups(id),
    semester_id    UUID NOT NULL REFERENCES semesters(id),
    day_of_week    INT  NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),  -- ISO: 1 = Monday
    start_time     TIME NOT NULL,
    end_time       TIME NOT NULL,
    venue_id       UUID REFERENCES venues(id),
    mode           TEXT NOT NULL DEFAULT 'IN_PERSON' CHECK (mode IN ('IN_PERSON','ONLINE')),
    notes          TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (end_time > start_time)
);

CREATE TABLE timetable_exceptions (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    timetable_id   UUID NOT NULL REFERENCES class_timetables(id) ON DELETE CASCADE,
    week_number    INT  NOT NULL CHECK (week_number > 0),
    venue_id       UUID REFERENCES venues(id),
    mode           TEXT CHECK (mode IN ('IN_PERSON','ONLINE')),
    cancelled      BOOLEAN NOT NULL DEFAULT false,
    note           TEXT,
    UNIQUE (timetable_id, week_number)
);

-- ---------------------------------------------------------------- Devices, passkeys, biometrics
CREATE TABLE devices (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID NOT NULL REFERENCES app_users(id),
    device_cookie_hash BYTEA NOT NULL,
    label              TEXT,
    user_agent         TEXT,
    status             TEXT NOT NULL DEFAULT 'TRUSTED' CHECK (status IN ('TRUSTED','REVOKED')),
    first_seen_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at         TIMESTAMPTZ,
    UNIQUE (user_id, device_cookie_hash)
);
CREATE INDEX devices_cookie ON devices(device_cookie_hash);

CREATE TABLE webauthn_credentials (
    id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                    UUID NOT NULL REFERENCES app_users(id),
    device_id                  UUID REFERENCES devices(id),
    credential_id              BYTEA NOT NULL UNIQUE,
    attested_credential_data   BYTEA NOT NULL,
    sign_count                 BIGINT NOT NULL DEFAULT 0,
    uv_initialized             BOOLEAN NOT NULL,
    backup_eligible            BOOLEAN,
    backup_state               BOOLEAN,
    transports                 TEXT,
    label                      TEXT,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at               TIMESTAMPTZ,
    revoked_at                 TIMESTAMPTZ
);
CREATE INDEX webauthn_credentials_user ON webauthn_credentials(user_id) WHERE revoked_at IS NULL;

CREATE TABLE biometric_enrollments (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id          UUID NOT NULL REFERENCES students(id),
    provider            TEXT NOT NULL,
    model_version       TEXT NOT NULL,
    template_ciphertext BYTEA NOT NULL,     -- AES-256-GCM encrypted face embedding (never a raw image)
    template_iv         BYTEA NOT NULL,
    key_id              TEXT NOT NULL,
    quality_score       DOUBLE PRECISION,
    consent_version     TEXT NOT NULL,
    consent_given_at    TIMESTAMPTZ NOT NULL,
    status              TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED','DELETED')),
    retention_until     DATE NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at          TIMESTAMPTZ
);
CREATE UNIQUE INDEX biometric_one_active ON biometric_enrollments(student_id) WHERE status = 'ACTIVE';

CREATE TABLE face_challenges (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id  UUID NOT NULL REFERENCES students(id),
    purpose     TEXT NOT NULL CHECK (purpose IN ('ENROL','LOGIN','ATTENDANCE','RECOVERY')),
    steps       TEXT[] NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    passed      BOOLEAN,
    score       DOUBLE PRECISION,
    failure     TEXT
);
CREATE INDEX face_challenges_student ON face_challenges(student_id, created_at DESC);

-- ---------------------------------------------------------------- Sessions / auth flows
CREATE TABLE auth_sessions (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash    BYTEA NOT NULL UNIQUE,
    user_id       UUID NOT NULL REFERENCES app_users(id),
    csrf_token    TEXT NOT NULL,
    device_id     UUID REFERENCES devices(id),
    ip            TEXT,
    user_agent    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    revoked_at    TIMESTAMPTZ
);
CREATE INDEX auth_sessions_user ON auth_sessions(user_id);

-- Multi-step unauthenticated flows (registration, sign-in, device recovery)
CREATE TABLE auth_flows (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash         BYTEA NOT NULL UNIQUE,
    kind               TEXT NOT NULL CHECK (kind IN ('REGISTRATION','LOGIN','RECOVERY')),
    student_id         UUID NOT NULL REFERENCES students(id),
    stage              TEXT NOT NULL,
    webauthn_challenge BYTEA,
    face_passed_at     TIMESTAMPTZ,
    consent_at         TIMESTAMPTZ,
    recovery_request_id UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ NOT NULL,
    completed_at       TIMESTAMPTZ
);

-- ---------------------------------------------------------------- Attendance
CREATE TABLE attendance_sessions (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    course_id          UUID NOT NULL REFERENCES courses(id),
    class_group_id     UUID NOT NULL REFERENCES class_groups(id),
    semester_id        UUID NOT NULL REFERENCES semesters(id),
    timetable_id       UUID REFERENCES class_timetables(id),
    started_by         UUID NOT NULL REFERENCES app_users(id),
    session_date       DATE NOT NULL,
    mode               TEXT NOT NULL CHECK (mode IN ('IN_PERSON','ONLINE')),
    venue_id           UUID REFERENCES venues(id),
    status             TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED','CANCELLED')),
    opened_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    closes_at          TIMESTAMPTZ NOT NULL,
    closed_at          TIMESTAMPTZ,
    closed_by          UUID REFERENCES app_users(id),
    late_after_minutes INT NOT NULL CHECK (late_after_minutes >= 0),
    require_face       BOOLEAN NOT NULL,
    require_passkey    BOOLEAN NOT NULL,
    proximity_policy   TEXT NOT NULL CHECK (proximity_policy IN ('NONE','EVIDENCE','STRICT')),
    notes              TEXT
);
CREATE UNIQUE INDEX attendance_one_open_per_course ON attendance_sessions(course_id, class_group_id) WHERE status = 'OPEN';
CREATE INDEX attendance_sessions_course ON attendance_sessions(course_id, session_date);

CREATE TABLE attendance_challenges (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id    UUID NOT NULL REFERENCES attendance_sessions(id),
    otp_hash      BYTEA NOT NULL,       -- HMAC-SHA256(server key, session_id || otp); the OTP itself is never stored
    qr_token_hash BYTEA NOT NULL UNIQUE,
    issued_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    revoked_at    TIMESTAMPTZ
);
CREATE INDEX attendance_challenges_session ON attendance_challenges(session_id, expires_at DESC);
CREATE INDEX attendance_challenges_otp ON attendance_challenges(otp_hash);

CREATE TABLE attendance_attempts (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id        UUID NOT NULL REFERENCES attendance_sessions(id),
    student_id        UUID NOT NULL REFERENCES students(id),
    challenge_id      UUID NOT NULL REFERENCES attendance_challenges(id),
    method            TEXT NOT NULL CHECK (method IN ('OTP','QR')),
    device_id         UUID REFERENCES devices(id),
    challenge_ok_at   TIMESTAMPTZ NOT NULL,
    face_ok_at        TIMESTAMPTZ,
    face_challenge_id UUID REFERENCES face_challenges(id),
    passkey_ok_at     TIMESTAMPTZ,
    webauthn_challenge BYTEA,
    status            TEXT NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS','COMPLETED','FAILED','EXPIRED')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ NOT NULL
);
CREATE INDEX attendance_attempts_student ON attendance_attempts(student_id, session_id);

CREATE TABLE attendance_records (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id          UUID NOT NULL REFERENCES attendance_sessions(id),
    student_id          UUID NOT NULL REFERENCES students(id),
    status              TEXT NOT NULL CHECK (status IN ('PRESENT','LATE','ABSENT','PENDING_VERIFICATION','REJECTED','EXCUSED')),
    verification_status TEXT NOT NULL CHECK (verification_status IN ('VERIFIED','FLAGGED','MANUAL','AUTO_ABSENT')),
    reference_no        TEXT NOT NULL UNIQUE,
    attempt_id          UUID REFERENCES attendance_attempts(id),
    device_id           UUID REFERENCES devices(id),
    presence_evidence   JSONB NOT NULL DEFAULT '{}',
    submitted_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by          UUID REFERENCES app_users(id),
    reason              TEXT,
    UNIQUE (session_id, student_id)           -- one record per student per session, enforced by the database
);
CREATE INDEX attendance_records_student ON attendance_records(student_id);

CREATE TABLE attendance_verification_events (
    id          BIGSERIAL PRIMARY KEY,
    session_id  UUID REFERENCES attendance_sessions(id),
    student_id  UUID REFERENCES students(id),
    attempt_id  UUID REFERENCES attendance_attempts(id),
    event_type  TEXT NOT NULL,
    success     BOOLEAN NOT NULL,
    detail      JSONB NOT NULL DEFAULT '{}',
    ip          TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX attendance_events_session ON attendance_verification_events(session_id, created_at DESC);
CREATE INDEX attendance_events_student ON attendance_verification_events(student_id, created_at DESC);

CREATE TABLE correction_requests (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id       UUID NOT NULL REFERENCES attendance_sessions(id),
    student_id       UUID NOT NULL REFERENCES students(id),
    requested_status TEXT NOT NULL CHECK (requested_status IN ('PRESENT','LATE','EXCUSED')),
    reason           TEXT NOT NULL,
    status           TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    reviewed_by      UUID REFERENCES app_users(id),
    reviewed_at      TIMESTAMPTZ,
    review_note      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX correction_one_pending ON correction_requests(session_id, student_id) WHERE status = 'PENDING';

-- ---------------------------------------------------------------- Security & operations
CREATE TABLE fraud_alerts (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind         TEXT NOT NULL,
    severity     TEXT NOT NULL CHECK (severity IN ('LOW','MEDIUM','HIGH')),
    student_id   UUID REFERENCES students(id),
    session_id   UUID REFERENCES attendance_sessions(id),
    device_id    UUID REFERENCES devices(id),
    detail       JSONB NOT NULL DEFAULT '{}',
    status       TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','DISMISSED','CONFIRMED')),
    reviewed_by  UUID REFERENCES app_users(id),
    reviewed_at  TIMESTAMPTZ,
    review_note  TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX fraud_alerts_status ON fraud_alerts(status, created_at DESC);

CREATE TABLE notifications (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES app_users(id),
    title      TEXT NOT NULL,
    body       TEXT NOT NULL,
    link       TEXT,
    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX notifications_user ON notifications(user_id, created_at DESC);

CREATE TABLE recovery_requests (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id         UUID NOT NULL REFERENCES students(id),
    kind               TEXT NOT NULL CHECK (kind IN ('DEVICE_CHANGE','ALT_VERIFICATION','REGISTRATION_DISPUTE')),
    reason             TEXT NOT NULL,
    device_cookie_hash BYTEA,
    face_verified      BOOLEAN NOT NULL DEFAULT false,
    status             TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','COMPLETED','EXPIRED')),
    reviewed_by        UUID REFERENCES app_users(id),
    reviewed_at        TIMESTAMPTZ,
    review_note        TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ NOT NULL
);
CREATE INDEX recovery_requests_status ON recovery_requests(status, created_at DESC);

CREATE TABLE audit_logs (
    id            BIGSERIAL PRIMARY KEY,
    actor_user_id UUID,
    actor_role    TEXT,
    action        TEXT NOT NULL,
    entity_type   TEXT,
    entity_id     TEXT,
    detail        JSONB NOT NULL DEFAULT '{}',
    ip            TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX audit_logs_created ON audit_logs(created_at DESC);

-- Audit log is append-only: block UPDATE and DELETE at the database level.
CREATE FUNCTION audit_logs_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only';
END $$;
CREATE TRIGGER audit_logs_no_update BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION audit_logs_immutable();

CREATE TABLE system_settings (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_by UUID REFERENCES app_users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
