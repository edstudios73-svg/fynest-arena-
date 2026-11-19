package gh.edu.gctu.smartattend.common;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Typed access to administrator-configurable policies stored in system_settings. */
@Service
public class SettingsService {
    /** Allowed keys and validators: unknown keys cannot be written. */
    public static final Map<String, String> SCHEMA = new LinkedHashMap<>();
    static {
        SCHEMA.put("attendance.lateAfterMinutes", "int:0:240");
        SCHEMA.put("attendance.defaultDurationMinutes", "int:2:240");
        SCHEMA.put("attendance.otpRotationSeconds", "int:30:120");
        SCHEMA.put("attendance.lateCountsAsAttended", "bool");
        SCHEMA.put("attendance.excusedExcludedFromDenominator", "bool");
        SCHEMA.put("attendance.lowAttendanceThreshold", "int:0:100");
        SCHEMA.put("attendance.excellentAttendanceThreshold", "int:0:100");
        SCHEMA.put("attendance.maxOtpFailuresPerSession", "int:1:50");
        SCHEMA.put("security.faceOnLogin", "bool");
        SCHEMA.put("security.faceOnAttendance", "bool");
        SCHEMA.put("security.passkeyOnAttendance", "bool");
        SCHEMA.put("security.registrationRequiresApproval", "bool");
        SCHEMA.put("security.maxFaceFailuresPerHour", "int:1:100");
        SCHEMA.put("face.matchThreshold", "num:0.30:0.80");
        SCHEMA.put("face.duplicateCheckOnEnrol", "bool");
        SCHEMA.put("biometric.retentionDays", "int:30:3650");
        SCHEMA.put("proximity.defaultPolicy", "enum:NONE,EVIDENCE,STRICT");
    }

    private final Db db;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public SettingsService(Db db) { this.db = db; }

    public String get(String key) {
        return cache.computeIfAbsent(key, k -> {
            String v = db.scalar("SELECT value FROM system_settings WHERE key = :k", String.class, "k", k);
            if (v == null) throw new IllegalStateException("Missing setting " + k);
            return v;
        });
    }

    public int getInt(String key) { return Integer.parseInt(get(key)); }
    public double getDouble(String key) { return Double.parseDouble(get(key)); }
    public boolean getBool(String key) { return Boolean.parseBoolean(get(key)); }

    public Map<String, Object> all() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (var e : db.list("SELECT key, value, updated_at FROM system_settings ORDER BY key")) {
            m.put((String) e.get("key"), Json.obj("value", e.get("value"), "type", SCHEMA.get(e.get("key")), "updatedAt", e.get("updatedAt")));
        }
        return m;
    }

    public void set(String key, String value, UUID by) {
        String rule = SCHEMA.get(key);
        if (rule == null) throw ApiException.badRequest("UNKNOWN_SETTING", "Unknown setting: " + key);
        String v = value == null ? "" : value.trim();
        String[] p = rule.split(":");
        try {
            switch (p[0]) {
                case "int" -> { int i = Integer.parseInt(v); if (i < Integer.parseInt(p[1]) || i > Integer.parseInt(p[2])) throw new IllegalArgumentException(); }
                case "num" -> { double d = Double.parseDouble(v); if (d < Double.parseDouble(p[1]) || d > Double.parseDouble(p[2])) throw new IllegalArgumentException(); }
                case "bool" -> { if (!v.equals("true") && !v.equals("false")) throw new IllegalArgumentException(); }
                case "enum" -> { if (!Arrays.asList(p[1].split(",")).contains(v)) throw new IllegalArgumentException(); }
                default -> throw new IllegalStateException();
            }
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_SETTING", "Invalid value for " + key + " (" + rule + ")");
        }
        db.update("UPDATE system_settings SET value = :v, updated_by = :u, updated_at = now() WHERE key = :k", "v", v, "u", by, "k", key);
        cache.remove(key);
    }

    public void clearCache() { cache.clear(); }
}
