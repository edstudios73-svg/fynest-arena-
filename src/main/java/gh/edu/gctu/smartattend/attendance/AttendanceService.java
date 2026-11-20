package gh.edu.gctu.smartattend.attendance;

import gh.edu.gctu.smartattend.common.*;
import gh.edu.gctu.smartattend.face.FaceService;
import gh.edu.gctu.smartattend.security.*;
import gh.edu.gctu.smartattend.student.StudentDirectory;
import gh.edu.gctu.smartattend.webauthn.WebAuthnService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class AttendanceService {
    private static final Set<String> MANUAL_STATUSES = Set.of("PRESENT", "LATE", "ABSENT", "EXCUSED", "REJECTED");
    private static final char[] REF_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final Db db;
    private final AcademicCalendar calendar;
    private final CourseAccess access;
    private final ChallengeService challenges;
    private final PresenceService presence;
    private final FaceService face;
    private final WebAuthnService webauthn;
    private final SettingsService settings;
    private final AuditService audit;
    private final NotificationService notifications;
    private final FraudService fraud;
    private final LiveBroker live;
    private final RateLimiter limiter;
    private org.springframework.transaction.support.TransactionTemplate tx;

    @org.springframework.beans.factory.annotation.Autowired
    void setTx(org.springframework.transaction.PlatformTransactionManager tm) {
        this.tx = new org.springframework.transaction.support.TransactionTemplate(tm);
    }

    public AttendanceService(Db db, AcademicCalendar calendar, CourseAccess access, ChallengeService challenges,
                             PresenceService presence, FaceService face, WebAuthnService webauthn, SettingsService settings,
                             AuditService audit, NotificationService notifications, FraudService fraud, LiveBroker live,
                             RateLimiter limiter) {
        this.db = db;
        this.calendar = calendar;
        this.access = access;
        this.challenges = challenges;
        this.presence = presence;
        this.face = face;
        this.webauthn = webauthn;
        this.settings = settings;
        this.audit = audit;
        this.notifications = notifications;
        this.fraud = fraud;
        this.live = live;
        this.limiter = limiter;
    }

    // ===================================================================== Lecturer side

    public record StartRequest(UUID courseId, LocalDate date, Integer durationMinutes, Integer lateAfterMinutes,
                               String mode, String proximityPolicy, Boolean requireFace, Boolean requirePasskey, String notes,
                               Integer classMinutes, Integer checkoutMinutes, Integer geofenceMeters,
                               Double latitude, Double longitude, Double accuracy) {}

    @Transactional
    public Map<String, Object> start(CurrentUser u, StartRequest r) {
        if (r.courseId() == null) throw ApiException.badRequest("COURSE_REQUIRED", "Select a course.");
        access.requireCourse(u, r.courseId());
        UUID semesterId = calendar.currentSemesterId();
        LocalDate date = r.date() == null ? calendar.today() : r.date();
        if (date.isAfter(calendar.today()) || date.isBefore(calendar.today().minusDays(1))) {
            throw ApiException.badRequest("INVALID_DATE", "Attendance can only be taken for today.");
        }
        Map<String, Object> slot = calendar.slotFor(r.courseId(), date);
        UUID groupId = slot != null ? (UUID) slot.get("classGroupId") : db.scalar("""
                SELECT class_group_id FROM class_timetables WHERE course_id = :c AND semester_id = :s LIMIT 1""",
                UUID.class, "c", r.courseId(), "s", semesterId);
        if (groupId == null) throw ApiException.conflict("NO_CLASS", "This course has no class assigned in the current semester.");
        Map<String, Object> courseCfg = db.one("SELECT class_minutes, geofence_m FROM courses WHERE id = :c", "c", r.courseId());
        String mode = r.mode() != null ? r.mode() : slot != null ? (String) slot.get("mode") : "IN_PERSON";
        if (!Set.of("IN_PERSON", "ONLINE").contains(mode)) throw ApiException.badRequest("INVALID_MODE", "Invalid mode.");
        UUID venueId = "ONLINE".equals(mode) ? null : slot != null ? (UUID) slot.get("venueId") : null;
        String prox = "ONLINE".equals(mode) ? "NONE" : r.proximityPolicy() != null ? r.proximityPolicy() : settings.get("proximity.defaultPolicy");
        if (!Set.of("NONE", "EVIDENCE", "STRICT", "GEOFENCE").contains(prox)) throw ApiException.badRequest("INVALID_POLICY", "Invalid proximity policy.");
        int duration = r.classMinutes() != null ? r.classMinutes() : r.durationMinutes() != null ? r.durationMinutes()
                : courseCfg != null && courseCfg.get("classMinutes") != null ? ((Number) courseCfg.get("classMinutes")).intValue() : settings.getInt("attendance.classMinutes");
        if (duration < 2 || duration > 480) throw ApiException.badRequest("INVALID_DURATION", "Class length must be between 2 and 480 minutes.");
        int checkout = r.checkoutMinutes() != null ? r.checkoutMinutes() : settings.getInt("attendance.checkoutMinutes");
        if (checkout < 0 || checkout > 60 || checkout >= duration) throw ApiException.badRequest("INVALID_CHECKOUT", "Invalid end-of-class confirmation window.");
        int geofence = r.geofenceMeters() != null ? r.geofenceMeters()
                : courseCfg != null && courseCfg.get("geofenceM") != null ? ((Number) courseCfg.get("geofenceM")).intValue() : settings.getInt("attendance.geofenceMeters");
        if (geofence < 10 || geofence > 1000) throw ApiException.badRequest("INVALID_RADIUS", "Distance must be 10–1000 m.");
        if ("GEOFENCE".equals(prox) && (r.latitude() == null || r.longitude() == null)) {
            throw ApiException.badRequest("LOCATION_REQUIRED", "Turn on location on this device to start attendance.");
        }
        int late = r.lateAfterMinutes() != null ? r.lateAfterMinutes() : settings.getInt("attendance.lateAfterMinutes");
        if (late < 0 || late > 240) throw ApiException.badRequest("INVALID_LATE", "Invalid late threshold.");
        // Security layers can only be relaxed by administrators, never by lecturers.
        boolean reqFace = settings.getBool("security.faceOnAttendance");
        boolean reqPasskey = settings.getBool("security.passkeyOnAttendance");
        if (u.role().isAdmin()) {
            if (r.requireFace() != null) reqFace = r.requireFace();
            if (r.requirePasskey() != null) reqPasskey = r.requirePasskey();
        }
        UUID id;
        try {
            id = db.insertReturningId("""
                    INSERT INTO attendance_sessions(course_id, class_group_id, semester_id, timetable_id, started_by, session_date, mode,
                        venue_id, closes_at, ends_at, checkout_minutes, geofence_m, anchor_lat, anchor_lon, anchor_accuracy, anchor_at,
                        late_after_minutes, require_face, require_passkey, proximity_policy, notes)
                    VALUES (:c, :g, :sem, :tt, :u, :d, :m, :v, now() + make_interval(mins => :dur + :co), now() + make_interval(mins => :dur),
                        :co, :gf, :lat, :lon, :acc, CASE WHEN CAST(:lat AS double precision) IS NULL THEN NULL ELSE now() END,
                        :late, :rf, :rp, :prox, :n)
                    RETURNING id""",
                    "c", r.courseId(), "g", groupId, "sem", semesterId, "tt", slot == null ? null : slot.get("timetableId"),
                    "u", u.userId(), "d", date, "m", mode, "v", venueId, "dur", duration, "co", checkout, "gf", geofence,
                    "lat", r.latitude(), "lon", r.longitude(), "acc", r.accuracy(), "late", late,
                    "rf", reqFace, "rp", reqPasskey, "prox", prox, "n", StudentAuthHelpers.clip(r.notes(), 300));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("SESSION_ALREADY_OPEN", "An attendance session is already open for this course.");
        }
        Map<String, Object> course = db.one("SELECT code, title FROM courses WHERE id = :c", "c", r.courseId());
        notifications.notifyCourseStudents(r.courseId(), semesterId, "Attendance open: " + course.get("title"),
                "Your lecturer has started attendance" + (course.get("code") != null ? " for " + course.get("code") : "") + ".",
                "/student.html#/attend");
        audit.log("ATTENDANCE_SESSION_STARTED", "attendance_session", id,
                Json.obj("courseId", r.courseId(), "mode", mode, "durationMinutes", duration, "proximity", prox,
                        "requireFace", reqFace, "requirePasskey", reqPasskey));
        return Json.obj("sessionId", id);
    }

    /** The admin's live screen reports its location so the geofence follows the admin's device. */
    public Map<String, Object> updateAnchor(CurrentUser u, UUID sessionId, PresenceService.Gps gps) {
        access.requireSession(u, sessionId);
        if (gps == null || gps.lat() == null || gps.lon() == null || Math.abs(gps.lat()) > 90 || Math.abs(gps.lon()) > 180) {
            throw ApiException.badRequest("INVALID_LOCATION", "Invalid location.");
        }
        db.update("""
                UPDATE attendance_sessions SET anchor_lat = :lat, anchor_lon = :lon, anchor_accuracy = :acc, anchor_at = now()
                WHERE id = :id AND status = 'OPEN'""", "lat", gps.lat(), "lon", gps.lon(), "acc", gps.accuracy(), "id", sessionId);
        return Json.obj("updated", true);
    }

    @Transactional
    public Map<String, Object> close(CurrentUser u, UUID sessionId, boolean system) {
        Map<String, Object> s = system ? db.one("SELECT * FROM attendance_sessions WHERE id = :id", "id", sessionId) : access.requireSession(u, sessionId);
        int n = db.update("""
                UPDATE attendance_sessions SET status = 'CLOSED', closed_at = now(), closed_by = :u WHERE id = :id AND status = 'OPEN'""",
                "u", u == null ? null : u.userId(), "id", sessionId);
        if (n == 0) throw ApiException.conflict("SESSION_CLOSED", "This attendance session is already closed.");
        challenges.revokeAll(sessionId);
        db.update("UPDATE attendance_attempts SET status = 'EXPIRED' WHERE session_id = :s AND status = 'IN_PROGRESS'", "s", sessionId);
        int leftEarly = db.update("""
                UPDATE attendance_records SET status = 'ABSENT', updated_at = now(),
                    reason = 'Checked in but did not confirm presence at the end of class'
                WHERE session_id = :s AND status = 'CHECKED_IN'""", "s", sessionId);
        // Enrolled students without a record are explicitly marked ABSENT.
        List<Map<String, Object>> absent = db.list("""
                SELECT st.id FROM course_enrollments e JOIN students st ON st.id = e.student_id
                WHERE e.course_id = :c AND e.semester_id = :sem AND e.status = 'ENROLLED' AND st.class_group_id = :g
                  AND st.register_status = 'ACTIVE'
                  AND NOT EXISTS (SELECT 1 FROM attendance_records r WHERE r.session_id = :s AND r.student_id = st.id)""",
                "c", s.get("courseId"), "sem", s.get("semesterId"), "g", s.get("classGroupId"), "s", sessionId);
        for (var a : absent) {
            db.update("""
                    INSERT INTO attendance_records(session_id, student_id, status, verification_status, reference_no)
                    VALUES (:s, :st, 'ABSENT', 'AUTO_ABSENT', :ref) ON CONFLICT (session_id, student_id) DO NOTHING""",
                    "s", sessionId, "st", a.get("id"), "ref", referenceNo());
        }
        if (system) audit.logAs(null, "SYSTEM", "ATTENDANCE_SESSION_AUTO_CLOSED", "attendance_session", sessionId, Json.obj("absent", absent.size()));
        else audit.log("ATTENDANCE_SESSION_CLOSED", "attendance_session", sessionId, Json.obj("absent", absent.size()));
        live.publish(sessionId, "closed");
        return Json.obj("closed", true, "markedAbsent", absent.size(), "leftEarly", leftEarly);
    }

    @Transactional
    public Map<String, Object> cancel(CurrentUser u, UUID sessionId, String reason) {
        access.requireSession(u, sessionId);
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("REASON_REQUIRED", "A reason is required to cancel a session.");
        int n = db.update("UPDATE attendance_sessions SET status = 'CANCELLED', closed_at = now(), closed_by = :u, notes = :r WHERE id = :id AND status = 'OPEN'",
                "u", u.userId(), "r", StudentAuthHelpers.clip(reason, 300), "id", sessionId);
        if (n == 0) throw ApiException.conflict("SESSION_CLOSED", "Only open sessions can be cancelled.");
        challenges.revokeAll(sessionId);
        audit.log("ATTENDANCE_SESSION_CANCELLED", "attendance_session", sessionId, Json.obj("reason", reason));
        live.publish(sessionId, "closed");
        return Json.obj("cancelled", true);
    }

    private volatile long lastHousekeeping = 0;

    /**
     * Runs housekeeping at most every 10 s per instance. Called from request paths as well as the
     * scheduler, because serverless instances (e.g. Vercel) may be paused between requests.
     */
    public void housekeepingIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastHousekeeping < 10_000) return;
        lastHousekeeping = now;
        autoClose();
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    public void autoClose() {
        lastHousekeeping = System.currentTimeMillis();
        for (var s : db.list("""
                UPDATE attendance_sessions SET checkout_notified = true
                WHERE status = 'OPEN' AND checkout_minutes > 0 AND NOT checkout_notified
                  AND now() >= ends_at - make_interval(mins => checkout_minutes)
                RETURNING id, course_id""")) {
            db.update("""
                    INSERT INTO notifications(user_id, title, body, link)
                    SELECT st.user_id, 'Confirm you are still in class', 'Tap to confirm before the session closes.', '/student.html#/'
                    FROM attendance_records r JOIN students st ON st.id = r.student_id
                    WHERE r.session_id = :s AND r.status = 'CHECKED_IN' AND st.user_id IS NOT NULL""", "s", s.get("id"));
            live.publish((UUID) s.get("id"), "checkout");
        }
        challenges.purgeExpiredDisplays();
        for (var s : db.list("SELECT id FROM attendance_sessions WHERE status = 'OPEN' AND closes_at <= now()")) {
            try { tx.executeWithoutResult(st -> close(null, (UUID) s.get("id"), true)); } catch (ApiException ignored) { }
        }
        db.update("UPDATE attendance_attempts SET status = 'EXPIRED' WHERE status = 'IN_PROGRESS' AND expires_at < now()");
        db.update("DELETE FROM auth_flows WHERE expires_at < now() - interval '1 day'");
        db.update("UPDATE auth_sessions SET revoked_at = now() WHERE revoked_at IS NULL AND expires_at < now()");
    }

    /** Lecturer/admin sets a record manually (approved exception, supervised verification, rejection). */
    @Transactional
    public Map<String, Object> manualMark(CurrentUser u, UUID sessionId, UUID studentId, String status, String reason) {
        Map<String, Object> s = access.requireSession(u, sessionId);
        if (!MANUAL_STATUSES.contains(status)) throw ApiException.badRequest("INVALID_STATUS", "Invalid attendance status.");
        if (reason == null || reason.trim().length() < 3) throw ApiException.badRequest("REASON_REQUIRED", "A reason is required for manual changes.");
        if ("CANCELLED".equals(s.get("status"))) throw ApiException.conflict("SESSION_CANCELLED", "This session was cancelled.");
        Integer enrolled = db.scalar("""
                SELECT 1 FROM course_enrollments e JOIN students st ON st.id = e.student_id
                WHERE e.course_id = :c AND e.student_id = :st AND e.semester_id = :sem AND e.status = 'ENROLLED'""",
                Integer.class, "c", s.get("courseId"), "st", studentId, "sem", s.get("semesterId"));
        if (enrolled == null) throw ApiException.badRequest("NOT_ENROLLED", "This student is not enrolled in the course.");
        Map<String, Object> before = db.one("SELECT status, verification_status FROM attendance_records WHERE session_id = :s AND student_id = :st",
                "s", sessionId, "st", studentId);
        db.update("""
                INSERT INTO attendance_records(session_id, student_id, status, verification_status, reference_no, updated_by, reason)
                VALUES (:s, :st, :status, 'MANUAL', :ref, :u, :r)
                ON CONFLICT (session_id, student_id) DO UPDATE SET status = EXCLUDED.status, verification_status = 'MANUAL',
                    updated_by = EXCLUDED.updated_by, reason = EXCLUDED.reason, updated_at = now()""",
                "s", sessionId, "st", studentId, "status", status, "ref", referenceNo(), "u", u.userId(), "r", StudentAuthHelpers.clip(reason, 500));
        audit.log("ATTENDANCE_MANUAL_CHANGE", "attendance_record", sessionId + ":" + studentId,
                Json.obj("before", before, "after", status, "reason", reason));
        UUID studentUser = db.scalar("SELECT user_id FROM students WHERE id = :s", UUID.class, "s", studentId);
        notifications.notify(studentUser, "Attendance updated", "Your attendance record was updated to " + status + ".", "/student.html#/history");
        live.publish(sessionId, "record");
        return Json.obj("updated", true, "status", status);
    }

    // ===================================================================== Student side

    public List<Map<String, Object>> activeForStudent(UUID studentId) {
        housekeepingIfDue();
        return db.list("""
                SELECT s.id AS session_id, s.mode, s.opened_at, s.closes_at, s.ends_at, s.checkout_minutes, s.session_date,
                       s.require_face, s.require_passkey, s.proximity_policy, c.id AS course_id, c.code AS course_code,
                       c.title AS course_title, v.code AS venue, r.status AS my_status, r.reference_no,
                       (s.ends_at - make_interval(mins => s.checkout_minutes)) AS checkout_from,
                       (now() >= s.ends_at - make_interval(mins => s.checkout_minutes)) AS checkout_open,
                       (now() < s.ends_at) AS checkin_open
                FROM attendance_sessions s
                JOIN courses c ON c.id = s.course_id
                JOIN course_enrollments e ON e.course_id = s.course_id AND e.semester_id = s.semester_id AND e.student_id = :st AND e.status = 'ENROLLED'
                JOIN students st ON st.id = :st AND st.class_group_id = s.class_group_id
                LEFT JOIN venues v ON v.id = s.venue_id
                LEFT JOIN attendance_records r ON r.session_id = s.id AND r.student_id = :st
                WHERE s.status = 'OPEN' AND s.closes_at > now() ORDER BY s.opened_at DESC""", "st", studentId);
    }

    public record BeginRequest(UUID sessionId, String otp, String qrToken, String indexNumber) {
        public BeginRequest(UUID sessionId, String otp, String qrToken) { this(sessionId, otp, qrToken, null); }
    }

    /**
     * Step 1: the student confirms their index number for the chosen course; an attempt is opened and the
     * face check issued. (A code/QR may also be supplied here; otherwise it is entered after the face check.)
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> begin(CurrentUser u, BeginRequest r) {
        UUID studentId = requireActiveStudent(u);
        limiter.check("attend-begin", studentId.toString(), 20, 300);
        UUID sessionId = r.sessionId();
        boolean hasQr = r.qrToken() != null && !r.qrToken().isBlank();
        if (hasQr) {
            // A QR scanned with the phone camera opens the app: it identifies the session (re-checked later).
            ChallengeService.Valid q;
            try {
                q = challenges.validateQr(r.qrToken().trim());
            } catch (ApiException e) {
                event(sessionId, studentId, null, "QR_FAIL", false, Json.obj("reason", e.code()));
                throw e;
            }
            if (sessionId != null && !sessionId.equals(q.sessionId())) {
                event(sessionId, studentId, null, "QR_FAIL", false, Json.obj("reason", "WRONG_SESSION"));
                fraud.raise("QR_SESSION_MISMATCH", "LOW", studentId, sessionId, u.deviceId(), Json.obj("qrSession", q.sessionId()));
                throw ApiException.badRequest("WRONG_COURSE", "This QR code is for a different course.");
            }
            sessionId = q.sessionId();
        }
        if (sessionId == null) throw ApiException.badRequest("SESSION_REQUIRED", "Choose your course first.");
        if (r.indexNumber() != null) {
            String mine = db.scalar("SELECT upper(index_number) FROM students WHERE id = :s", String.class, "s", studentId);
            if (!StudentDirectory.normaliseIndex(r.indexNumber()).equals(mine)) {
                event(sessionId, studentId, null, "INDEX_MISMATCH", false, Json.obj());
                throw ApiException.badRequest("INDEX_MISMATCH", "That index number doesn't match your account.");
            }
        }
        Map<String, Object> s = sessionForStudent(sessionId, studentId);
        if (s.get("endsAt") != null && ((Instant) s.get("endsAt")).isBefore(Instant.now())) {
            throw ApiException.conflict("CHECKIN_CLOSED", "Check-in for this class has closed.");
        }
        if (db.scalar("SELECT 1 FROM attendance_records WHERE session_id = :s AND student_id = :st", Integer.class, "s", sessionId, "st", studentId) != null) {
            throw ApiException.conflict("ALREADY_RECORDED", "You're already checked in for this class.");
        }
        db.update("UPDATE attendance_attempts SET status = 'EXPIRED' WHERE session_id = :s AND student_id = :st AND status = 'IN_PROGRESS'",
                "s", sessionId, "st", studentId);
        byte[] wa = Crypto.randomBytes(32);
        UUID attemptId = db.insertReturningId("""
                INSERT INTO attendance_attempts(session_id, student_id, device_id, webauthn_challenge, expires_at)
                VALUES (:s, :st, :d, :wa, now() + interval '5 minutes') RETURNING id""",
                "s", sessionId, "st", studentId, "d", u.deviceId(), "wa", wa);
        event(sessionId, studentId, attemptId, "INDEX_OK", true, Json.obj());
        boolean codeDone = false;
        if (hasQr || (r.otp() != null && !r.otp().isBlank())) {
            applyCode(u, studentId, attemptId, sessionId, r.otp(), r.qrToken());
            codeDone = true;
        }
        boolean needFace = Boolean.TRUE.equals(s.get("requireFace"));
        boolean needPasskey = Boolean.TRUE.equals(s.get("requirePasskey"));
        boolean geo = "GEOFENCE".equals(s.get("proximityPolicy")) && !"ONLINE".equals(s.get("mode"));
        Map<String, Object> out = Json.obj("attemptId", attemptId, "sessionId", sessionId,
                "course", Json.obj("code", s.get("courseCode"), "title", s.get("courseTitle")),
                "mode", s.get("mode"), "proximityPolicy", s.get("proximityPolicy"),
                "requiresFace", needFace, "requiresPasskey", needPasskey, "codeDone", codeDone,
                "wantsLocation", geo || (!"ONLINE".equals(s.get("mode")) && !"NONE".equals(s.get("proximityPolicy")) && Boolean.TRUE.equals(s.get("venueHasGps"))),
                "requiresLocation", geo, "endsAt", s.get("endsAt"), "checkoutMinutes", s.get("checkoutMinutes"));
        if (needFace) out.put("faceChallenge", face.createChallenge(studentId, "ATTENDANCE"));
        if (needPasskey) out.put("passkey", webauthn.requestOptions(u.userId(), wa));
        live.publish(sessionId, "attempt");
        return out;
    }

    /** Step 3: the code shown on the board, or the QR code (after the face check). */
    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> verifyCode(CurrentUser u, UUID attemptId, String otp, String qrToken) {
        UUID studentId = requireActiveStudent(u);
        Map<String, Object> a = attempt(attemptId, studentId);
        UUID sessionId = (UUID) a.get("sessionId");
        Boolean needFace = db.scalar("SELECT require_face FROM attendance_sessions WHERE id = :s", Boolean.class, "s", sessionId);
        if (Boolean.TRUE.equals(needFace) && a.get("faceOkAt") == null) throw ApiException.badRequest("FACE_REQUIRED", "Scan your face first.");
        applyCode(u, studentId, attemptId, sessionId, otp, qrToken);
        return Json.obj("passed", true);
    }

    private void applyCode(CurrentUser u, UUID studentId, UUID attemptId, UUID sessionId, String otp, String qrToken) {
        ChallengeService.Valid valid;
        boolean viaQr = qrToken != null && !qrToken.isBlank();
        if (viaQr) {
            try {
                valid = challenges.validateQr(qrToken.trim());
            } catch (ApiException e) {
                event(sessionId, studentId, attemptId, "QR_FAIL", false, Json.obj("reason", e.code()));
                throw e;
            }
            if (!sessionId.equals(valid.sessionId())) {
                event(sessionId, studentId, attemptId, "QR_FAIL", false, Json.obj("reason", "WRONG_SESSION"));
                fraud.raise("QR_SESSION_MISMATCH", "LOW", studentId, sessionId, u.deviceId(), Json.obj("qrSession", valid.sessionId()));
                throw ApiException.badRequest("WRONG_COURSE", "This QR code is for a different course.");
            }
        } else {
            int max = settings.getInt("attendance.maxOtpFailuresPerSession");
            Integer fails = db.scalar("""
                    SELECT count(*)::int FROM attendance_verification_events WHERE session_id = :s AND student_id = :st AND event_type = 'OTP_FAIL'""",
                    Integer.class, "s", sessionId, "st", studentId);
            if (fails != null && fails >= max) {
                throw ApiException.tooMany("Too many wrong codes. See the admin.");
            }
            try {
                valid = challenges.validateOtp(sessionId, otp);
            } catch (ApiException e) {
                event(sessionId, studentId, attemptId, "OTP_FAIL", false, Json.obj("reason", e.code()));
                if (fails != null && fails + 1 >= Math.max(3, max - 1)) {
                    fraud.raise("REPEATED_INVALID_OTP", "MEDIUM", studentId, sessionId, u.deviceId(), Json.obj("failures", fails + 1));
                }
                live.publish(sessionId, "failure");
                throw e;
            }
        }
        db.update("UPDATE attendance_attempts SET challenge_id = :c, challenge_ok_at = now(), method = :m WHERE id = :id",
                "c", valid.challengeId(), "m", viaQr ? "QR" : "OTP", "id", attemptId);
        event(sessionId, studentId, attemptId, viaQr ? "QR_OK" : "OTP_OK", true, Json.obj());
    }

    /** Step 2: live face verification bound to this attempt. */
    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> verifyFace(CurrentUser u, UUID attemptId, UUID challengeId, List<byte[]> frames) {
        UUID studentId = requireActiveStudent(u);
        Map<String, Object> a = attempt(attemptId, studentId);
        FaceService.Outcome o = face.verify(studentId, challengeId, "ATTENDANCE", frames);
        UUID sessionId = (UUID) a.get("sessionId");
        if (!o.passed()) {
            event(sessionId, studentId, attemptId, o.failure().startsWith("LIVENESS") ? "LIVENESS_FAIL" : "FACE_FAIL", false,
                    Json.obj("reason", o.failure()));
            live.publish(sessionId, "failure");
            Map<String, Object> retry = face.createChallenge(studentId, "ATTENDANCE");
            return Json.obj("passed", false, "reason", o.failure(), "message", o.message(), "faceChallenge", retry);
        }
        db.update("UPDATE attendance_attempts SET face_ok_at = now(), face_challenge_id = :c WHERE id = :id", "c", challengeId, "id", attemptId);
        event(sessionId, studentId, attemptId, "FACE_OK", true, Json.obj("score", Math.round(o.score() * 1000) / 1000.0));
        return Json.obj("passed", true);
    }

    /** Step 3: device passkey (fingerprint / Face ID / Windows Hello on the registered device). */
    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> verifyPasskey(CurrentUser u, UUID attemptId, String credentialJson) {
        UUID studentId = requireActiveStudent(u);
        Map<String, Object> a = attempt(attemptId, studentId);
        byte[] wa = (byte[]) a.get("webauthnChallenge");
        if (wa == null) throw ApiException.badRequest("PASSKEY_USED", "Passkey step already completed or expired. Start again.");
        db.update("UPDATE attendance_attempts SET webauthn_challenge = NULL WHERE id = :id", "id", attemptId);
        UUID sessionId = (UUID) a.get("sessionId");
        try {
            webauthn.authenticate(u.userId(), wa, credentialJson);
        } catch (ApiException e) {
            event(sessionId, studentId, attemptId, "PASSKEY_FAIL", false, Json.obj("reason", e.code()));
            live.publish(sessionId, "failure");
            throw e;
        }
        db.update("UPDATE attendance_attempts SET passkey_ok_at = now() WHERE id = :id", "id", attemptId);
        event(sessionId, studentId, attemptId, "PASSKEY_OK", true, Json.obj());
        return Json.obj("passed", true);
    }

    /** Step 4: final server-side validation and atomic record creation. */
    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> submit(CurrentUser u, UUID attemptId, PresenceService.Gps gps, String ip) {
        UUID studentId = requireActiveStudent(u);
        Map<String, Object> a = db.one("""
                SELECT a.*, a.expires_at > now() AS live FROM attendance_attempts a WHERE a.id = :id AND a.student_id = :st FOR UPDATE""",
                "id", attemptId, "st", studentId);
        if (a == null) throw ApiException.notFound("ATTEMPT_NOT_FOUND", "Attendance attempt not found. Start again.");
        UUID sessionId = (UUID) a.get("sessionId");
        Map<String, Object> s = db.one("""
                SELECT s.*, c.code AS course_code, c.title AS course_title,
                       now() > s.opened_at + make_interval(mins => s.late_after_minutes) AS is_late
                FROM attendance_sessions s JOIN courses c ON c.id = s.course_id WHERE s.id = :id FOR SHARE OF s""", "id", sessionId);
        if (!"OPEN".equals(s.get("status"))) throw ApiException.conflict("SESSION_CLOSED", "This attendance session has closed.");
        if (!"IN_PROGRESS".equals(a.get("status")) || !Boolean.TRUE.equals(a.get("live"))) {
            throw ApiException.badRequest("ATTEMPT_EXPIRED", "This attendance attempt expired. Please start again with the current code.");
        }
        if (a.get("challengeOkAt") == null) throw ApiException.badRequest("CODE_REQUIRED", "Scan the QR code or enter the code first.");
        if (Boolean.TRUE.equals(s.get("requireFace")) && a.get("faceOkAt") == null) throw ApiException.badRequest("FACE_REQUIRED", "Facial verification is required.");
        if (Boolean.TRUE.equals(s.get("requirePasskey")) && a.get("passkeyOkAt") == null) throw ApiException.badRequest("PASSKEY_REQUIRED", "Device verification is required.");

        PresenceService.Verdict v = presence.evaluate(s, ip, gps);
        String policy = (String) s.get("proximityPolicy");
        if ("GEOFENCE".equals(policy) && !"ONLINE".equals(s.get("mode")) && !v.positive()) {
            event(sessionId, studentId, attemptId, "PRESENCE_FAIL", false, Json.obj("checks", v.checks()));
            live.publish(sessionId, "failure");
            throw new ApiException(HttpStatus.FORBIDDEN, "OUT_OF_RANGE", PresenceService.geofenceMessage(v));
        }
        if ("STRICT".equals(policy) && !v.positive()) {
            event(sessionId, studentId, attemptId, "PRESENCE_FAIL", false, Json.obj("checks", v.checks()));
            live.publish(sessionId, "failure");
            throw new ApiException(HttpStatus.FORBIDDEN, "PRESENCE_FAILED",
                    "Classroom verification failed. Connect to the classroom network / enable location, or ask your lecturer for supervised verification.");
        }
        boolean late = Boolean.TRUE.equals(s.get("isLate"));
        boolean needsCheckout = s.get("checkoutMinutes") != null && ((Number) s.get("checkoutMinutes")).intValue() > 0;
        String status = needsCheckout ? "CHECKED_IN" : late ? "LATE" : "PRESENT";
        String verification = "VERIFIED";
        List<String> flags = new ArrayList<>();
        if (v.inconsistent()) {
            flags.add("LOCATION_INCONSISTENT");
            fraud.raise("LOCATION_INCONSISTENT", "MEDIUM", studentId, sessionId, u.deviceId(), Json.obj("checks", v.checks()));
        }
        // Same physical browser used for another student in this session?
        List<Map<String, Object>> sameDevice = u.deviceId() == null ? List.of() : db.list("""
                SELECT st.index_number, r.id FROM attendance_records r JOIN devices d ON d.id = r.device_id
                JOIN students st ON st.id = r.student_id
                WHERE r.session_id = :s AND r.student_id <> :st
                  AND d.device_cookie_hash = (SELECT device_cookie_hash FROM devices WHERE id = :d)""",
                "s", sessionId, "st", studentId, "d", u.deviceId());
        if (!sameDevice.isEmpty()) {
            flags.add("SHARED_DEVICE");
            fraud.raise("SAME_DEVICE_MULTIPLE_STUDENTS", "HIGH", studentId, sessionId, u.deviceId(),
                    Json.obj("otherStudents", sameDevice.stream().map(x -> x.get("indexNumber")).toList()));
        }
        if (!flags.isEmpty()) { status = "PENDING_VERIFICATION"; verification = "FLAGGED"; }

        String ref = referenceNo();
        Map<String, Object> evidence = Json.obj("presence", v.result(), "checks", v.checks(), "method", a.get("method"),
                "face", a.get("faceOkAt") != null, "passkey", a.get("passkeyOkAt") != null, "flags", flags);
        Map<String, Object> rec = db.one("""
                INSERT INTO attendance_records(session_id, student_id, status, verification_status, reference_no, attempt_id, device_id,
                    presence_evidence, checked_in_late)
                VALUES (:s, :st, :status, :vs, :ref, :a, :d, CAST(:ev AS jsonb), :late)
                ON CONFLICT (session_id, student_id) DO NOTHING
                RETURNING id, submitted_at""",
                "s", sessionId, "st", studentId, "status", status, "vs", verification, "ref", ref, "a", attemptId,
                "d", u.deviceId(), "ev", db.json(evidence), "late", late);
        if (rec == null) throw ApiException.conflict("ALREADY_RECORDED", "Your attendance for this session is already recorded.");
        db.update("UPDATE attendance_attempts SET status = 'COMPLETED' WHERE id = :id", "id", attemptId);
        event(sessionId, studentId, attemptId, "SUBMITTED", true, Json.obj("status", status, "presence", v.result()));

        Map<String, Object> st = db.one("SELECT index_number, first_name, last_name, other_names FROM students WHERE id = :s", "s", studentId);
        notifications.notify(u.userId(), "Attendance recorded", status + " · " + s.get("courseTitle") + " · Ref " + ref, "/student.html#/history");
        live.publish(sessionId, "record");
        return Json.obj("recorded", true, "status", status, "verificationStatus", verification, "referenceNo", ref,
                "studentName", StudentDirectory.fullName(st), "indexNumber", st.get("indexNumber"),
                "courseCode", s.get("courseCode"), "courseTitle", s.get("courseTitle"), "sessionDate", s.get("sessionDate"),
                "submittedAt", rec.get("submittedAt"), "flags", flags, "endsAt", s.get("endsAt"),
                "checkoutFrom", needsCheckout ? ((Instant) s.get("endsAt")).minusSeconds(((Number) s.get("checkoutMinutes")).longValue() * 60) : null);
    }

    // ===================================================================== End-of-class confirmation

    private Map<String, Object> checkoutContext(UUID sessionId, UUID studentId) {
        Map<String, Object> c = db.one("""
                SELECT s.*, r.status AS my_status, r.checkout_face_at, r.checked_in_late, r.reference_no,
                       c.code AS course_code, c.title AS course_title,
                       now() >= s.ends_at - make_interval(mins => s.checkout_minutes) AS window_open
                FROM attendance_sessions s JOIN courses c ON c.id = s.course_id
                LEFT JOIN attendance_records r ON r.session_id = s.id AND r.student_id = :st WHERE s.id = :id""",
                "id", sessionId, "st", studentId);
        if (c == null) throw ApiException.notFound("SESSION_NOT_FOUND", "Class not found.");
        if (!"OPEN".equals(c.get("status"))) throw ApiException.conflict("SESSION_CLOSED", "This class has closed.");
        if (!"CHECKED_IN".equals(c.get("myStatus"))) throw ApiException.conflict("NOT_CHECKED_IN", "You're not checked in to this class.");
        if (!Boolean.TRUE.equals(c.get("windowOpen"))) {
            throw ApiException.conflict("TOO_EARLY", "You can confirm in the last " + c.get("checkoutMinutes") + " minutes of class.");
        }
        return c;
    }

    /** End of class, step 1: face check (so a phone left with a friend can't confirm). */
    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> checkoutStart(CurrentUser u, UUID sessionId) {
        UUID studentId = requireActiveStudent(u);
        checkoutContext(sessionId, studentId);
        boolean needFace = settings.getBool("security.faceOnCheckout");
        return Json.obj("requiresFace", needFace, "faceChallenge", needFace ? face.createChallenge(studentId, "ATTENDANCE") : null);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> checkoutFace(CurrentUser u, UUID sessionId, UUID challengeId, List<byte[]> frames) {
        UUID studentId = requireActiveStudent(u);
        checkoutContext(sessionId, studentId);
        FaceService.Outcome o = face.verify(studentId, challengeId, "ATTENDANCE", frames);
        if (!o.passed()) {
            event(sessionId, studentId, null, "CHECKOUT_FACE_FAIL", false, Json.obj("reason", o.failure()));
            return Json.obj("passed", false, "message", o.message(), "faceChallenge", face.createChallenge(studentId, "ATTENDANCE"));
        }
        db.update("UPDATE attendance_records SET checkout_face_at = now() WHERE session_id = :s AND student_id = :st", "s", sessionId, "st", studentId);
        return Json.obj("passed", true);
    }

    /** End of class, step 2: still within range of the admin's device → attendance counts. */
    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> checkoutConfirm(CurrentUser u, UUID sessionId, PresenceService.Gps gps, String ip) {
        UUID studentId = requireActiveStudent(u);
        Map<String, Object> c = checkoutContext(sessionId, studentId);
        if (settings.getBool("security.faceOnCheckout")) {
            Instant f = (Instant) c.get("checkoutFaceAt");
            if (f == null || f.isBefore(Instant.now().minusSeconds(600))) throw ApiException.badRequest("FACE_REQUIRED", "Scan your face first.");
        }
        PresenceService.Verdict v = presence.evaluate(c, ip, gps);
        if ("GEOFENCE".equals(c.get("proximityPolicy")) && !"ONLINE".equals(c.get("mode")) && !v.positive()) {
            event(sessionId, studentId, null, "CHECKOUT_PRESENCE_FAIL", false, Json.obj("checks", v.checks()));
            throw new ApiException(HttpStatus.FORBIDDEN, "OUT_OF_RANGE", PresenceService.geofenceMessage(v));
        }
        String status = Boolean.TRUE.equals(c.get("checkedInLate")) ? "LATE" : "PRESENT";
        int n = db.update("""
                UPDATE attendance_records SET status = :st, checked_out_at = now(), updated_at = now(), checkout_evidence = CAST(:ev AS jsonb)
                WHERE session_id = :s AND student_id = :sid AND status = 'CHECKED_IN'""",
                "st", status, "ev", db.json(Json.obj("presence", v.result(), "checks", v.checks())), "s", sessionId, "sid", studentId);
        if (n == 0) throw ApiException.conflict("ALREADY_RECORDED", "Already confirmed.");
        event(sessionId, studentId, null, "CHECKOUT_OK", true, Json.obj("status", status));
        notifications.notify(u.userId(), "Attendance marked", status + " · " + c.get("courseTitle"), "/student.html#/history");
        live.publish(sessionId, "record");
        return Json.obj("confirmed", true, "status", status, "referenceNo", c.get("referenceNo"),
                "courseTitle", c.get("courseTitle"), "courseCode", c.get("courseCode"));
    }

    // ===================================================================== helpers

    private UUID requireActiveStudent(CurrentUser u) {
        if (u.studentId() == null) throw ApiException.forbidden("Only students can mark attendance.");
        String st = db.scalar("SELECT status FROM app_users WHERE id = :u", String.class, "u", u.userId());
        if ("PENDING_APPROVAL".equals(st)) {
            throw ApiException.forbidden("Your account is awaiting verification by the department. You cannot mark attendance yet.");
        }
        return u.studentId();
    }

    private Map<String, Object> sessionForStudent(UUID sessionId, UUID studentId) {
        Map<String, Object> s = db.one("""
                SELECT s.*, c.code AS course_code, c.title AS course_title,
                       (v.latitude IS NOT NULL) AS venue_has_gps,
                       EXISTS (SELECT 1 FROM course_enrollments e JOIN students st ON st.id = e.student_id
                               WHERE e.course_id = s.course_id AND e.semester_id = s.semester_id AND e.student_id = :st
                                 AND e.status = 'ENROLLED' AND st.class_group_id = s.class_group_id) AS enrolled
                FROM attendance_sessions s JOIN courses c ON c.id = s.course_id LEFT JOIN venues v ON v.id = s.venue_id
                WHERE s.id = :id""", "id", sessionId, "st", studentId);
        if (s == null) throw ApiException.notFound("SESSION_NOT_FOUND", "Attendance session not found.");
        if (!Boolean.TRUE.equals(s.get("enrolled"))) throw ApiException.forbidden("You are not enrolled in this course.");
        if (!"OPEN".equals(s.get("status"))) throw ApiException.conflict("SESSION_CLOSED", "This attendance session has closed.");
        if (((Instant) s.get("closesAt")).isBefore(Instant.now())) throw ApiException.conflict("SESSION_CLOSED", "This attendance session has closed.");
        return s;
    }

    private Map<String, Object> attempt(UUID attemptId, UUID studentId) {
        Map<String, Object> a = db.one("""
                SELECT a.*, a.expires_at > now() AS live, s.status AS session_status FROM attendance_attempts a
                JOIN attendance_sessions s ON s.id = a.session_id WHERE a.id = :id AND a.student_id = :st""",
                "id", attemptId, "st", studentId);
        if (a == null) throw ApiException.notFound("ATTEMPT_NOT_FOUND", "Attendance attempt not found. Start again.");
        if (!"OPEN".equals(a.get("sessionStatus"))) throw ApiException.conflict("SESSION_CLOSED", "This attendance session has closed.");
        if (!"IN_PROGRESS".equals(a.get("status")) || !Boolean.TRUE.equals(a.get("live"))) {
            throw ApiException.badRequest("ATTEMPT_EXPIRED", "This attempt expired. Please start again with the current code.");
        }
        return a;
    }

    void event(UUID sessionId, UUID studentId, UUID attemptId, String type, boolean ok, Map<String, Object> detail) {
        db.update("""
                INSERT INTO attendance_verification_events(session_id, student_id, attempt_id, event_type, success, detail, ip)
                VALUES (:s, :st, :a, :t, :ok, CAST(:d AS jsonb), :ip)""",
                "s", sessionId, "st", studentId, "a", attemptId, "t", type, "ok", ok, "d", db.json(detail), "ip", AuthContext.ip());
    }

    static String referenceNo() {
        StringBuilder b = new StringBuilder("SA-").append(LocalDate.now(AcademicCalendar.ZONE).format(DateTimeFormatter.ofPattern("yyMMdd"))).append('-');
        for (int i = 0; i < 8; i++) b.append(REF_ALPHABET[Crypto.randomInt(REF_ALPHABET.length)]);
        return b.toString();
    }

    /** Small shared string helper. */
    static final class StudentAuthHelpers {
        static String clip(String s, int n) {
            if (s == null) return null;
            s = s.trim();
            return s.length() > n ? s.substring(0, n) : s;
        }
    }
}
