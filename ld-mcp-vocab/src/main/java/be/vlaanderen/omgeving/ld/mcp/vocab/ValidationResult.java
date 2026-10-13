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
        /** Class disjointness with OWLMiniReasoner. */
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
     */
    public record Configuration(String serverVersion, List<LoadedFile> files, Map<String, String> options,
                                List<String> problems) {
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
     * @param focus     the resource it is about (an IRI, or a node as the report shows it)
     * @param path      the property path, for SHACL; otherwise {@code null}
     * @param message   the message
     * @param hints     further lines: near-miss hints and suggestions
     * @param severity  for SHACL the severity name ({@code violation}, {@code warning}, …); otherwise
     *                  {@code null}
     */
    public record Finding(Certainty certainty, String code, String focus, String path, String message,
                          List<String> hints, String severity) {

        /**
         * Copies the hints.
         *
         * @param certainty how sure it is
         * @param code      a stable identifier
         * @param focus     the resource
         * @param path      the property path, or {@code null}
         * @param message   the message
         * @param hints     further lines
         * @param severity  the SHACL severity, or {@code null}
         */
        public Finding {
            hints = List.copyOf(hints);
        }
    }
}
