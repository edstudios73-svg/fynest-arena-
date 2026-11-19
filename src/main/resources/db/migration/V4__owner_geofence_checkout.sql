-- Owner-run attendance: index number + face (no passkeys), QR/code, geofence around the admin's device,
-- and an end-of-class confirmation before attendance counts.

-- New record state: checked in, waiting for the end-of-class confirmation.
ALTER TABLE attendance_records DROP CONSTRAINT attendance_records_status_check;
ALTER TABLE attendance_records ADD CONSTRAINT attendance_records_status_check
    CHECK (status IN ('CHECKED_IN','PRESENT','LATE','ABSENT','PENDING_VERIFICATION','REJECTED','EXCUSED'));
ALTER TABLE attendance_records
    ADD COLUMN checked_in_late BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN checkout_face_at TIMESTAMPTZ,
    ADD COLUMN checked_out_at TIMESTAMPTZ,
    ADD COLUMN checkout_evidence JSONB;

-- Sessions know when the class ends and where the admin's device is.
ALTER TABLE attendance_sessions DROP CONSTRAINT attendance_sessions_proximity_policy_check;
ALTER TABLE attendance_sessions ADD CONSTRAINT attendance_sessions_proximity_policy_check
    CHECK (proximity_policy IN ('NONE','EVIDENCE','STRICT','GEOFENCE'));
ALTER TABLE attendance_sessions
    ADD COLUMN ends_at TIMESTAMPTZ,
    ADD COLUMN checkout_minutes INT NOT NULL DEFAULT 10 CHECK (checkout_minutes BETWEEN 0 AND 60),
    ADD COLUMN geofence_m INT NOT NULL DEFAULT 50 CHECK (geofence_m BETWEEN 10 AND 1000),
    ADD COLUMN anchor_lat DOUBLE PRECISION,
    ADD COLUMN anchor_lon DOUBLE PRECISION,
    ADD COLUMN anchor_accuracy DOUBLE PRECISION,
    ADD COLUMN anchor_at TIMESTAMPTZ,
    ADD COLUMN checkout_notified BOOLEAN NOT NULL DEFAULT false;
UPDATE attendance_sessions SET ends_at = closes_at WHERE ends_at IS NULL;

-- Attempts: index number + face come first, the classroom code after.
ALTER TABLE attendance_attempts ALTER COLUMN challenge_id DROP NOT NULL, ALTER COLUMN challenge_ok_at DROP NOT NULL;
ALTER TABLE attendance_attempts DROP CONSTRAINT attendance_attempts_method_check;
ALTER TABLE attendance_attempts ALTER COLUMN method DROP NOT NULL;
ALTER TABLE attendance_attempts ADD CONSTRAINT attendance_attempts_method_check CHECK (method IS NULL OR method IN ('OTP','QR'));

INSERT INTO system_settings(key, value) VALUES
    ('security.passkeyOnLogin', 'false'),
    ('security.faceOnCheckout', 'true'),
    ('attendance.geofenceMeters', '50'),
    ('attendance.checkoutMinutes', '10'),
    ('attendance.classMinutes', '120')
ON CONFLICT (key) DO NOTHING;
UPDATE system_settings SET value = 'false' WHERE key IN ('security.passkeyOnAttendance', 'security.registrationRequiresApproval');
UPDATE system_settings SET value = 'GEOFENCE' WHERE key = 'proximity.defaultPolicy';
