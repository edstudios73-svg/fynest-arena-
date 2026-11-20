-- Students are approved automatically and the class list is active as soon as it is loaded.
UPDATE system_settings SET value = 'false' WHERE key = 'security.registrationRequiresApproval';
UPDATE app_users SET status = 'ACTIVE' WHERE role = 'STUDENT' AND status = 'PENDING_APPROVAL';
UPDATE class_groups SET register_status = 'VERIFIED', register_verified_at = COALESCE(register_verified_at, now()),
    register_verification_note = COALESCE(register_verification_note, 'Activated automatically');

-- Each course carries its own settings, edited by the admin.
ALTER TABLE courses
    ADD COLUMN lecturer_name TEXT,
    ADD COLUMN credit_hours INT CHECK (credit_hours BETWEEN 0 AND 20),
    ADD COLUMN class_minutes INT NOT NULL DEFAULT 120 CHECK (class_minutes BETWEEN 10 AND 480),
    ADD COLUMN geofence_m INT NOT NULL DEFAULT 50 CHECK (geofence_m BETWEEN 10 AND 1000);

UPDATE courses c SET lecturer_name = sub.name FROM (
    SELECT a.course_id, string_agg(trim(coalesce(l.title || ' ', '') || l.full_name), ', ') AS name
    FROM course_assignments a JOIN lecturers l ON l.id = a.lecturer_id GROUP BY a.course_id) sub
WHERE sub.course_id = c.id;

UPDATE courses c SET class_minutes = sub.mins FROM (
    SELECT course_id, max(EXTRACT(EPOCH FROM (end_time - start_time)) / 60)::int AS mins FROM class_timetables GROUP BY course_id) sub
WHERE sub.course_id = c.id AND sub.mins BETWEEN 10 AND 480;
