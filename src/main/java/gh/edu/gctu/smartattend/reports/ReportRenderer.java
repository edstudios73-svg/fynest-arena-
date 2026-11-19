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
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
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

    public static String stamp() { return ZonedDateTime.now(java.time.ZoneId.of("Africa/Accra")).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")); }
}
