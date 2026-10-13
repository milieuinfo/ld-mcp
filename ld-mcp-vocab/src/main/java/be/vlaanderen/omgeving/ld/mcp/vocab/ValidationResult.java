package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.util.List;
import java.util.Map;

/**
 * The outcome of validating one data file, independent of how it is shown: {@link TextReport}
 * makes the text for the language model; the CLI and {@code structuredContent} use the same result.
 *
 * @param header what was validated, and with which configuration
 * @param steps  the steps in the order they ran; empty if the file could not be parsed
 */
public record ValidationResult(Header header, List<Step> steps) {

    /**
     * Copies the steps.
     *
     * @param header what was validated
     * @param steps  the steps
     */
    public ValidationResult {
        steps = List.copyOf(steps);
    }

    /**
     * How sure a finding is.
     */
    public enum Certainty {
        /** Established: the data is wrong (an unknown term in a loaded namespace, a violation). */
        ERROR,
        /** Probably wrong, but check: e.g. an IRI that differs from a known term only in http/https. */
        SUSPECT,
        /** A pointer, not a verdict: e.g. a namespace that is known but not loaded. */
        HINT
    }

    /** The steps of a validation. */
    public enum Kind {
        /** Terms against the loaded ontologies. */
        VOCAB,
        /** Rule inference with {@code --rules}. */
        INFERENCE,
        /** Class disjointness, following the class hierarchy. */
        DISJOINTNESS,
        /** SHACL. */
        SHACL
    }

    /** The outcome of a step. */
    public enum Status {
        /** Ran, nothing to report. */
        OK,
        /** Did not run (not configured). */
        SKIPPED,
        /** Ran, with findings. */
        FINDINGS
    }

    /**
     * What was validated.
     *
     * @param file          how the file is named in the report
     * @param triples       the number of triples, or -1 if the file could not be parsed
     * @param parseError    the parse error, or {@code null}
     * @param configuration the configuration, or {@code null} if not known
     */
    public record Header(String file, long triples, String parseError, Configuration configuration) {
    }

    /**
     * The configuration a validation ran with, so that a result can be reproduced.
     *
     * @param serverVersion the version of the jar
     * @param files         the loaded files, with their SHA-256
     * @param options       the active options (rules, shapes, generate-shapes, …)
     * @param problems      what could not be loaded
     * @param notTranslated the OWL restrictions that {@code --generate-shapes} could not translate
     */
    public record Configuration(String serverVersion, List<LoadedFile> files, Map<String, String> options,
                                List<String> problems, List<String> notTranslated) {

        /**
         * A configuration without generated shapes to report on.
         *
         * @param serverVersion the version of the jar
         * @param files         the loaded files
         * @param options       the active options
         * @param problems      what could not be loaded
         */
        public Configuration(String serverVersion, List<LoadedFile> files, Map<String, String> options,
                             List<String> problems) {
            this(serverVersion, files, options, problems, List.of());
        }
    }

    /**
     * One loaded file.
     *
     * @param role   {@code ontology}, {@code rules}, {@code shapes} or {@code prefix-registry}
     * @param path   the absolute path
     * @param sha256 the SHA-256 of its content, in hexadecimal
     */
    public record LoadedFile(String role, String path, String sha256) {
    }

    /**
     * One step.
     *
     * @param kind     which step
     * @param status   its outcome
     * @param findings what it found, all of them (the text report caps the number shown)
     * @param stats    figures of the step, e.g. {@code inferred} for the inference step
     */
    public record Step(Kind kind, Status status, List<Finding> findings, Map<String, Object> stats) {

        /**
         * Copies the findings and figures.
         *
         * @param kind     which step
         * @param status   its outcome
         * @param findings what it found
         * @param stats    figures of the step
         */
        public Step {
            findings = List.copyOf(findings);
            stats = Map.copyOf(stats);
        }
    }

    /**
     * One finding.
     *
     * @param certainty how sure it is
     * @param code      a stable identifier of the kind of finding, e.g. {@code unknown-property}
     * @param focus     the resource it is about, as the report shows it (a prefixed name where one fits)
     * @param path      the property path as the report shows it, for SHACL; otherwise {@code null}
     * @param message   the message
     * @param hints     further lines: near-miss hints and suggestions
     * @param severity  for SHACL the severity name ({@code violation}, {@code warning}, …); otherwise
     *                  {@code null}
     * @param focusIri  the full IRI of the resource, or {@code null} for a blank node or literal
     * @param pathIri   the full IRI of the path if it is one property, otherwise {@code null}
     */
    public record Finding(Certainty certainty, String code, String focus, String path, String message,
                          List<String> hints, String severity, String focusIri, String pathIri) {

        /**
         * Copies the hints.
         *
         * @param certainty how sure it is
         * @param code      a stable identifier
         * @param focus     the resource as shown
         * @param path      the property path as shown, or {@code null}
         * @param message   the message
         * @param hints     further lines
         * @param severity  the SHACL severity, or {@code null}
         * @param focusIri  the full IRI of the resource, or {@code null}
         * @param pathIri   the full IRI of the path, or {@code null}
         */
        public Finding {
            hints = List.copyOf(hints);
        }

        /**
         * A finding whose focus and path are shown as full IRIs (or are none).
         *
         * @param certainty how sure it is
         * @param code      a stable identifier
         * @param focus     the full IRI of the resource
         * @param path      the property path, or {@code null}
         * @param message   the message
         * @param hints     further lines
         * @param severity  the SHACL severity, or {@code null}
         */
        public Finding(Certainty certainty, String code, String focus, String path, String message,
                       List<String> hints, String severity) {
            this(certainty, code, focus, path, message, hints, severity, focus, null);
        }
    }
}
