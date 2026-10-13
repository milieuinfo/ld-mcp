package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.jena.shared.PrefixMapping;

import be.vlaanderen.omgeving.ld.mcp.core.OntologyLoader;
import be.vlaanderen.omgeving.ld.mcp.core.ServerArgs;
import be.vlaanderen.omgeving.ld.mcp.vocab.ValidationResult.Certainty;

/**
 * The command line, for CI:
 * <pre>
 * java -jar ld-mcp-vocab.jar validate [server options] [--format text|json|ttl]
 *     [--fail-on error|suspect] [--extensions ttl,jsonld] [--include-trig] &lt;file or directory&gt;…
 * java -jar ld-mcp-vocab.jar generate-shapes [server options] [--out shapes.ttl]
 * </pre>
 * The server options are those of the MCP server ({@link VocabConfig}). Exit codes: 0 no findings
 * at or above the threshold, 1 findings, 2 an error in the configuration, a configuration problem
 * (e.g. an ontology file that does not parse), or a data file that does not parse.
 */
final class Cli {

    /** Exit code: nothing at or above the threshold. */
    static final int OK = 0;
    /** Exit code: findings at or above the threshold. */
    static final int FINDINGS = 1;
    /** Exit code: configuration error, configuration problem or parse error. */
    static final int ERROR = 2;

    /** Extensions of data files found in a directory (as for --ontologies). */
    private static final Set<String> DATA = Set.of(".ttl", ".rdf", ".owl", ".nt", ".n3", ".jsonld");

    private Cli() {
    }

    /**
     * Runs a command.
     *
     * @param args    the command and its arguments
     * @param version the version of the jar
     * @param out     where results go
     * @param err     where messages go
     * @return the exit code
     */
    static int run(String[] args, String version, PrintStream out, PrintStream err) {
        String command = args[0];
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
        try {
            return switch (command) {
                case "validate" -> validate(rest, version, out, err);
                case "generate-shapes" -> generateShapes(rest, version, out, err);
                default -> throw new IllegalArgumentException("Unknown command '" + command
                        + "'; known: validate, generate-shapes (without a command: the MCP server)");
            };
        } catch (IllegalArgumentException | UncheckedIOException e) {
            err.println("ld-mcp-vocab: " + e.getMessage());
            return ERROR;
        }
    }

    // ---------------------------------------------------------------- validate

    private static int validate(String[] args, String version, PrintStream out, PrintStream err) {
        Map<String, ServerArgs.Kind> known = new HashMap<>(VocabConfig.ARGUMENTS);
        known.put("format", ServerArgs.Kind.SINGLE);
        known.put("fail-on", ServerArgs.Kind.SINGLE);
        known.put("include-trig", ServerArgs.Kind.FLAG);
        known.put("extensions", ServerArgs.Kind.SINGLE);
        ServerArgs parsed = ServerArgs.parse(args, known, true);
        String format = parsed.first("format").orElse("text").toLowerCase(Locale.ROOT);
        if (!Set.of("text", "json", "ttl").contains(format)) {
            throw new IllegalArgumentException("--format must be text, json or ttl, not '" + format + "'");
        }
        String failOn = parsed.first("fail-on").orElse("error").toLowerCase(Locale.ROOT);
        Certainty threshold = switch (failOn) {
            case "error" -> Certainty.ERROR;
            case "suspect" -> Certainty.SUSPECT;
            default -> throw new IllegalArgumentException("--fail-on must be error or suspect, not '" + failOn + "'");
        };
        if (parsed.positional().isEmpty()) {
            throw new IllegalArgumentException("validate needs one or more files or directories");
        }
        VocabConfig config = VocabConfig.from(parsed);
        Set<String> extensions = parsed.first("extensions").map(Cli::extensions).orElse(DATA);
        List<Path> files = dataFiles(parsed.positional(), extensions, parsed.flag("include-trig"));
        if (files.isEmpty()) {
            throw new IllegalArgumentException("no data files found in " + parsed.positional());
        }

        Workspace.State state = config.workspace(version).current();
        List<ValidationResult> results = new ArrayList<>();
        for (Path f : files) {
            results.add(state.validator().check(f, f.toString()));
        }

        switch (format) {
            case "json" -> out.println(ResultJson.write(json(results, version, state), true));
            case "ttl" -> out.print(ResultTurtle.write(results));
            default -> {
                StringBuilder sb = new StringBuilder();
                results.forEach(r -> sb.append(TextReport.render(r).stripTrailing()).append("\n\n"));
                sb.append(summaryLine(results));
                out.println(state.withProblems(sb.toString()));
            }
        }
        if (!state.problems().isEmpty()) {
            err.println("ld-mcp-vocab: configuration problems (see the report); exit code 2");
        }
        return exitCode(results, threshold, !state.problems().isEmpty());
    }

    /** The exit code: 2 for problems or parse errors, else 1 for findings at or above the threshold. */
    static int exitCode(List<ValidationResult> results, Certainty threshold, boolean problems) {
        if (problems || results.stream().anyMatch(r -> r.header().parseError() != null)) {
            return ERROR;
        }
        boolean above = results.stream().flatMap(r -> r.steps().stream()).flatMap(s -> s.findings().stream())
                .anyMatch(f -> f.certainty().compareTo(threshold) <= 0);
        return above ? FINDINGS : OK;
    }

