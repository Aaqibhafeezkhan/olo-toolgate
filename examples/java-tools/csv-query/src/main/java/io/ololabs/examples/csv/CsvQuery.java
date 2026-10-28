// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.csv;

import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.Global;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.annotations.ValueType;
import io.ololabs.toolgate.sdk.context.ToolContext;
import io.ololabs.toolgate.sdk.errors.ToolConfigurationError;
import io.ololabs.toolgate.sdk.errors.ToolInputError;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Lets a model answer questions from CSV exports (reports, inventories, audit extracts) without loading them into a
 * database: filter rows, pick columns, sort, and count or total by group. Files come only from {@code DataDir}, and
 * size and row caps keep a call bounded.
 */
@ToolPackage(id = "examples.csv", name = "CSV query", publisher = "ToolGate Examples",
        description = "Filters, sorts and aggregates CSV files from an approved data directory.")
@Global(name = "DataDir", type = ValueType.STRING, defaultValue = "data",
        description = "Directory holding the CSV files callers may query. Use an absolute path under a Tool Host.")
@Global(name = "MaxFileMegabytes", type = ValueType.INT, defaultValue = "100", min = "1", max = "2048",
        description = "Largest CSV file a call reads.")
public class CsvQuery {
    static final Pattern FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._ -]{0,199}\\.(csv|tsv|txt)", Pattern.CASE_INSENSITIVE);
    static final Pattern CONDITION = Pattern.compile("^\\s*([^=!<>~]+?)\\s*(=|!=|>=|<=|>|<|~)\\s*(.*)$");

    public record CsvFile(
            @Param(description = "File name") String file,
            @Param(description = "Size in bytes") long sizeBytes,
            @Param(description = "Column names from the header row") List<String> columns) {}

    public record CsvFiles(@Param(description = "Files callers may query") List<CsvFile> files) {}

    @ServerTool(name = "files", title = "List CSV files",
            description = "Lists the CSV files that csv.query can read, with their columns.", effect = EffectSafety.READ_ONLY)
    public CsvFiles files(ToolContext context) {
        Path dir = dataDir(context);
        try (Stream<Path> list = Files.list(dir)) {
            List<CsvFile> files = new ArrayList<>();
            for (Path p : list.sorted().toList()) {
                String name = p.getFileName().toString();
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) || !FILE.matcher(name).matches()) continue;
                List<String> header;
                try (BufferedReader in = reader(p)) {
                    String first = in.readLine();
                    header = first == null ? List.of() : parse(first, delimiter(first));
                }
                files.add(new CsvFile(name, Files.size(p), header));
            }
            return new CsvFiles(files);
        } catch (IOException e) {
            throw new ToolConfigurationError("DataDir cannot be read");
        }
    }

    public record QueryResult(
            @Param(description = "Column names of the rows") List<String> columns,
            @Param(description = "Result rows, each a list of values in column order") List<List<String>> rows,
            @Param(description = "Rows that matched the conditions") long matchedRows,
            @Param(description = "Rows read from the file") long totalRows,
            @Param(description = "True when more results exist than limit allowed") boolean truncated) {}

    @ServerTool(name = "query", title = "Query a CSV file",
            description = "Reads a CSV file from the data directory and returns the rows matching every condition,"
                    + " with chosen columns, sorted. With groupBy it returns one row per group with a count and,"
                    + " with sum, a total.",
            useWhen = "Use it to answer questions from exported reports, such as 'failed orders over 500 by region'.",
            effect = EffectSafety.READ_ONLY)
    @Example(arguments = "{\"file\":\"../../etc/shadow\"}", expectedError = "INVALID_ARGUMENT")
    @Example(arguments = "{\"file\":\"orders.csv\",\"where\":[\"amount ?? 5\"]}", expectedError = "INVALID_ARGUMENT")
    public QueryResult query(
            @Param(description = "File name in the data directory, such as orders.csv", maxLength = 200) String file,
            @Param(description = "Conditions, all of which must hold. Each is <column><op><value> with op one of"
                    + " = != > >= < <= (numbers compare numerically) or ~ (contains, case-insensitive),"
                    + " such as status=FAILED, amount>500, customer~acme.", required = false) List<String> where,
            @Param(description = "Columns to return, in order; all columns when omitted", required = false)
            List<String> columns,
            @Param(description = "Column to group by; returns <column>, count and, with sum, sum", required = false,
                    maxLength = 200) String groupBy,
            @Param(description = "With groupBy, a numeric column to total per group", required = false, maxLength = 200)
            String sum,
            @Param(description = "Column to sort by; with groupBy also count or sum", required = false, maxLength = 200)
            String sortBy,
            @Param(description = "Sort largest first", defaultValue = "false") boolean descending,
            @Param(description = "Most rows to return", defaultValue = "100", min = "1", max = "5000") int limit,
            ToolContext context) {
        List<Condition> conditions = new ArrayList<>();
        for (String w : where == null ? List.<String>of() : where) conditions.add(Condition.parse(w));
        Path path = file(context, file);

        List<String> header;
        List<List<String>> matched = new ArrayList<>();
        long total = 0;
        try (BufferedReader in = reader(path)) {
            String first = in.readLine();
            if (first == null) throw new ToolInputError("the file is empty");
            char delimiter = delimiter(first);
            header = parse(first, delimiter);
            for (Condition c : conditions) c.bind(header);
            String line;
            while ((line = record(in)) != null) {
                if (line.isBlank()) continue;
                total++;
                List<String> row = parse(line, delimiter);
                if (conditions.stream().allMatch(c -> c.test(row))) matched.add(row);
            }
        } catch (IOException e) {
            throw new ToolInputError("the file cannot be read");
        }

        List<String> outColumns;
        List<List<String>> out;
        if (groupBy != null && !groupBy.isBlank()) {
            int g = index(header, groupBy);
            int s = sum == null || sum.isBlank() ? -1 : index(header, sum);
            Map<String, BigDecimal[]> groups = new LinkedHashMap<>();
            for (List<String> row : matched) {
                BigDecimal[] acc = groups.computeIfAbsent(cell(row, g), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                acc[0] = acc[0].add(BigDecimal.ONE);
                if (s >= 0) acc[1] = acc[1].add(number(cell(row, s)) == null ? BigDecimal.ZERO : number(cell(row, s)));
            }
            outColumns = s >= 0 ? List.of(header.get(g), "count", "sum") : List.of(header.get(g), "count");
            out = new ArrayList<>();
            for (Map.Entry<String, BigDecimal[]> e : groups.entrySet()) {
                out.add(s >= 0 ? List.of(e.getKey(), e.getValue()[0].toPlainString(), e.getValue()[1].stripTrailingZeros().toPlainString())
                        : List.of(e.getKey(), e.getValue()[0].toPlainString()));
            }
        } else {
            List<Integer> picked = new ArrayList<>();
            if (columns == null || columns.isEmpty()) {
                for (int i = 0; i < header.size(); i++) picked.add(i);
            } else {
                for (String c : columns) picked.add(index(header, c));
            }
            outColumns = picked.stream().map(header::get).toList();
            out = matched.stream().map(r -> picked.stream().map(i -> cell(r, i)).toList()).toList();
        }
        if (sortBy != null && !sortBy.isBlank()) {
            int by = index(outColumns, sortBy);
            Comparator<List<String>> order = (a, b) -> compare(cell(a, by), cell(b, by));
            out = new ArrayList<>(out);
            out.sort(descending ? order.reversed() : order);
        }
        return new QueryResult(outColumns, List.copyOf(out.subList(0, Math.min(limit, out.size()))), matched.size(), total,
                out.size() > limit);
    }

    /** One filter, such as amount>500. */
    static final class Condition {
        final String column;
        final String op;
        final String value;
        int index = -1;

        Condition(String column, String op, String value) {
            this.column = column;
            this.op = op;
            this.value = value;
        }

        static Condition parse(String text) {
            Matcher m = CONDITION.matcher(text == null ? "" : text);
            if (!m.matches()) throw new ToolInputError("condition '" + text + "' is not <column><op><value>, such as amount>500");
            return new Condition(m.group(1), m.group(2), m.group(3).trim());
        }

        void bind(List<String> header) {
            index = index(header, column);
        }

        boolean test(List<String> row) {
            String cell = cell(row, index);
            return switch (op) {
                case "=" -> cell.equalsIgnoreCase(value);
                case "!=" -> !cell.equalsIgnoreCase(value);
                case "~" -> cell.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
                default -> {
                    int c = compare(cell, value);
                    yield switch (op) {
                        case ">" -> c > 0;
                        case ">=" -> c >= 0;
                        case "<" -> c < 0;
                        default -> c <= 0;
                    };
                }
            };
        }
    }

    // ---- CSV ----

    static BufferedReader reader(Path p) throws IOException {
        return new BufferedReader(new InputStreamReader(Files.newInputStream(p), StandardCharsets.UTF_8));
    }

    /** One record, joining physical lines while a quoted field is open. */
    static String record(BufferedReader in) throws IOException {
        String line = in.readLine();
        if (line == null) return null;
        StringBuilder record = new StringBuilder(line);
        while (quotes(record) % 2 == 1) {
            String next = in.readLine();
            if (next == null) break;
            record.append('\n').append(next);
        }
        return record.toString();
    }

    static long quotes(CharSequence s) {
        return s.chars().filter(c -> c == '"').count();
    }

    static char delimiter(String header) {
        char best = ',';
        long most = -1;
        for (char c : new char[] {',', ';', '\t', '|'}) {
            long n = header.chars().filter(x -> x == c).count();
            if (n > most) {
                most = n;
                best = c;
            }
        }
        return best;
    }

    /** RFC 4180 fields: quotes around a field, and "" for a quote inside one. */
    static List<String> parse(String line, char delimiter) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == delimiter) {
                fields.add(field.toString().trim());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString().trim());
        if (!fields.isEmpty() && fields.get(0).startsWith("﻿")) fields.set(0, fields.get(0).substring(1));
        return fields;
    }

    static int index(List<String> header, String column) {
        for (int i = 0; i < header.size(); i++) if (header.get(i).equalsIgnoreCase(column.trim())) return i;
        throw new ToolInputError("no column '" + column + "'; the columns are " + header);
    }

    static String cell(List<String> row, int i) {
        return i < row.size() ? row.get(i) : "";
    }

    static BigDecimal number(String s) {
        try {
            return new BigDecimal(s.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Numbers numerically, anything else as text, ignoring case. */
    static int compare(String a, String b) {
        BigDecimal x = number(a);
        BigDecimal y = number(b);
        if (x != null && y != null) return x.compareTo(y);
        return a.compareToIgnoreCase(b);
    }

    // ---- files ----

    static Path dataDir(ToolContext context) {
        Path dir = Path.of(context.global().getStringValue("DataDir"));
        if (!Files.isDirectory(dir)) throw new ToolConfigurationError("DataDir is not a directory");
        try {
            return dir.toRealPath();
        } catch (IOException e) {
            throw new ToolConfigurationError("DataDir cannot be resolved");
        }
    }

    static Path file(ToolContext context, String name) {
        if (!FILE.matcher(name).matches()) throw new ToolInputError("file must be a .csv, .tsv or .txt file name, without a directory");
        Path dir = dataDir(context);
        Path p = dir.resolve(name);
        try {
            if (!Files.isRegularFile(p) || !p.toRealPath().getParent().equals(dir)) throw new ToolInputError("no file " + name + " in the data directory");
            if (Files.size(p) > context.global().getIntValue("MaxFileMegabytes") * 1024L * 1024L) {
                throw new ToolInputError(name + " is larger than MaxFileMegabytes");
            }
            return p;
        } catch (IOException e) {
            throw new ToolInputError("no file " + name + " in the data directory");
        }
    }
}
