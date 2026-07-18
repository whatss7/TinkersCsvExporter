package io.github.whatss7.tinkerscsvexporter;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/**
 * Fluent builder that collects tabular data and serializes it into CSV format.
 * <p>
 * Each row is identified by an item key (rendered in the first "name" column),
 * and each column is identified by a header. Columns can be given a priority to
 * control their ordering in the output. The builder can either return the CSV
 * as a string or write it directly to a file.
 */
public class CsvBuilder {
    /**
     * Header text used for the first column that holds the row keys.
     */
    private String nameHeader = "Name";
    /**
     * Ordered set of all data column headers seen so far.
     */
    private final LinkedHashSet<String> dataHeaders = new LinkedHashSet<>();
    /**
     * Row key -> (header -> cell value). Insertion order is preserved.
     */
    private final Map<String, Map<String, String>> rows = new LinkedHashMap<>();
    /**
     * Optional per-header sort priority; higher values are placed first.
     */
    private final Map<String, Integer> priorities = new HashMap<>();
    /**
     * Optional translator used to render the second (translation) header row.
     */
    private Function<String, String> translator;
    /**
     * When true, the translation is merged into the single header row instead
     * of being emitted as a separate second row.
     */
    private boolean mergeTranslationHeader = false;
    /**
     * Destination file path used by {@link #buildAndWrite()}.
     */
    private final Path outputPath;

    /**
     * @param outputPath the file the CSV will be written to; must not be null
     */
    public CsvBuilder(Path outputPath) {
        this.outputPath = Objects.requireNonNull(outputPath, "outputPath must not be null");
    }

    /**
     * Sets the header text for the first (row key) column.
     *
     * @param nameHeader the header text; must not be null
     * @return this builder for chaining
     */
    public CsvBuilder nameHeader(String nameHeader) {
        this.nameHeader = Objects.requireNonNull(nameHeader, "nameHeader must not be null");
        return this;
    }

    /**
     * Assigns a sort priority to a column. Columns with a higher priority appear
     * earlier; columns with equal priority are ordered alphabetically.
     *
     * @param header   the column header
     * @param priority the priority value (higher = earlier)
     * @return this builder for chaining
     */
    public CsvBuilder priority(String header, int priority) {
        if (header != null) priorities.put(header, priority);
        return this;
    }

    /**
     * Ensures a row exists for the given item key, creating an empty one if needed.
     *
     * @param item the row key
     * @return this builder for chaining
     */
    public CsvBuilder addItem(String item) {
        rows.computeIfAbsent(item, k -> new LinkedHashMap<>());
        return this;
    }

    /**
     * Sets a cell value for the given row and column, registering the column
     * header if it is new. Null values are ignored (the cell stays empty).
     *
     * @param item   the row key
     * @param header the column header
     * @param value  the cell value, or null to leave the cell empty
     * @return this builder for chaining
     */
    public CsvBuilder put(String item, String header, String value) {
        addItem(item);
        if (header != null) dataHeaders.add(header);
        if (value != null) {
            rows.get(item).put(header, value);
        }
        return this;
    }

    /**
     * Convenience overload that stores an {@code int} value as text.
     */
    public CsvBuilder put(String item, String header, int value) {
        return put(item, header, Integer.toString(value));
    }

    /**
     * Convenience overload that stores a {@code long} value as text.
     */
    public CsvBuilder put(String item, String header, long value) {
        return put(item, header, Long.toString(value));
    }

    /**
     * Convenience overload that stores a {@code float} value as text.
     */
    public CsvBuilder put(String item, String header, float value) {
        return put(item, header, Float.toString(value));
    }

    /**
     * Convenience overload that stores a {@code double} value as text.
     */
    public CsvBuilder put(String item, String header, double value) {
        return put(item, header, Double.toString(value));
    }

    /**
     * Convenience overload that stores a {@code boolean} value as text.
     */
    public CsvBuilder put(String item, String header, boolean value) {
        return put(item, header, Boolean.toString(value));
    }

    /**
     * Sets a translator used to build the second CSV row, which holds a
     * human-readable translation of every column header. The function receives a
     * header (or the name column header) and should return its translation, or
     * {@code null} to leave that cell blank. Pass {@code null} to disable the
     * translation row entirely.
     *
     * @param translator the translation function
     * @return this builder for chaining
     */
    public CsvBuilder translator(Function<String, String> translator) {
        this.translator = translator;
        return this;
    }

