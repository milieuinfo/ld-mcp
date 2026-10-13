package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Fetches a document over HTTP with a given {@code Accept} header. An interface, so that the tools
 * that use the network can be tested with recorded responses. The implementation for the server
 * is {@link GuardedFetcher}.
 */
@FunctionalInterface
public interface Fetcher {

    /**
     * The outcome of a request.
     *
     * @param status      HTTP status code of the final response, after redirects
     * @param contentType value of the {@code Content-Type} header, or an empty string
     * @param body        response body as text
     * @param headers     all response headers, names in lower case
     */
    record Response(int status, String contentType, String body, Map<String, List<String>> headers) {

        /**
         * Normalises the header names to lower case.
         *
         * @param status      HTTP status code
         * @param contentType content type, or an empty string
         * @param body        body as text
         * @param headers     response headers
         */
        public Response {
            Map<String, List<String>> lower = new TreeMap<>();
            headers.forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), List.copyOf(v)));
            headers = Map.copyOf(lower);
        }

        /**
         * A response without other headers than the content type.
         *
         * @param status      HTTP status code
         * @param contentType content type, or an empty string
         * @param body        body as text
         */
        public Response(int status, String contentType, String body) {
            this(status, contentType, body, Map.of());
        }

        /**
         * Tells whether the request succeeded.
         *
         * @return whether the status is in the 2xx range
         */
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        /**
         * Reads the {@code Retry-After} header: a number of seconds or an HTTP date.
         *
         * @return how long the server asks to wait, if it says so (never negative)
         */
        public Optional<Duration> retryAfter() {
            List<String> values = headers.getOrDefault("retry-after", List.of());
            if (values.isEmpty()) {
                return Optional.empty();
            }
            String v = values.get(0).strip();
            try {
                long seconds = Long.parseLong(v);
                return Optional.of(Duration.ofSeconds(Math.max(0, seconds)));
            } catch (NumberFormatException e) {
                try {
                    Duration d = Duration.between(ZonedDateTime.now(),
                            ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME));
                    return Optional.of(d.isNegative() ? Duration.ZERO : d);
                } catch (DateTimeParseException e2) {
                    return Optional.empty();
                }
            }
        }
    }

    /**
     * Fetches a document.
     *
     * @param uri    the document to fetch; a fragment ({@code #…}) is ignored
     * @param accept the {@code Accept} header to send
     * @return the response
     * @throws IOException if the request fails (no connection, time-out, refused, …)
     */
    Response get(URI uri, String accept) throws IOException;

    /**
     * Fetches a document within the deadline of a tool call. Implementations that ignore
     * deadlines (recorded responses in tests) need not override it.
     *
     * @param uri      the document to fetch; a fragment ({@code #…}) is ignored
     * @param accept   the {@code Accept} header to send
     * @param deadline when the tool call must be done
     * @return the response
     * @throws IOException if the request fails, also when the deadline passes
     */
    default Response get(URI uri, String accept, Deadline deadline) throws IOException {
        return get(uri, accept);
    }

    /**
     * Returns a fetcher that refuses every request, for {@code --offline}.
     *
     * @return the fetcher
     */
    static Fetcher offline() {
        return (uri, accept) -> {
            throw new GuardedFetcher.RefusedException("refused: the server runs with --offline (" + uri + ")");
        };
    }

    /**
     * Removes the fragment from a URI: the document of a {@code #} namespace is the URI before
     * the {@code #}.
     *
     * @param uri any URI
     * @return the same URI without fragment
     */
    static URI withoutFragment(URI uri) {
        String s = uri.toString();
        int hash = s.indexOf('#');
        return hash < 0 ? uri : URI.create(s.substring(0, hash));
    }
}
