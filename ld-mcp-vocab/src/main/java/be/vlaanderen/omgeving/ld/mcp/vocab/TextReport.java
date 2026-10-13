package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import be.vlaanderen.omgeving.ld.mcp.vocab.ValidationResult.Finding;
import be.vlaanderen.omgeving.ld.mcp.vocab.ValidationResult.Status;
import be.vlaanderen.omgeving.ld.mcp.vocab.ValidationResult.Step;

/**
 * Renders a {@link ValidationResult} as the text report for the language model: one marker per
 * step, at most {@value #MAX_MESSAGES} messages per step.
 */
public final class TextReport {

    /** Maximum number of messages per step; the remainder is only counted. */
    static final int MAX_MESSAGES = 50;

    private TextReport() {
    }

    /**
     * Renders a result.
     *
     * @param result the result
     * @return the report
     */
    public static String render(ValidationResult result) {
        ValidationResult.Header h = result.header();
        if (h.parseError() != null) {
            return "PARSE ERROR in " + h.file() + ": " + h.parseError();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Validation: ").append(h.file()).append(" (").append(h.triples()).append(" triples)\n");
        if (h.configuration() != null) {
            sb.append(configurationLine(h.configuration())).append('\n');
        }
        for (Step step : result.steps()) {
            switch (step.kind()) {
                case VOCAB -> vocab(step, sb);
                case INFERENCE -> inference(step, sb);
                case DISJOINTNESS -> disjointness(step, sb);
                case SHACL -> shacl(step, sb);
            }
        }
        return sb.toString();
    }

    private static void vocab(Step step, StringBuilder sb) {
        if (step.status() == Status.OK) {
            sb.append("\n[VOCAB] ok\n");
            return;
        }
        sb.append("\n[VOCAB ERROR] ").append(step.findings().size()).append(" unknown term(s)\n");
        List<String> lines = new ArrayList<>();
        for (Finding f : step.findings()) {
            StringBuilder line = new StringBuilder("- ").append(f.message());
            f.hints().forEach(hint -> line.append("\n    ").append(hint));
            lines.add(line.toString());
        }
        appendCapped(sb, lines);
    }

    private static void inference(Step step, StringBuilder sb) {
        if (step.status() == Status.SKIPPED) {
            sb.append("\n[INFERENCE] skipped (no --rules)\n");
        } else {
            sb.append("\n[INFERENCE] ").append(step.stats().get("inferred")).append(" inferred triples\n");
        }
    }

    private static void disjointness(Step step, StringBuilder sb) {
        if (step.status() == Status.OK) {
            sb.append("\n[OWL] consistent\n");
            return;
        }
        boolean valid = Boolean.TRUE.equals(step.stats().get("valid"));
        sb.append("\n[MODEL INVALID] ").append(step.findings().size()).append(" message(s)")
                .append(valid ? " (warnings only)" : "").append('\n');
        appendCapped(sb, step.findings().stream().map(f -> "- " + f.message()).toList());
    }

    private static void shacl(Step step, StringBuilder sb) {
        if (step.status() == Status.SKIPPED) {
            sb.append("\n[SHACL] skipped (no --shapes or --generate-shapes)\n");
            return;
        }
        if (step.status() == Status.OK) {
            sb.append("\n[SHACL] conforms\n");
            return;
        }
        // Violations first, then warnings, then info, then other severities; only those after
        // violations are marked.
        Map<String, List<String>> bySeverity = new LinkedHashMap<>();
        for (String s : List.of("violation", "warning", "info")) {
            bySeverity.put(s, new ArrayList<>());
        }
        step.findings().stream().map(Finding::severity).filter(s -> !bySeverity.containsKey(s)).sorted()
                .forEach(s -> bySeverity.put(s, new ArrayList<>()));
        for (Finding f : step.findings()) {
            bySeverity.get(f.severity()).add("- " + (f.severity().equals("violation") ? "" : "[" + f.severity() + "] ")
                    + shaclLine(f));
        }
        List<String> counts = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        bySeverity.forEach((severity, entries) -> {
            if (!entries.isEmpty() || severity.equals("violation")) {
                counts.add(entries.size() + " " + severity + "(s)");
            }
            lines.addAll(entries);
        });
        sb.append("\n[SHACL] ").append(String.join(", ", counts)).append('\n');
        appendCapped(sb, lines);
    }

    /** The prefix of the configuration line, by which a caller can recognise it. */
    static final String CONFIGURATION = "Configuration: ";

    /**
     * Summarises the configuration on one line: the server version, how many files of each role,
     * the switches that are on, and a content hash of all loaded files (the full list with a
     * SHA-256 per file is in the structured result).
     *
     * @param c the configuration
     * @return the line, starting with {@value #CONFIGURATION}
     */
    static String configurationLine(ValidationResult.Configuration c) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String role : List.of("ontology", "rules", "shapes", "prefix-registry")) {
            counts.put(role, 0);
        }
        c.files().forEach(f -> counts.merge(f.role(), 1, Integer::sum));
        List<String> parts = new ArrayList<>();
        parts.add(counts.get("ontology") + " ontology file(s)");
        if (counts.get("rules") > 0) {
            parts.add(counts.get("rules") + " rules file(s)");
        }
        if (counts.get("shapes") > 0) {
            parts.add(counts.get("shapes") + " shapes file(s)");
        }
        if (counts.get("prefix-registry") > 0) {
            parts.add("prefix registry");
        }
        c.options().forEach((k, v) -> {
            if (v.equals("true")) {
                parts.add(k);
            } else if (!v.equals("false")) {
                parts.add(k + " " + v);
            }
        });
        return CONFIGURATION + "ld-mcp-vocab " + c.serverVersion() + "; " + String.join(", ", parts)
                + "; content hash " + contentHash(c);
    }

    /**
     * A short hash over the roles and content hashes of the loaded files, independent of where they
     * lie: the same files give the same hash on every machine.
     */
    static String contentHash(ValidationResult.Configuration c) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            c.files().stream().map(f -> f.role() + " " + f.sha256()).sorted()
                    .forEach(line -> sha.update((line + "\n").getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(sha.digest()).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The text of a SHACL finding after its severity mark; also the order of the findings.
     *
     * @param f a SHACL finding
     * @return focus, path and message
     */
    static String shaclLine(Finding f) {
        return f.focus() + "  path: " + (f.path() == null ? "-" : f.path()) + "  — " + f.message();
    }

    private static void appendCapped(StringBuilder sb, List<String> lines) {
        lines.stream().limit(MAX_MESSAGES).forEach(l -> sb.append(l).append('\n'));
        if (lines.size() > MAX_MESSAGES) {
            sb.append("… and ").append(lines.size() - MAX_MESSAGES).append(" more messages\n");
        }
    }
}
