package gh.edu.gctu.smartattend.reports;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import gh.edu.gctu.smartattend.common.ApiException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class ReportRenderer {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public ResponseEntity<byte[]> render(Report r, String format) {
        String f = format == null ? "csv" : format.toLowerCase();
        try {
            return switch (f) {
                case "csv" -> file(csv(r), r.fileBase() + ".csv", "text/csv;charset=UTF-8");
                case "xlsx" -> file(xlsx(r), r.fileBase() + ".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                case "pdf" -> file(pdf(r), r.fileBase() + ".pdf", "application/pdf");
                default -> throw ApiException.badRequest("INVALID_FORMAT", "Format must be csv, xlsx or pdf.");
            };
        } catch (IOException | DocumentException e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<byte[]> file(byte[] body, String name, String type) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name.replaceAll("[^A-Za-z0-9._-]", "-")).build().toString())
                .contentType(MediaType.parseMediaType(type)).body(body);
    }

    /** Neutralise spreadsheet formula injection (cells starting with = + - @ tab CR). */
    static String safe(Object o) {
        if (o == null) return "";
        String s = o.toString();
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !s.matches("-?\\d+(\\.\\d+)?")) s = "'" + s;
        return s;
    }

    byte[] csv(Report r) {
        StringBuilder b = new StringBuilder("﻿");
        b.append(cell(r.title())).append('\n');
        for (String m : r.meta()) b.append(cell(m)).append('\n');
        b.append('\n');
        b.append(String.join(",", r.headers().stream().map(ReportRenderer::cell).toList())).append('\n');
        for (var row : r.rows()) b.append(String.join(",", row.stream().map(ReportRenderer::cell).toList())).append('\n');
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String cell(Object o) {
        String s = safe(o);
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) s = "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    byte[] xlsx(Report r) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Report");
            CellStyle bold = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            bold.setFont(font);
            int rowNo = 0;
            Row t = sh.createRow(rowNo++);
            t.createCell(0).setCellValue(r.title());
            t.getCell(0).setCellStyle(bold);
            for (String m : r.meta()) sh.createRow(rowNo++).createCell(0).setCellValue(m);
            rowNo++;
            Row h = sh.createRow(rowNo++);
            for (int i = 0; i < r.headers().size(); i++) {
                Cell c = h.createCell(i);
                c.setCellValue(r.headers().get(i));
                c.setCellStyle(bold);
            }
            for (var row : r.rows()) {
                Row x = sh.createRow(rowNo++);
                for (int i = 0; i < row.size(); i++) {
                    Object v = row.get(i);
                    if (v instanceof Number n) x.createCell(i).setCellValue(n.doubleValue());
                    else x.createCell(i).setCellValue(safe(v));
                }
            }
            for (int i = 0; i < r.headers().size(); i++) sh.autoSizeColumn(i);
            wb.write(out);
            return out.toByteArray();
        }
    }

    byte[] pdf(Report r) throws DocumentException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(r.headers().size() > 6 ? PageSize.A4.rotate() : PageSize.A4, 32, 32, 36, 36);
        PdfWriter.getInstance(doc, out);
        doc.open();
        Color navy = new Color(11, 31, 68);
        com.lowagie.text.Font brand = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, new Color(37, 99, 235));
        com.lowagie.text.Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, navy);
        com.lowagie.text.Font small = FontFactory.getFont(FontFactory.HELVETICA, 9, new Color(71, 85, 105));
        com.lowagie.text.Font head = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.WHITE);
        com.lowagie.text.Font body = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.BLACK);
        doc.add(new Paragraph("SmartAttend · Ghana Communication Technology University", brand));
        doc.add(new Paragraph(r.title(), title));
        for (String m : r.meta()) doc.add(new Paragraph(m, small));
        doc.add(new Paragraph("Generated " + ZonedDateTime.now(java.time.ZoneId.of("Africa/Accra")).format(STAMP) + " (Africa/Accra)", small));
        doc.add(Chunk.NEWLINE);
        PdfPTable table = new PdfPTable(r.headers().size());
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String h : r.headers()) {
            PdfPCell c = new PdfPCell(new Phrase(h, head));
            c.setBackgroundColor(navy);
            c.setPadding(4);
            table.addCell(c);
        }
        boolean alt = false;
        for (var row : r.rows()) {
            for (Object v : row) {
                PdfPCell c = new PdfPCell(new Phrase(v == null ? "" : v.toString(), body));
                c.setPadding(3);
                if (alt) c.setBackgroundColor(new Color(244, 246, 250));
                table.addCell(c);
            }
            alt = !alt;
        }
        if (r.rows().isEmpty()) {
            PdfPCell c = new PdfPCell(new Phrase("No data", body));
            c.setColspan(r.headers().size());
            table.addCell(c);
        }
        doc.add(table);
        doc.close();
        return out.toByteArray();
    }

    /** Printable class attendance sheet: header, summary, who came, who didn't, signatures. */
    public ResponseEntity<byte[]> sessionSheetPdf(ReportService.Sheet sh) {
        try {
            return file(sheet(sh), sh.fileBase() + ".pdf", "application/pdf");
        } catch (DocumentException e) {
            throw new IllegalStateException(e);
        }
    }

    byte[] sheet(ReportService.Sheet sh) throws DocumentException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 36, 40);
        PdfWriter.getInstance(doc, out);
        doc.open();
        Color navy = new Color(11, 31, 68), blue = new Color(37, 99, 235), green = new Color(21, 128, 61), red = new Color(185, 28, 28),
                grey = new Color(100, 116, 139), line = new Color(226, 232, 240);
        var f = (java.util.function.BiFunction<Float, Color, com.lowagie.text.Font>) (size, c) -> FontFactory.getFont(FontFactory.HELVETICA, size, c);
        var fb = (java.util.function.BiFunction<Float, Color, com.lowagie.text.Font>) (size, c) -> FontFactory.getFont(FontFactory.HELVETICA_BOLD, size, c);

        // Header band
        PdfPTable band = new PdfPTable(1);
        band.setWidthPercentage(100);
        PdfPCell bc = new PdfPCell();
        bc.setBackgroundColor(navy);
        bc.setBorder(0);
        bc.setPadding(14);
        bc.addElement(new Paragraph("GHANA COMMUNICATION TECHNOLOGY UNIVERSITY", fb.apply(9f, new Color(147, 197, 253))));
        bc.addElement(new Paragraph("Class Attendance Sheet", fb.apply(20f, Color.WHITE)));
        bc.addElement(new Paragraph(sh.course() + (sh.code() == null ? "" : "  ·  " + sh.code()), f.apply(12f, Color.WHITE)));
        band.addCell(bc);
        doc.add(band);
        doc.add(new Paragraph(" ", f.apply(6f, Color.WHITE)));

        // Details
        PdfPTable info = new PdfPTable(new float[] {1, 2, 1, 2});
        info.setWidthPercentage(100);
        String[][] kv = {{"Lecturer", nz(sh.lecturer())}, {"Date", sh.date()}, {"Class", nz(sh.group())}, {"Started", nz(sh.time())},
                {"Venue", nz(sh.venue())}, {"Status", "CLOSED".equals(sh.status()) ? "Final" : "In progress"}};
        for (String[] p : kv) {
            PdfPCell k = new PdfPCell(new Phrase(p[0], f.apply(9f, grey)));
            PdfPCell v = new PdfPCell(new Phrase(p[1], fb.apply(10f, navy)));
            for (PdfPCell c : new PdfPCell[] {k, v}) { c.setBorder(0); c.setPaddingBottom(5); info.addCell(c); }
        }
        doc.add(info);
        doc.add(new Paragraph(" ", f.apply(6f, Color.WHITE)));

        // Summary boxes
        int total = sh.present().size() + sh.absent().size();
        String pct = total == 0 ? "—" : Math.round(sh.present().size() * 1000.0 / total) / 10.0 + "%";
        PdfPTable sum = new PdfPTable(4);
        sum.setWidthPercentage(100);
        Object[][] boxes = {{"Students", String.valueOf(total), navy}, {"Present", String.valueOf(sh.present().size()), green},
                {"Absent", String.valueOf(sh.absent().size()), red}, {"Attendance", pct, blue}};
        for (Object[] b : boxes) {
            PdfPCell c = new PdfPCell();
            c.setBorderColor(line);
            c.setPadding(10);
            Paragraph n = new Paragraph((String) b[1], fb.apply(20f, (Color) b[2]));
            n.setAlignment(com.lowagie.text.Element.ALIGN_CENTER);
            Paragraph l = new Paragraph((String) b[0], f.apply(9f, grey));
            l.setAlignment(com.lowagie.text.Element.ALIGN_CENTER);
            c.addElement(n);
            c.addElement(l);
            sum.addCell(c);
        }
        doc.add(sum);

        section(doc, "Present (" + sh.present().size() + ")", green, sh.present(), true, f, fb, line);
        section(doc, "Absent (" + sh.absent().size() + ")", red, sh.absent(), false, f, fb, line);

        // Signatures
        doc.add(new Paragraph(" ", f.apply(18f, Color.WHITE)));
        PdfPTable sig = new PdfPTable(2);
        sig.setWidthPercentage(100);
        for (String who : new String[] {"Course rep", "Lecturer"}) {
            PdfPCell c = new PdfPCell();
            c.setBorder(0);
            c.setPaddingRight(24);
            c.addElement(new Paragraph("______________________________", f.apply(10f, grey)));
            c.addElement(new Paragraph(who + " — name, signature & date", f.apply(9f, grey)));
            sig.addCell(c);
        }
        doc.add(sig);
        doc.add(new Paragraph(" ", f.apply(8f, Color.WHITE)));
        doc.add(new Paragraph("Generated by SmartAttend on " + ZonedDateTime.now(java.time.ZoneId.of("Africa/Accra")).format(STAMP)
                + ". Students check in with their face, the class code and their location in class.", f.apply(8f, grey)));
        doc.close();
        return out.toByteArray();
    }

    private static String nz(String s) { return s == null || s.isBlank() ? "—" : s; }

    private void section(Document doc, String heading, Color color, java.util.List<ReportService.Person> people, boolean withTime,
                         java.util.function.BiFunction<Float, Color, com.lowagie.text.Font> f,
                         java.util.function.BiFunction<Float, Color, com.lowagie.text.Font> fb, Color line) throws DocumentException {
        Paragraph h = new Paragraph(heading, fb.apply(12f, color));
        h.setSpacingBefore(16);
        h.setSpacingAfter(6);
        doc.add(h);
        String[] heads = withTime ? new String[] {"#", "Index number", "Name", "Status", "Time"} : new String[] {"#", "Index number", "Name", "Status"};
        PdfPTable t = new PdfPTable(withTime ? new float[] {0.5f, 2f, 4.5f, 1.4f, 1f} : new float[] {0.5f, 2f, 4.5f, 1.4f});
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        for (String x : heads) {
            PdfPCell c = new PdfPCell(new Phrase(x, fb.apply(8.5f, Color.WHITE)));
            c.setBackgroundColor(color);
            c.setBorderColor(color);
            c.setPadding(5);
            t.addCell(c);
        }
        int i = 0;
        for (var p : people) {
            i++;
            String[] vals = withTime ? new String[] {String.valueOf(i), p.index(), p.name(), p.status(), p.time()} : new String[] {String.valueOf(i), p.index(), p.name(), p.status()};
            for (String v : vals) {
                PdfPCell c = new PdfPCell(new Phrase(v == null ? "" : v, f.apply(9f, Color.BLACK)));
                c.setPadding(4.5f);
                c.setBorderColor(line);
                if (i % 2 == 0) c.setBackgroundColor(new Color(248, 250, 252));
                t.addCell(c);
            }
        }
        if (people.isEmpty()) {
            PdfPCell c = new PdfPCell(new Phrase("None", f.apply(9f, Color.GRAY)));
            c.setColspan(heads.length);
            c.setPadding(6);
            c.setBorderColor(line);
            t.addCell(c);
        }
        doc.add(t);
    }

    public static String stamp() { return ZonedDateTime.now(java.time.ZoneId.of("Africa/Accra")).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")); }
}