    private static String summaryLine(List<ValidationResult> results) {
        Map<String, Object> s = ResultJson.summary(results);
        return "Summary: " + results.size() + " file(s), " + s.get("error") + " error(s), " + s.get("suspect")
                + " suspect, " + s.get("hint") + " hint(s), " + s.get("parseErrors") + " parse error(s)";
    }

    private static Map<String, Object> json(List<ValidationResult> results, String version, Workspace.State state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", ResultJson.SCHEMA_VERSION);
        m.put("tool", "ld-mcp-vocab");
        m.put("version", version);
        m.put("problems", state.problems());
        List<Object> list = new ArrayList<>();
        results.forEach(r -> list.add(ResultJson.toMap(r)));
        m.put("results", list);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("files", results.size());
        summary.putAll(ResultJson.summary(results));
        m.put("summary", summary);
        return m;
    }

    /** "ttl, jsonld" → {".ttl", ".jsonld"}. */
    private static Set<String> extensions(String list) {
        Set<String> out = new java.util.TreeSet<>();
        for (String e : list.split(",")) {
            String x = e.strip().toLowerCase(Locale.ROOT);
            if (!x.isEmpty()) {
                out.add(x.startsWith(".") ? x : "." + x);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("--extensions needs at least one extension, e.g. ttl");
        }
        return out;
    }

    /**
     * The given files, and the data files in the given directories (recursively, sorted).
     *
     * @param given       files and directories
     * @param extensions  extensions of data files in a directory, e.g. {@code .ttl}
     * @param includeTrig whether {@code .trig} files in a directory count as well
     * @return the files
     */
    static List<Path> dataFiles(List<String> given, Set<String> extensions, boolean includeTrig) {
        List<Path> out = new ArrayList<>();
        for (String g : given) {
            Path p = Path.of(g);
            if (Files.isRegularFile(p)) {
                out.add(p);
            } else if (Files.isDirectory(p)) {
                try (Stream<Path> walk = Files.walk(p)) {
                    walk.filter(Files::isRegularFile).filter(f -> isData(f, extensions, includeTrig)).sorted()
                            .forEach(out::add);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else {
                throw new IllegalArgumentException("not found: " + g);
            }
        }
        return out;
    }

    private static boolean isData(Path f, Set<String> extensions, boolean includeTrig) {
        String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
        return extensions.stream().anyMatch(name::endsWith) || includeTrig && name.endsWith(".trig");
    }

    // ---------------------------------------------------------------- generate-shapes

    private static int generateShapes(String[] args, String version, PrintStream out, PrintStream err) {
        Map<String, ServerArgs.Kind> known = new HashMap<>(VocabConfig.ARGUMENTS);
        known.put("out", ServerArgs.Kind.SINGLE);
        ServerArgs parsed = ServerArgs.parse(args, known);
        VocabConfig config = VocabConfig.from(parsed);
        Path target = parsed.first("out").map(Path::of).orElse(null);

        // The output file is never an input: else its own hash would change the next export.
        Set<Path> exclude = new java.util.HashSet<>(config.shapes());
        if (config.prefixRegistry() != null) {
            exclude.add(config.prefixRegistry());
        }
        if (target != null) {
            exclude.add(target);
        }
        OntologyLoader.Loaded loaded = OntologyLoader.loadReporting(config.ontologies(), exclude);
        loaded.problems().forEach(p -> err.println("ld-mcp-vocab: " + p));
        ShaclGenerator.Result result = ShaclGenerator.translate(loaded.model());

        StringBuilder sb = new StringBuilder();
        sb.append("# SHACL shapes generated by ld-mcp-vocab ").append(version)
                .append(" from the OWL restrictions of these files (SHA-256):\n");
        for (Path f : OntologyLoader.files(config.ontologies(), exclude)) {
            sb.append("#   ").append(sha256(f)).append("  ").append(f.toString().replace('\\', '/')).append('\n');
        }
        sb.append("# ").append(result.nodeShapes()).append(" node shapes, ").append(result.propertyShapes())
                .append(" property shapes. A profile reading of OWL; see the README of ld-mcp.\n");
        sb.append("# Not translated (").append(result.notTranslated().size()).append("):\n");
        result.notTranslated().forEach(n -> sb.append("#   ").append(Untrusted.literal(n)).append('\n'));
        sb.append('\n');
        PrefixMapping prefixes = PrefixMapping.Factory.create().setNsPrefixes(loaded.model().getNsPrefixMap())
                .setNsPrefixes(result.shapes().getNsPrefixMap());
        sb.append(CanonicalTurtle.write(result.shapes(), prefixes));

        if (target == null) {
            out.print(sb);
        } else {
            try {
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.writeString(target, sb.toString());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            err.println("ld-mcp-vocab: " + result.nodeShapes() + " node shapes written to " + target);
        }
        if (!loaded.problems().isEmpty()) {
            return ERROR;
        }
        return OK;
    }

    private static String sha256(Path f) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
