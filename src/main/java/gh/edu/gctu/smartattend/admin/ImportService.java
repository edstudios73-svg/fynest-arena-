package gh.edu.gctu.smartattend.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import gh.edu.gctu.smartattend.common.*;
import gh.edu.gctu.smartattend.security.CurrentUser;
import gh.edu.gctu.smartattend.student.StudentDirectory;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Official register import: parse → validate → preview → explicit administrator confirmation.
 * Names are stored exactly as supplied (only surrounding whitespace is trimmed).
 * Nothing is silently dropped: invalid and duplicate rows are reported and excluded from the commit.
 */
@Service
public class ImportService {
    private static final int MAX_ROWS = 5000;
    private static final Map<String, String> HEADER_ALIASES = Map.ofEntries(
            Map.entry("student id", "index"), Map.entry("studentid", "index"), Map.entry("index number", "index"),
            Map.entry("index no", "index"), Map.entry("index", "index"), Map.entry("title", "title"),
            Map.entry("first name", "first"), Map.entry("firstname", "first"), Map.entry("last name", "last"),
            Map.entry("lastname", "last"), Map.entry("surname", "last"), Map.entry("other names", "other"),
            Map.entry("other name", "other"), Map.entry("othernames", "other"), Map.entry("middle name", "other"));

    private final Db db;
    private final AuditService audit;
    private final ObjectMapper om;

    public ImportService(Db db, AuditService audit, ObjectMapper om) {
        this.db = db;
        this.audit = audit;
        this.om = om;
    }

    public record Parsed(String sheet, List<String> sheets, List<Map<String, Object>> rows, List<String> fileErrors) {}

    public Map<String, Object> preview(CurrentUser u, MultipartFile file, UUID classGroupId, String sheetName, boolean enrolAll) throws IOException {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("FILE_REQUIRED", "Choose a file to import.");
        Map<String, Object> group = db.one("SELECT id, name, expected_size, register_status FROM class_groups WHERE id = :g", "g", classGroupId);
        if (group == null) throw ApiException.notFound("GROUP_NOT_FOUND", "Class group not found.");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("upload").replaceAll("[^A-Za-z0-9 ._()-]", "_");
        byte[] bytes = file.getBytes();
        Parsed p = parse(name, bytes, sheetName);
        if (!p.fileErrors().isEmpty()) {
            return Json.obj("valid", false, "fileErrors", p.fileErrors(), "sheets", p.sheets());
        }
        // Validation
        Map<String, Integer> counts = new HashMap<>();
        for (var r : p.rows()) counts.merge((String) r.get("indexNumber"), 1, Integer::sum);
        int valid = 0, invalid = 0, dup = 0, exists = 0;
        for (var r : p.rows()) {
            List<String> errors = new ArrayList<>();
            String idx = (String) r.get("indexNumber");
            if (idx.isEmpty()) errors.add("Missing Student ID");
            else if (!idx.matches("[A-Za-z0-9/._-]{3,40}")) errors.add("Student ID has invalid characters or length");
            if (blank(r.get("firstName"))) errors.add("Missing First Name");
            if (blank(r.get("lastName"))) errors.add("Missing Last Name");
            String status;
            if (!errors.isEmpty()) { status = "INVALID"; invalid++; }
            else if (counts.get(idx) > 1) { status = "DUPLICATE_IN_FILE"; dup++; errors.add("Student ID appears " + counts.get(idx) + " times in this file"); }
            else {
                Map<String, Object> existing = db.one("SELECT first_name, last_name, other_names, class_group_id FROM students WHERE upper(index_number) = :i", "i", idx.toUpperCase());
                if (existing == null) { status = "VALID"; valid++; }
                else {
                    exists++;
                    boolean same = Objects.equals(existing.get("firstName"), r.get("firstName")) && Objects.equals(existing.get("lastName"), r.get("lastName"))
                            && Objects.equals(existing.get("otherNames"), r.get("otherNames"));
                    status = same ? "ALREADY_IMPORTED" : "CONFLICT";
                    if (!same) errors.add("Already in the register with a different name — review manually");
                }
            }
            r.put("status", status);
            r.put("errors", errors);
        }
        Integer currentCount = db.scalar("SELECT count(*)::int FROM students WHERE class_group_id = :g AND register_status = 'ACTIVE'", Integer.class, "g", classGroupId);
        int afterCommit = (currentCount == null ? 0 : currentCount) + valid;
        Integer expected = (Integer) group.get("expectedSize");
        Map<String, Object> discrepancy = expected != null && expected != afterCommit
                ? Json.obj("expected", expected, "afterImport", afterCommit, "difference", afterCommit - expected,
                "message", "The timetable states " + expected + " students but the register will contain " + afterCommit +
                        ". The class register must be verified by an administrator before it is activated.")
                : null;
        Map<String, Object> preview = Json.obj("rows", p.rows(), "sheet", p.sheet(), "sheets", p.sheets(), "discrepancy", discrepancy,
                "existingInGroup", currentCount);
        UUID id = db.insertReturningId("""
                INSERT INTO import_batches(filename, sheet_name, class_group_id, uploaded_by, row_count, valid_count, invalid_count, duplicate_count,
                    enrol_all_courses, preview) VALUES (:f, :s, :g, :u, :rc, :v, :i, :d, :e, CAST(:p AS jsonb)) RETURNING id""",
                "f", name, "s", p.sheet(), "g", classGroupId, "u", u.userId(), "rc", p.rows().size(), "v", valid, "i", invalid, "d", dup,
                "e", enrolAll, "p", db.json(preview));
        audit.log("REGISTER_IMPORT_PREVIEWED", "import_batch", id, Json.obj("file", name, "rows", p.rows().size(), "valid", valid,
                "invalid", invalid, "duplicates", dup, "existing", exists));
        return Json.obj("valid", true, "batchId", id, "filename", name, "sheet", p.sheet(), "sheets", p.sheets(), "rowCount", p.rows().size(),
                "validCount", valid, "invalidCount", invalid, "duplicateCount", dup, "existingCount", exists,
                "discrepancy", discrepancy, "rows", p.rows(), "group", group);
    }

