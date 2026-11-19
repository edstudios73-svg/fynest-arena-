package gh.edu.gctu.smartattend.student;

import gh.edu.gctu.smartattend.common.Db;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class StudentDirectory {
    private final Db db;

    public StudentDirectory(Db db) { this.db = db; }

    /** Normalise an index number as typed by a student (trim, uppercase, collapse spaces). */
    public static String normaliseIndex(String raw) {
        if (raw == null) return "";
        return raw.trim().replaceAll("\\s+", "").toUpperCase();
    }

    /** Display name built consistently from the official register fields, spelling untouched. */
    public static String fullName(Map<String, Object> s) {
        StringBuilder b = new StringBuilder();
        append(b, (String) s.get("firstName"));
        append(b, (String) s.get("otherNames"));
        append(b, (String) s.get("lastName"));
        return b.toString();
    }

    private static void append(StringBuilder b, String part) {
        if (part != null && !part.isBlank()) {
            if (b.length() > 0) b.append(' ');
            b.append(part.trim());
        }
    }

    public Map<String, Object> byIndex(String index) {
        return db.one("""
                SELECT s.id, s.user_id, s.index_number, s.title, s.first_name, s.last_name, s.other_names, s.register_status,
                       g.register_status AS group_register_status, g.name AS group_name, g.level, p.name AS programme,
                       u.status AS account_status
                FROM students s JOIN class_groups g ON g.id = s.class_group_id JOIN programmes p ON p.id = g.programme_id
                LEFT JOIN app_users u ON u.id = s.user_id
                WHERE upper(s.index_number) = :i""", "i", normaliseIndex(index));
    }

    public Map<String, Object> byId(UUID id) {
        return db.one("""
                SELECT s.id, s.user_id, s.index_number, s.title, s.first_name, s.last_name, s.other_names, s.register_status,
                       s.class_group_id, g.name AS group_name, g.level, p.name AS programme, u.status AS account_status
                FROM students s JOIN class_groups g ON g.id = s.class_group_id JOIN programmes p ON p.id = g.programme_id
                LEFT JOIN app_users u ON u.id = s.user_id WHERE s.id = :id""", "id", id);
    }
}
