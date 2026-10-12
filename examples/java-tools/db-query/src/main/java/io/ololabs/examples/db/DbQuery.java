// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.db;

import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.ErrorDecl;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.Global;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.Secret;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.annotations.ValueType;
import io.ololabs.toolgate.sdk.context.ToolContext;
import io.ololabs.toolgate.sdk.errors.ToolBusinessError;
import io.ololabs.toolgate.sdk.errors.ToolConfigurationError;
import io.ololabs.toolgate.sdk.errors.ToolInputError;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lets a model read from a SQL database: list the tables, look up one row by key, or run a read-only query.
 *
 * <p>The example uses an embedded H2 database file, so it needs no network and runs the same standalone and under a
 * Tool Host. Read-only is enforced three ways, and an example for another database should keep all three: the
 * connection is opened read-only ({@code ACCESS_MODE_DATA=r} and {@link Connection#setReadOnly}); the account is an
 * ordinary user with SELECT grants only (an H2 admin could read server files through SQL functions); and the
 * statement must be a single SELECT or WITH that names none of the file and link functions. Rows, cell length and run
 * time are capped. {@code db.row} shows the safest pattern of all: the model chooses values, never SQL, and table and
 * column names are checked against the database's own catalog before they go into a statement.
 */
@ToolPackage(id = "examples.db", name = "Database query", publisher = "ToolGate Examples",
        description = "Lists tables, looks up rows and runs read-only SQL queries against an approved database.")
@Global(name = "DatabaseFile", type = ValueType.STRING, defaultValue = "data/demo",
        description = "H2 database file, without the .mv.db suffix. Use an absolute path under a Tool Host.")
@Global(name = "DbUser", type = ValueType.STRING, defaultValue = "reader",
        description = "Database account; give it SELECT grants only, never admin rights.")
@Secret(name = "DbPassword")
@Global(name = "AllowedTables", type = ValueType.STRING, defaultValue = "*",
        description = "Comma-separated tables db.tables and db.row may use, or * for every table the account can see.")
@Global(name = "MaxRows", type = ValueType.INT, defaultValue = "500", min = "1", max = "10000",
        description = "Most rows one call returns.")
@Global(name = "QueryTimeoutSeconds", type = ValueType.INT, defaultValue = "15", min = "1", max = "55",
        description = "Longest a statement may run.")
public class DbQuery {
    static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    /** Statement starts that only read. */
    static final Pattern READ_ONLY = Pattern.compile("^\\s*(SELECT|WITH)\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** Words a read-only query never needs; most read or write server files, or change the session. */
    static final Set<String> FORBIDDEN = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "CREATE", "DROP", "ALTER",
            "TRUNCATE", "GRANT", "REVOKE", "CALL", "EXECUTE", "SET", "SCRIPT", "RUNSCRIPT", "BACKUP", "SHUTDOWN",
            "FILE_READ", "FILE_WRITE", "CSVREAD", "CSVWRITE", "LINK_SCHEMA", "LOAD", "INTO");
    static final int MAX_CELL_CHARS = 1000;

    // ---- db.tables ----

    public record Column(
            @Param(description = "Column name") String name,
            @Param(description = "SQL type, such as INTEGER or CHARACTER VARYING") String type,
            @Param(description = "True when the column may hold NULL") boolean nullable) {}

    public record Table(
            @Param(description = "Table name") String name,
            @Param(description = "Columns in order") List<Column> columns) {}

    public record Tables(@Param(description = "Tables the tool may read") List<Table> tables) {}

    @ServerTool(name = "tables", title = "List tables",
            description = "Lists the tables this tool may read, with their columns and types.",
            useWhen = "Use it before writing a query, to learn the table and column names.",
            effect = EffectSafety.READ_ONLY)
    public Tables tables(ToolContext context) {
        Set<String> allowed = allowedTables(context);
        try (Connection c = connect(context);
                PreparedStatement s = c.prepareStatement("SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, IS_NULLABLE"
                        + " FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'PUBLIC' ORDER BY TABLE_NAME, ORDINAL_POSITION")) {
            Map<String, List<Column>> tables = new LinkedHashMap<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    String table = r.getString(1);
                    if (allowed != null && !allowed.contains(table.toUpperCase(Locale.ROOT))) continue;
                    tables.computeIfAbsent(table, t -> new ArrayList<>())
                            .add(new Column(r.getString(2), r.getString(3), "YES".equals(r.getString(4))));
                }
            }
            return new Tables(tables.entrySet().stream().map(e -> new Table(e.getKey(), e.getValue())).toList());
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    // ---- db.row ----

    public record Rows(
            @Param(description = "Column names") List<String> columns,
            @Param(description = "Rows, each a list of values in column order; NULL is shown as (null)") List<List<String>> rows,
            @Param(description = "True when more rows matched than were returned") boolean truncated) {}

    @ServerTool(name = "row", title = "Look up rows by key",
            description = "Returns the rows of a table whose column equals a value, such as the order with ID 1042"
                    + " or the customers in a city. No SQL needed.",
            useWhen = "Prefer it to db.query for simple lookups by id, email, status and similar.",
            effect = EffectSafety.READ_ONLY,
            errors = @ErrorDecl(code = "TABLE_NOT_FOUND", description = "No readable table or column has that name."))
    @Example(arguments = "{\"table\":\"orders; DROP TABLE orders\",\"column\":\"id\",\"value\":\"1\"}",
            expectedError = "INVALID_ARGUMENT")
    public Rows row(
            @Param(description = "Table name, as db.tables lists it", maxLength = 128) String table,
            @Param(description = "Column to match", maxLength = 128) String column,
            @Param(description = "Value the column must equal", maxLength = 1000) String value,
            @Param(description = "Most rows to return", defaultValue = "50", min = "1", max = "10000") int limit,
            ToolContext context) {
        if (!IDENTIFIER.matcher(table).matches() || !IDENTIFIER.matcher(column).matches()) {
            throw new ToolInputError("table and column must be plain names such as orders and customer_id");
        }
        Set<String> allowed = allowedTables(context);
        if (allowed != null && !allowed.contains(table.toUpperCase(Locale.ROOT))) {
            throw new ToolBusinessError("TABLE_NOT_FOUND", "No readable table is called " + table + ".");
        }
        try (Connection c = connect(context)) {
            // Identifiers cannot be bound as parameters, so take their exact spelling from the catalog instead.
            String[] names = catalogNames(c, table, column);
            String sql = "SELECT * FROM \"" + names[0] + "\" WHERE \"" + names[1] + "\" = ?";
            try (PreparedStatement s = c.prepareStatement(sql)) {
                s.setString(1, value);
                return read(s, Math.min(limit, context.global().getIntValue("MaxRows")), context);
            }
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    // ---- db.query ----

    @ServerTool(name = "query", title = "Run a read-only query",
            description = "Runs one read-only SQL SELECT (or WITH ... SELECT) and returns the columns and rows. Use ? for"
                    + " values and pass them in parameters, in order.",
            useWhen = "Use it for joins, filters and aggregates db.row cannot express. Call db.tables first.",
            effect = EffectSafety.READ_ONLY,
            errors = @ErrorDecl(code = "QUERY_FAILED", description = "The database rejected the query; the message says why."))
    @Example(arguments = "{\"sql\":\"DELETE FROM orders\"}", expectedError = "INVALID_ARGUMENT")
    @Example(arguments = "{\"sql\":\"SELECT FILE_READ('/etc/passwd')\"}", expectedError = "INVALID_ARGUMENT")
    public Rows query(
            @Param(description = "One SELECT statement, such as SELECT status, COUNT(*) FROM orders GROUP BY status",
                    minLength = 1, maxLength = 8000) String sql,
            @Param(description = "Values for the ? placeholders, in order", required = false) List<String> parameters,
            @Param(description = "Most rows to return", defaultValue = "100", min = "1", max = "10000") int limit,
            ToolContext context) {
        String statement = sql.trim();
        if (statement.endsWith(";")) statement = statement.substring(0, statement.length() - 1);
        checkReadOnly(statement);
        List<String> values = parameters == null ? List.of() : parameters;
        try (Connection c = connect(context); PreparedStatement s = c.prepareStatement(statement)) {
            for (int i = 0; i < values.size(); i++) s.setString(i + 1, values.get(i));
            return read(s, Math.min(limit, context.global().getIntValue("MaxRows")), context);
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    /** One SELECT or WITH, no second statement, no comments, and none of the forbidden words outside string literals. */
    static void checkReadOnly(String sql) {
        if (!READ_ONLY.matcher(sql).matches()) throw new ToolInputError("only a SELECT or WITH query may run");
        String code = sql.replaceAll("'([^']|'')*'", "''");
        if (code.contains(";")) throw new ToolInputError("only one statement may run");
        if (code.contains("--") || code.contains("/*")) throw new ToolInputError("comments are not allowed");
        for (String word : code.toUpperCase(Locale.ROOT).split("[^A-Z0-9_]+")) {
            if (FORBIDDEN.contains(word)) throw new ToolInputError(word + " is not allowed in a read-only query");
        }
    }

    // ---- shared ----

    static Connection connect(ToolContext context) throws SQLException {
        String file = context.global().getStringValue("DatabaseFile");
        if (!Files.exists(Path.of(file + ".mv.db"))) throw new ToolConfigurationError("DatabaseFile does not exist");
        if (file.contains(";")) throw new ToolConfigurationError("DatabaseFile may not contain ;");
        Properties p = new Properties();
        p.setProperty("user", context.global().getStringValue("DbUser"));
        p.setProperty("password", context.secret().getStringValue("DbPassword"));
        // IFEXISTS: never create a database; ACCESS_MODE_DATA=r: open the file read-only.
        Connection c = DriverManager.getConnection("jdbc:h2:file:" + file + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r", p);
        c.setReadOnly(true);
        return c;
    }

    static Rows read(PreparedStatement s, int limit, ToolContext context) throws SQLException {
        s.setQueryTimeout(context.global().getIntValue("QueryTimeoutSeconds"));
        s.setMaxRows(limit + 1);
        try (ResultSet r = s.executeQuery()) {
            ResultSetMetaData meta = r.getMetaData();
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) columns.add(meta.getColumnLabel(i));
            List<List<String>> rows = new ArrayList<>();
            boolean truncated = false;
            while (r.next()) {
                if (rows.size() == limit) {
                    truncated = true;
                    break;
                }
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= columns.size(); i++) {
                    String v = r.getString(i);
                    row.add(v == null ? "(null)" : v.length() > MAX_CELL_CHARS ? v.substring(0, MAX_CELL_CHARS) + "…" : v);
                }
                rows.add(row);
            }
            return new Rows(columns, rows, truncated);
        }
    }

    static String[] catalogNames(Connection c, String table, String column) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS"
                + " WHERE TABLE_SCHEMA = 'PUBLIC' AND UPPER(TABLE_NAME) = ? AND UPPER(COLUMN_NAME) = ?")) {
            s.setString(1, table.toUpperCase(Locale.ROOT));
            s.setString(2, column.toUpperCase(Locale.ROOT));
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new ToolBusinessError("TABLE_NOT_FOUND", "No readable table " + table + " with column " + column + ".");
                return new String[] {r.getString(1), r.getString(2)};
            }
        }
    }

    static Set<String> allowedTables(ToolContext context) {
        String list = context.global().getStringValue("AllowedTables").trim();
        if (list.equals("*")) return null;
        return Set.copyOf(Arrays.stream(list.split(",")).map(t -> t.trim().toUpperCase(Locale.ROOT)).filter(t -> !t.isEmpty()).toList());
    }

    /** The database's message helps the model fix its query; connection problems are configuration. */
    static RuntimeException failure(SQLException e) {
        if (e.getSQLState() != null && e.getSQLState().startsWith("08") || e.getErrorCode() == 28000 || e.getErrorCode() == 90146) {
            return new ToolConfigurationError("the database cannot be opened with the configured file, user and password");
        }
        String message = e.getMessage() == null ? "the query failed" : e.getMessage().split("; SQL statement")[0];
        return new ToolBusinessError("QUERY_FAILED", message.length() > 500 ? message.substring(0, 500) : message);
    }
}