    @Transactional
    public Map<String, Object> commit(CurrentUser u, UUID batchId) {
        Map<String, Object> b = db.one("SELECT * FROM import_batches WHERE id = :id FOR UPDATE", "id", batchId);
        if (b == null) throw ApiException.notFound("BATCH_NOT_FOUND", "Import not found.");
        if (!"PREVIEW".equals(b.get("status"))) throw ApiException.conflict("BATCH_DONE", "This import was already " + b.get("status").toString().toLowerCase() + ".");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ((Map<String, Object>) b.get("preview")).get("rows");
        UUID group = (UUID) b.get("classGroupId");
        UUID sem = db.scalar("SELECT semester_id FROM class_groups WHERE id = :g", UUID.class, "g", group);
        int inserted = 0, skipped = 0;
        for (var r : rows) {
            if (!"VALID".equals(r.get("status"))) { skipped++; continue; }
            int n = db.update("""
                    INSERT INTO students(class_group_id, index_number, title, first_name, last_name, other_names, import_batch_id)
                    VALUES (:g, :i, :t, :f, :l, :o, :b) ON CONFLICT (index_number) DO NOTHING""",
                    "g", group, "i", r.get("indexNumber"), "t", nz(r.get("title")), "f", r.get("firstName"), "l", r.get("lastName"),
                    "o", nz(r.get("otherNames")), "b", batchId);
            if (n == 1) inserted++; else skipped++;
        }
        int enrolments = 0;
        if (Boolean.TRUE.equals(b.get("enrolAllCourses"))) {
            enrolments = db.update("""
                    INSERT INTO course_enrollments(course_id, student_id, semester_id)
                    SELECT DISTINCT t.course_id, s.id, t.semester_id FROM students s
                    JOIN class_timetables t ON t.class_group_id = s.class_group_id AND t.semester_id = :sem
                    WHERE s.import_batch_id = :b ON CONFLICT DO NOTHING""", "sem", sem, "b", batchId);
        }
        db.update("UPDATE import_batches SET status = 'COMMITTED', committed_at = now(), committed_by = :u WHERE id = :id", "u", u.userId(), "id", batchId);
        // Any change to the register requires re-verification before the class register is (re)activated.
        Map<String, Object> g = db.one("""
                SELECT expected_size, (SELECT count(*)::int FROM students WHERE class_group_id = :g AND register_status = 'ACTIVE') AS actual
                FROM class_groups WHERE id = :g""", "g", group);
        if (inserted > 0) {
            db.update("UPDATE class_groups SET register_status = 'VERIFIED' WHERE id = :g", "g", group);
        }
        audit.log("REGISTER_IMPORT_COMMITTED", "import_batch", batchId, Json.obj("inserted", inserted, "skipped", skipped, "enrolments", enrolments,
                "expected", g.get("expectedSize"), "actual", g.get("actual")));
        return Json.obj("inserted", inserted, "skipped", skipped, "enrolments", enrolments, "expected", g.get("expectedSize"), "actual", g.get("actual"),
                "registerStatus", "VERIFIED");
    }

