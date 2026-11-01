package com.paisatrack.modules.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * A titled grid of values, written as CSV, Excel or PDF. Several tables make one file: sheets in
 * Excel, sections in CSV and PDF. Amounts stay numbers in CSV and Excel, so sums in a spreadsheet
 * match the app exactly.
 */
record Table(String title, List<String> headers, List<List<Object>> rows) {

    private static final DateTimeFormatter PDF_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    Table {
        rows = new ArrayList<>(rows);
    }

    static Table of(String title, String... headers) {
        return new Table(title, List.of(headers), new ArrayList<>());
    }

    Table add(Object... values) {
        rows.add(java.util.Arrays.asList(values));
        return this;
    }

    // ---- CSV ----

    static byte[] csv(List<Table> tables) {
        StringBuilder out = new StringBuilder("﻿"); // BOM so Excel opens UTF-8 (₹, names) correctly
        boolean sections = tables.size() > 1;
        for (Table t : tables) {
            if (sections) {
                out.append(cell(t.title())).append("\r\n");
            }
            out.append(String.join(",", t.headers().stream().map(Table::cell).toList())).append("\r\n");
            for (List<Object> row : t.rows()) {
                out.append(String.join(",", row.stream().map(Table::cell).toList())).append("\r\n");
            }
            if (sections) {
                out.append("\r\n");
            }
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** RFC 4180 quoting, and a leading quote on text that a spreadsheet would run as a formula. */
    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal n) {
            return n.toPlainString();
        }
        if (value instanceof Number || value instanceof LocalDate) {
            return value.toString();
        }
        String s = value.toString();
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ---- Excel ----

    static byte[] xlsx(List<Table> tables) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CreationHelper helper = wb.getCreationHelper();
            CellStyle header = wb.createCellStyle();
            var bold = wb.createFont();
            bold.setBold(true);
            header.setFont(bold);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(helper.createDataFormat().getFormat("#,##0.00"));
            CellStyle date = wb.createCellStyle();
            date.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));
            for (Table t : tables) {
                SXSSFSheet sheet = wb.createSheet(sheetName(t.title()));
                sheet.trackAllColumnsForAutoSizing();
                Row head = sheet.createRow(0);
                for (int i = 0; i < t.headers().size(); i++) {
                    Cell c = head.createCell(i);
                    c.setCellValue(t.headers().get(i));
                    c.setCellStyle(header);
                }
                int r = 1;
                for (List<Object> values : t.rows()) {
                    Row row = sheet.createRow(r++);
                    for (int i = 0; i < values.size(); i++) {
                        write(row.createCell(i), values.get(i), money, date);
                    }
                }
                if (!t.rows().isEmpty()) {
                    sheet.setAutoFilter(new CellRangeAddress(0, r - 1, 0, t.headers().size() - 1));
                }
                sheet.createFreezePane(0, 1);
                for (int i = 0; i < t.headers().size(); i++) {
                    sheet.autoSizeColumn(i);
                }
            }
            wb.write(out);
            wb.dispose();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Cell cell, Object v, CellStyle money, CellStyle date) {
        if (v == null) {
            return;
        }
        if (v instanceof BigDecimal n) {
            cell.setCellValue(n.doubleValue());
            cell.setCellStyle(money);
        } else if (v instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else if (v instanceof LocalDate d) {
            cell.setCellValue(d);
            cell.setCellStyle(date);
        } else {
            cell.setCellValue(v.toString());
        }
    }

    private static String sheetName(String title) {
        String clean = title.replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
        return clean.length() > 31 ? clean.substring(0, 31) : clean;
    }

    // ---- PDF ----

    static byte[] pdf(String heading, List<String> lines, List<Table> tables) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4.rotate(), 28, 28, 28, 28);
        PdfWriter.getInstance(doc, out);
        doc.addTitle(heading);
        doc.addCreator("PaisaTrack");
        doc.open();
        Font h1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
        Font h2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
        Font body = FontFactory.getFont(FontFactory.HELVETICA, 8.5f);
        Font muted = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, java.awt.Color.GRAY);
        doc.add(new Paragraph(heading, h1));
        for (String line : lines) {
            doc.add(new Paragraph(line, muted));
        }
        for (Table t : tables) {
            Paragraph title = new Paragraph(t.title(), h2);
            title.setSpacingBefore(12);
            title.setSpacingAfter(4);
            doc.add(title);
            if (t.rows().isEmpty()) {
                doc.add(new Paragraph("Nothing in this period.", muted));
                continue;
            }
            PdfPTable grid = new PdfPTable(t.headers().size());
            grid.setWidthPercentage(100);
            grid.setHeaderRows(1);
            for (String h : t.headers()) {
                PdfPCell c = new PdfPCell(new Phrase(h, h2));
                c.setBackgroundColor(new java.awt.Color(232, 242, 237));
                c.setPadding(4);
                grid.addCell(c);
            }
            for (List<Object> values : t.rows()) {
                for (Object v : values) {
                    PdfPCell c = new PdfPCell(new Phrase(pdfText(v), body));
                    c.setPadding(3.5f);
                    if (v instanceof Number) {
                        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    }
                    grid.addCell(c);
                }
            }
            doc.add(grid);
        }
        doc.close();
        return out.toByteArray();
    }

    private static String pdfText(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof BigDecimal n) {
            return String.format(Locale.ENGLISH, "%,.2f", n);
        }
        if (v instanceof LocalDate d) {
            return PDF_DATE.format(d);
        }
        return v.toString();
    }
}
