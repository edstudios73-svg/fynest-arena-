-- Lecture halls are large: default check-in distance 100 m (was 50 m).
ALTER TABLE courses ALTER COLUMN geofence_m SET DEFAULT 100;
ALTER TABLE attendance_sessions ALTER COLUMN geofence_m SET DEFAULT 100;
UPDATE courses SET geofence_m = 100 WHERE geofence_m = 50;
UPDATE system_settings SET value = '100' WHERE key = 'attendance.geofenceMeters' AND value = '50';
