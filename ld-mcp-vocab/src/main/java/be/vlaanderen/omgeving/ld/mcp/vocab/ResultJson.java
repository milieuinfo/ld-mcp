package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import be.vlaanderen.omgeving.ld.mcp.vocab.ValidationResult.Certainty;
import be.vlaanderen.omgeving.ld.mcp.vocab.ValidationResult.Finding;
import be.vlaanderen.omgeving.ld.mcp.vocab.ValidationResult.Step;

/**
 * The machine-readable form of a {@link ValidationResult}: maps and lists that serialise to JSON
 * (the CLI's {@code --format json}) and that the MCP SDK sends as {@code structuredContent}. The
 * structure is described by {@code validation-result.schema.json} (bundled with the jar, and in
 * {@code docs/}); {@link #SCHEMA_VERSION} changes when it does.
 */
public final class ResultJson {

    /** The version of the JSON structure. */
    public static final String SCHEMA_VERSION = "1.0";

    private ResultJson() {
    }

    /**
     * Converts one result.
     *
     * @param r the result
     * @return the result as nested maps and lists
     */
    public static Map<String, Object> toMap(ValidationResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", SCHEMA_VERSION);
        m.put("file", r.header().file());
        m.put("triples", r.header().triples());
        if (r.header().parseError() != null) {
            m.put("parseError", r.header().parseError());
        }
        ValidationResult.Configuration c = r.header().configuration();
        if (c != null) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("serverVersion", c.serverVersion());
            cm.put("contentHash", TextReport.contentHash(c));
            List<Object> files = new ArrayList<>();
            c.files().forEach(f -> files.add(ordered("role", f.role(), "path", f.path(), "sha256", f.sha256())));
            cm.put("files", files);
            cm.put("options", new TreeMap<>(c.options()));
            cm.put("problems", c.problems());
            cm.put("notTranslated", c.notTranslated());
            m.put("configuration", cm);
        }
        List<Object> steps = new ArrayList<>();
        for (Step s : r.steps()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("kind", s.kind().name());
            sm.put("status", s.status().name());
            sm.put("stats", new TreeMap<>(s.stats()));
            List<Object> findings = new ArrayList<>();
            s.findings().forEach(f -> findings.add(finding(f)));
            sm.put("findings", findings);
            steps.add(sm);
        }
        m.put("steps", steps);
        m.put("summary", summary(List.of(r)));
        return m;
    }

    /**
     * Counts the findings per certainty.
     *
     * @param results the results
     * @return {@code error}, {@code suspect} and {@code hint} counts, and the number of parse errors
     */
    public static Map<String, Object> summary(List<ValidationResult> results) {
        long[] counts = new long[Certainty.values().length];
        long parseErrors = 0;
        for (ValidationResult r : results) {
            if (r.header().parseError() != null) {
                parseErrors++;
            }
            r.steps().forEach(s -> s.findings().forEach(f -> counts[f.certainty().ordinal()]++));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", counts[Certainty.ERROR.ordinal()]);
        m.put("suspect", counts[Certainty.SUSPECT.ordinal()]);
        m.put("hint", counts[Certainty.HINT.ordinal()]);
        m.put("parseErrors", parseErrors);
        return m;
    }

    private static Map<String, Object> finding(Finding f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("certainty", TextReport.tag(f.certainty()));
        m.put("code", f.code());
        m.put("focus", f.focus());
        if (f.focusIri() != null) {
            m.put("focusIri", f.focusIri());
        }
        if (f.path() != null) {
            m.put("path", f.path());
        }
        if (f.pathIri() != null) {
            m.put("pathIri", f.pathIri());
        }
        m.put("message", f.message());
        m.put("hints", f.hints());
        if (f.severity() != null) {
            m.put("severity", f.severity());
        }
        return m;
    }

    /**
     * Reads the bundled JSON Schema of a result.
     *
     * @return the schema as nested maps and lists
     */
    public static Map<String, Object> schema() {
        try (java.io.InputStream in = ResultJson.class.getResourceAsStream("/validation-result.schema.json")) {
            if (in == null) {
                throw new IllegalStateException("validation-result.schema.json is not on the classpath");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) plain(org.apache.jena.atlas.json.JSON.parse(in));
            return m;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Converts Jena's JSON values to maps, lists, strings, numbers and booleans. */
    private static Object plain(org.apache.jena.atlas.json.JsonValue v) {
        if (v.isObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            v.getAsObject().entrySet().forEach(e -> m.put(e.getKey(), plain(e.getValue())));
            return m;
        }
        if (v.isArray()) {
            List<Object> l = new ArrayList<>();
            v.getAsArray().forEach(x -> l.add(plain(x)));
            return l;
        }
        if (v.isString()) {
            return v.getAsString().value();
        }
        if (v.isNumber()) {
            return v.getAsNumber().value();
        }
        if (v.isBoolean()) {
            return v.getAsBoolean().value();
        }
        return null;
    }

    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /**
     * Writes nested maps, lists, strings, numbers, booleans and {@code null} as JSON.
     *
     * @param value  the value
     * @param indent whether to indent (two spaces) or write one line
     * @return the JSON text
     */
    public static String write(Object value, boolean indent) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, indent, 0);
        return sb.toString();
    }

    private static void write(Object v, StringBuilder sb, boolean indent, int depth) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            string(s, sb);
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sb.append(first ? "" : ",");
                newline(sb, indent, depth + 1);
                string(String.valueOf(e.getKey()), sb);
                sb.append(indent ? ": " : ":");
                write(e.getValue(), sb, indent, depth + 1);
                first = false;
            }
            if (!map.isEmpty()) {
                newline(sb, indent, depth);
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object o : list) {
                sb.append(first ? "" : ",");
                newline(sb, indent, depth + 1);
                write(o, sb, indent, depth + 1);
                first = false;
            }
            if (!first) {
                newline(sb, indent, depth);
            }
            sb.append(']');
        } else {
            string(String.valueOf(v), sb);
        }
    }

    private static void newline(StringBuilder sb, boolean indent, int depth) {
        if (indent) {
            sb.append('\n').append("  ".repeat(depth));
        }
    }

    private static void string(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