    public Parsed parse(String filename, byte[] bytes, String sheetName) throws IOException {
        String lower = filename.toLowerCase();
        boolean zip = bytes.length > 4 && bytes[0] == 'P' && bytes[1] == 'K';
        boolean ole = bytes.length > 8 && (bytes[0] & 0xFF) == 0xD0 && (bytes[1] & 0xFF) == 0xCF;
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            if (!zip && !ole) return new Parsed(null, List.of(), List.of(), List.of("The file is not a valid Excel workbook."));
            return parseWorkbook(bytes, sheetName);
        }
        if (lower.endsWith(".csv")) {
            if (zip || ole) return new Parsed(null, List.of(), List.of(), List.of("The file is not a CSV file."));
            return parseCsv(bytes);
        }
        return new Parsed(null, List.of(), List.of(), List.of("Unsupported file type. Upload .xlsx, .xls or .csv."));
    }

    private Parsed parseWorkbook(byte[] bytes, String sheetName) throws IOException {
        try (InputStream in = new java.io.ByteArrayInputStream(bytes); Workbook wb = WorkbookFactory.create(in)) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) names.add(wb.getSheetName(i));
            Sheet sheet = null;
            if (sheetName != null && !sheetName.isBlank()) sheet = wb.getSheet(sheetName);
            if (sheet == null) {
                for (Sheet s : wb) if (findHeader(s) != null) { sheet = s; break; }
            }
            if (sheet == null) return new Parsed(null, names, List.of(), List.of("No sheet with the required columns (Student ID, First Name, Last Name) was found."));
            Integer headerRow = findHeader(sheet);
            if (headerRow == null) return new Parsed(sheet.getSheetName(), names, List.of(), List.of("Sheet '" + sheet.getSheetName() + "' is missing required columns: Student ID, First Name, Last Name."));
            Map<String, Integer> cols = headerMap(sheet.getRow(headerRow));
            DataFormatter fmt = new DataFormatter();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                Map<String, Object> m = rowMap(r + 1, k -> cols.containsKey(k) ? fmt.formatCellValue(row.getCell(cols.get(k))) : "");
                if (m == null) continue;
                rows.add(m);
                if (rows.size() > MAX_ROWS) return new Parsed(sheet.getSheetName(), names, List.of(), List.of("Too many rows (max " + MAX_ROWS + ")."));
            }
            return new Parsed(sheet.getSheetName(), names, rows, List.of());
        } catch (org.apache.poi.EmptyFileException | org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException e) {
            return new Parsed(null, List.of(), List.of(), List.of("The workbook could not be read."));
        }
    }

    private Parsed parseCsv(byte[] bytes) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            List<List<String>> lines = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                if (lines.isEmpty() && line.startsWith("﻿")) line = line.substring(1);
                lines.add(splitCsv(line));
                if (lines.size() > MAX_ROWS + 20) return new Parsed("CSV", List.of("CSV"), List.of(), List.of("Too many rows (max " + MAX_ROWS + ")."));
            }
            int header = -1;
            Map<String, Integer> cols = null;
            for (int i = 0; i < Math.min(10, lines.size()); i++) {
                Map<String, Integer> c = headerMapFrom(lines.get(i));
                if (c.containsKey("index") && c.containsKey("first") && c.containsKey("last")) { header = i; cols = c; break; }
            }
            if (cols == null) return new Parsed("CSV", List.of("CSV"), List.of(), List.of("CSV is missing required columns: Student ID, First Name, Last Name."));
            List<Map<String, Object>> rows = new ArrayList<>();
            final Map<String, Integer> fc = cols;
            for (int i = header + 1; i < lines.size(); i++) {
                List<String> l = lines.get(i);
                Map<String, Object> m = rowMap(i + 1, k -> fc.containsKey(k) && fc.get(k) < l.size() ? l.get(fc.get(k)) : "");
                if (m != null) rows.add(m);
            }
            return new Parsed("CSV", List.of("CSV"), rows, List.of());
        }
    }

    private static Map<String, Object> rowMap(int rowNo, java.util.function.Function<String, String> get) {
        String idx = StudentDirectory.normaliseIndex(get.apply("index"));
        String title = trim(get.apply("title")), first = trim(get.apply("first")), last = trim(get.apply("last")), other = trim(get.apply("other"));
        if (idx.isEmpty() && first.isEmpty() && last.isEmpty() && other.isEmpty()) return null; // blank row
        return Json.obj("row", rowNo, "indexNumber", idx, "title", title, "firstName", first, "lastName", last, "otherNames", other);
    }

    private Integer findHeader(Sheet s) {
        for (int r = s.getFirstRowNum(); r <= Math.min(s.getLastRowNum(), s.getFirstRowNum() + 10); r++) {
            Row row = s.getRow(r);
            if (row == null) continue;
            Map<String, Integer> m = headerMap(row);
            if (m.containsKey("index") && m.containsKey("first") && m.containsKey("last")) return r;
        }
        return null;
    }

    private Map<String, Integer> headerMap(Row row) {
        DataFormatter fmt = new DataFormatter();
        List<String> cells = new ArrayList<>();
        for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) cells.add(fmt.formatCellValue(row.getCell(c)));
        return headerMapFrom(cells);
    }

    private static Map<String, Integer> headerMapFrom(List<String> cells) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            String k = HEADER_ALIASES.get(cells.get(i).trim().toLowerCase().replaceAll("\\s+", " "));
            if (k != null) m.putIfAbsent(k, i);
        }
        return m;
    }

    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (c == '"') q = false;
                else cur.append(c);
            } else if (c == '"') q = true;
            else if (c == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }
    private static boolean blank(Object o) { return o == null || o.toString().isBlank(); }
    private static String nz(Object o) { return o == null || o.toString().isBlank() ? null : o.toString(); }
}
