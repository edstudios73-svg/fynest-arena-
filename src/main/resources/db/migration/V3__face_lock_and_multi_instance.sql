-- Face templates are kept for the whole programme by default (5 years), and the face stays locked to the index number.
UPDATE system_settings SET value = '1825' WHERE key = 'biometric.retentionDays' AND value = '730';
UPDATE biometric_enrollments SET retention_until = (created_at + interval '1825 days')::date WHERE status = 'ACTIVE';

-- Serverless/multi-instance hosting: the code shown on the lecturer's screen must be readable by any instance.
-- It is stored encrypted (AES-256-GCM) for display only; students' codes are still verified against the HMAC.
ALTER TABLE attendance_challenges ADD COLUMN display_ciphertext BYTEA, ADD COLUMN display_iv BYTEA;
