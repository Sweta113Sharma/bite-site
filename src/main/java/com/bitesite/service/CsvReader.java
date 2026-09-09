package com.bitesite.service;

import java.util.ArrayList;
import java.util.List;

/**
 * A small RFC 4180 reader, written rather than pulled in, because the alternative was a
 * dependency for one screen.
 *
 * <p>It exists at all because the naive {@code split(",")} fails on the first real menu:
 * "Chicken Roll, Large" is one field, not two. So quoted fields are honoured, a doubled
 * quote inside them is a literal quote, and a newline inside them does not end the row.
 *
 * <p>The rest of it is the specific ways a spreadsheet export breaks a parser:
 *
 * <ul>
 *   <li><b>A UTF-8 BOM.</b> Excel writes one. Left in place it becomes part of the first
 *       header, so "category" silently is not "category" and the file looks like it has
 *       no such column.</li>
 *   <li><b>CRLF, and lone CR.</b> Windows and old Mac exports respectively.</li>
 *   <li><b>A trailing newline</b>, which must not produce a final empty row.</li>
 * </ul>
 *
 * <p>Every one of these produces a confusing failure rather than an obvious one, which is
 * why they are handled here instead of being left for whoever uploads the file.
 */
final class CsvReader {

    private CsvReader() {}

    /** Splits CSV text into rows of raw, unquoted fields. Blank rows are dropped. */
    static List<List<String>> parse(String content) {
        List<List<String>> rows = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return rows;
        }
        // The BOM, if Excel left one, before anything else looks at the text.
        if (content.charAt(0) == '﻿') {
            content = content.substring(1);
        }

        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    // A doubled quote is an escaped one; a single quote closes the field.
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }

            switch (c) {
                case '"' -> inQuotes = true;
                case ',' -> {
                    row.add(field.toString());
                    field.setLength(0);
                }
                case '\r' -> {
                    // Swallow the LF of a CRLF so it does not open a second, empty row.
                    if (i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                        i++;
                    }
                    row.add(field.toString());
                    field.setLength(0);
                    addIfNotBlank(rows, row);
                    row = new ArrayList<>();
                }
                case '\n' -> {
                    row.add(field.toString());
                    field.setLength(0);
                    addIfNotBlank(rows, row);
                    row = new ArrayList<>();
                }
                default -> field.append(c);
            }
        }

        // Whatever the last line left behind, when the file does not end in a newline.
        row.add(field.toString());
        addIfNotBlank(rows, row);
        return rows;
    }

    /**
     * Drops rows that are entirely empty. Spreadsheets are generous with these: a file
     * saved after deleting some rows often carries a tail of ",,,," lines, and each one
     * would otherwise be reported to the admin as a row with a missing name and price.
     */
    private static void addIfNotBlank(List<List<String>> rows, List<String> row) {
        if (row.stream().anyMatch(f -> !f.isBlank())) {
            rows.add(row);
        }
    }
}