    /**
     * Controls whether the translation is merged into the single header row. When
     * enabled, each header cell shows the translation if it has content, falling
     * back to the original header otherwise, and no separate translation row is
     * emitted. When disabled, a second "translation" row is emitted beneath the
     * header (the default behaviour).
     *
     * @param merge {@code true} to merge translation into the header row
     * @return this builder for chaining
     */
    public CsvBuilder mergeTranslation(boolean merge) {
        this.mergeTranslationHeader = merge;
        return this;
    }

    /**
     * Renders all collected rows and columns into a CSV string, including the
     * header line. When a {@link #translator(Function)} is configured, the
     * translation is rendered according to the mode set via
     * {@link #mergeTranslation(boolean)}: in merge mode it is collapsed into the
     * single header row (falling back to the original header when blank), and
     * otherwise a second "translation" row is emitted immediately after the
     * header, mirroring every column. Missing cells are rendered as empty fields.
     *
     * @return the full CSV content
     */
    public String build() {
        List<String> orderedHeaders = sortedHeaders();
        StringBuilder sb = new StringBuilder();

        // Merge mode collapses the translation into the single header row, using
        // the translation when present and falling back to the original header.
        if (translator != null && mergeTranslationHeader) {
            sb.append(escape(mergeCell(nameHeader)));
            for (String h : orderedHeaders) {
                sb.append(',').append(escape(mergeCell(h)));
            }
            sb.append('\n');
        } else {
            // Write the header line: name column first, then all data columns.
            sb.append(escape(nameHeader));
            for (String h : orderedHeaders) {
                sb.append(',').append(escape(h));
            }
            sb.append('\n');

            // Write the optional translation row, mirroring the header. A blank
            // cell is emitted when the translator returns null (translation failed).
            if (translator != null) {
                sb.append(escape(translate(nameHeader)));
                for (String h : orderedHeaders) {
                    sb.append(',').append(escape(translate(h)));
                }
                sb.append('\n');
            }
        }

        // Write one line per row, filling in each column in the sorted order.
        for (Map.Entry<String, Map<String, String>> row : rows.entrySet()) {
            sb.append(escape(row.getKey()));
            Map<String, String> cells = row.getValue();
            for (String h : orderedHeaders) {
                String v = cells.get(h);
                sb.append(',').append(escape(v == null ? "" : v));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Applies the configured translator, mapping {@code null} to an empty cell.
     */
    private String translate(String header) {
        if (translator == null) return "";
        String t = translator.apply(header);
        return t == null ? "" : t;
    }

    /**
     * Returns the translated header when it has content, otherwise falls back to
     * the original header. Used by merge mode to collapse the translation row
     * into the single header line.
     */
    private String mergeCell(String header) {
        String t = translator.apply(header);
        return (t == null || t.isEmpty()) ? header : t;
    }

    /**
     * Returns the data headers ordered by descending priority, breaking ties
     * alphabetically.
     */
    private List<String> sortedHeaders() {
        List<String> ordered = new ArrayList<>(dataHeaders);
        ordered.sort((a, b) -> {
            int pa = priorities.getOrDefault(a, 0);
            int pb = priorities.getOrDefault(b, 0);
            if (pa != pb) return Integer.compare(pb, pa);
            return a.compareTo(b);
        });
        return ordered;
    }

    /**
     * Builds the CSV content and writes it to {@link #outputPath} using UTF-8,
     * creating any missing parent directories. A UTF-8 BOM is prepended so that
     * spreadsheet applications (e.g. Excel) detect the encoding correctly.
     *
     * @return the path the file was written to
     * @throws IOException if the directories or file cannot be created/written
     */
    public Path buildAndWrite() throws IOException {
        // Make sure the destination directory exists before writing.
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String csvContent = build();
        try (OutputStream out = Files.newOutputStream(outputPath);
             OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {

            writer.write('\uFEFF');  // UTF-8 BOM
            writer.write(csvContent);
        }
        return outputPath;
    }

    /**
     * Escapes a single CSV field per RFC 4180: fields containing commas, quotes,
     * or line breaks are wrapped in double quotes, and inner quotes are doubled.
     *
     * @param field the raw field value (may be null)
     * @return the escaped field, or an empty string if {@code field} is null
     */
    private static String escape(String field) {
        if (field == null) return "";
        boolean needQuote = field.indexOf(',') >= 0
                || field.indexOf('"') >= 0
                || field.indexOf('\n') >= 0
                || field.indexOf('\r') >= 0;
        if (!needQuote) return field;

        StringBuilder sb = new StringBuilder(field.length() + 2);
        sb.append('"');
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c == '"') sb.append("\"\"");
            else sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }
}
