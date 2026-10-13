package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonException;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;

/**
 * Client for the API of <a href="https://lov.linkeddata.es">Linked Open Vocabularies</a> (LOV).
 * LOV is used to <em>explore</em> candidate terms; its ranking is textual and its metadata can lag
 * behind the vocabularies themselves, so the output says so and leaves the assessment to the user.
 */
public final class LovClient {

    /** Default base URL of the LOV API (version 2). */
    public static final String DEFAULT_BASE_URL = "https://lov.linkeddata.es/dataset/api/v2";

    /** Default number of attempts per request: the LOV server often drops the TLS handshake or does not answer. */
    public static final int DEFAULT_ATTEMPTS = 3;

    /** Default pause before the second attempt; it doubles before each further attempt. */
    public static final Duration DEFAULT_PAUSE = Duration.ofSeconds(1);

    /** The largest number of hits asked from LOV in one search. */
    public static final int MAX_PAGE_SIZE = 50;

    private final String baseUrl;
    private final Fetcher fetcher;
    private final int attempts;
    private final Duration pause;

    /**
     * Creates a client that makes one attempt per request.
     *
     * @param baseUrl base URL of the LOV API, without trailing slash, e.g. {@link #DEFAULT_BASE_URL}
     * @param fetcher how to fetch the API responses
     */
    public LovClient(String baseUrl, Fetcher fetcher) {
        this(baseUrl, fetcher, 1, Duration.ZERO);
    }

    /**
     * Creates a client that retries a request that fails without an answer (no connection,
     * time-out, broken handshake) or with a status that says "try again later" (429, 502, 503, 504).
     *
     * @param baseUrl  base URL of the LOV API, without trailing slash, e.g. {@link #DEFAULT_BASE_URL}
     * @param fetcher  how to fetch the API responses
     * @param attempts the maximum number of attempts per request, at least 1
     * @param pause    the pause before the second attempt; doubled before each further attempt
     */
    public LovClient(String baseUrl, Fetcher fetcher, int attempts, Duration pause) {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1, not " + attempts);
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.fetcher = fetcher;
        this.attempts = attempts;
        this.pause = pause;
    }

    /**
     * Searches LOV for terms and lists the hits, marking which ones are already loaded.
     *
     * @param query    the text to search for, e.g. {@code Sample}
     * @param type     {@code class}, {@code property}, or {@code null} for both
     * @param limit    the maximum number of hits to list
     * @param isLoaded tells whether a term IRI exists in the loaded ontologies
     * @return the hits as text: a warning, the number of hits per vocabulary, and one line per hit
     * @throws IllegalArgumentException if the type is not supported or LOV cannot be reached
     */
    public String searchTerms(String query, String type, int limit, Predicate<String> isLoaded) {
        return searchTerms(query, type, limit, isLoaded, Deadline.NONE);
    }

    /**
     * Searches LOV for terms within a deadline, and lists the hits, marking which ones are already
     * loaded.
     *
     * @param query    the text to search for, e.g. {@code Sample}
     * @param type     {@code class}, {@code property}, or {@code null} for both
     * @param limit    the maximum number of hits to list
     * @param isLoaded tells whether a term IRI exists in the loaded ontologies
     * @param deadline when the call must be done; retries stop when it is near
     * @return the hits as text: a warning, the number of hits per vocabulary, and one line per hit
     * @throws IllegalArgumentException if the type is not supported or LOV cannot be reached in time
     */
    public String searchTerms(String query, String type, int limit, Predicate<String> isLoaded,
                              Deadline deadline) {
        if (type != null && !type.equals("class") && !type.equals("property")) {
            throw new IllegalArgumentException("type must be 'class' or 'property', not '" + type + "'");
        }
        String url = baseUrl + "/term/search?q=" + encode(query)
                + (type == null ? "" : "&type=" + type)
                + "&page_size=" + Math.clamp(limit, 1, MAX_PAGE_SIZE);
        JsonObject json = getJson(url, deadline);

        StringBuilder sb = new StringBuilder();
        sb.append("LOV: ").append(intValue(json, "total_results")).append(" hit(s) for '").append(query).append("'")
                .append(type == null ? "" : " (" + type + ")").append(".\n")
                .append("Note: LOV ranks by text match, not by suitability. A hit is a candidate to assess "
                        + "(e.g. against R17 of oslo-ld-kit), never a recommendation.\n");

        JsonArray buckets = path(json, "aggregations", "vocabs", "buckets").map(JsonValue::getAsArray).orElse(null);
        if (buckets != null && !buckets.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (JsonValue b : buckets) {
                JsonObject o = b.getAsObject();
                parts.add(string(o, "key") + " (" + intValue(o, "doc_count") + ")");
            }
            sb.append("Per vocabulary: ").append(String.join(", ", parts)).append('\n');
        }

        JsonArray results = json.hasKey("results") ? json.get("results").getAsArray() : new JsonArray();
        if (results.isEmpty()) {
            return sb.append("No hits.").toString();
        }
        for (JsonValue r : results) {
            JsonObject o = r.getAsObject();
            String uri = firstString(o.get("uri"));
            String loaded = isLoaded.test(uri) ? "loaded" : "not loaded";
            sb.append("- ").append(string(o, "prefixedName")).append("  <").append(uri).append(">  [")
                    .append(string(o, "type")).append(", ").append(loaded).append("]\n");
        }
        return sb.toString();
    }

    /**
     * Fetches the LOV record of a vocabulary.
     *
     * @param vocab the LOV prefix ({@code saref}) or the namespace IRI
     * @return the record, or empty if LOV does not know the vocabulary
     * @throws IllegalArgumentException if LOV cannot be reached
     */
    public Optional<JsonObject> vocabularyInfo(String vocab) {
        return vocabularyInfo(vocab, Deadline.NONE);
    }

    /**
     * Fetches the LOV record of a vocabulary within a deadline.
     *
     * @param vocab    the LOV prefix ({@code saref}) or the namespace IRI
     * @param deadline when the call must be done; retries stop when it is near
     * @return the record, or empty if LOV does not know the vocabulary
     * @throws IllegalArgumentException if LOV cannot be reached in time
     */
    public Optional<JsonObject> vocabularyInfo(String vocab, Deadline deadline) {
        String url = baseUrl + "/vocabulary/info?vocab=" + encode(vocab);
        Fetcher.Response response = fetch(url, deadline);
        if (response.status() == 404) {
            return Optional.empty();
        }
        if (!response.ok()) {
            throw new IllegalArgumentException("LOV answered " + response.status() + " for " + url);
        }
        if (response.body().isBlank()) {
            return Optional.empty();
        }
        JsonObject json = parse(response.body(), url);
        return json.hasKey("uri") ? Optional.of(json) : Optional.empty();
    }

    /**
     * Summarises a LOV vocabulary record: title, creators and publishers, licence, the latest
     * version known to LOV and its declared relations, and incoming reuse.
     *
     * @param info a record from {@link #vocabularyInfo}
     * @return the summary as text, one fact per line
     */
    public static String summarise(JsonObject info) {
        StringBuilder sb = new StringBuilder();
        line(sb, "title", langValues(info.get("titles")));
        line(sb, "namespace", string(info, "nsp"));
        line(sb, "creators", names(info.get("creatorIds")));
        line(sb, "publishers", names(info.get("publisherIds")));
        line(sb, "licence (LOV)", strings(info.get("license")));
        JsonArray versions = info.hasKey("versions") ? info.get("versions").getAsArray() : new JsonArray();
        line(sb, "versions in LOV", String.valueOf(versions.size()));
        latest(versions).ifPresent(v -> {
            line(sb, "latest version in LOV", string(v, "name") + ", issued " + string(v, "issued"));
            line(sb, "  extends", strings(v.get("relExtends")));
            line(sb, "  specializes", strings(v.get("relSpecializes")));
            line(sb, "  imports", strings(v.get("relImports")));
            line(sb, "  equivalent to", strings(v.get("relEquivalent")));
        });
        line(sb, "reused by (extends/specializes/imports)",
                size(info.get("incomRelExtends")) + "/" + size(info.get("incomRelSpecializes")) + "/"
                        + size(info.get("incomRelImports")) + " vocabularies");
        return sb.toString();
    }

    // ---------------------------------------------------------------- helpers

    private JsonObject getJson(String url, Deadline deadline) {
        Fetcher.Response response = fetch(url, deadline);
        if (!response.ok()) {
            throw new IllegalArgumentException("LOV answered " + response.status() + " for " + url);
        }
        return parse(response.body(), url);
    }

    /**
     * Fetches with retries: after a failure without an answer or a transient status, waits (the
     * pause, doubled each time, or longer if the server asks so with {@code Retry-After}) and tries
     * again, as long as attempts and time are left.
     */
    private Fetcher.Response fetch(String url, Deadline deadline) {
        IOException last = null;
        Fetcher.Response response = null;
        Duration wait = pause;
        int made = 0;
        for (int i = 1; i <= attempts; i++) {
            if (i > 1) {
                Duration asked = response == null ? Duration.ZERO : response.retryAfter().orElse(Duration.ZERO);
                Duration delay = asked.compareTo(wait) > 0 ? asked : wait;
                if (delay.compareTo(deadline.remaining()) >= 0) {
                    break; // no time left for another attempt
                }
                sleep(delay);
                wait = wait.multipliedBy(2);
            }
            if (deadline.expired()) {
                break;
            }
            made++;
            try {
                response = fetcher.get(URI.create(url), "application/json", deadline);
                last = null;
                if (!isTransient(response.status())) {
                    return response;
                }
            } catch (GuardedFetcher.RefusedException e) {
                last = e;
                response = null;
                break; // the policy will refuse again
            } catch (IOException e) {
                last = e;
                response = null;
            }
        }
        if (last != null || response == null) {
            String why = last != null ? last.getMessage() : "time budget exhausted";
            throw new IllegalArgumentException("Cannot reach LOV (" + url + "): " + why
                    + (made > 1 ? " (" + made + " attempts)" : ""), last);
        }
        return response;
    }

    private static boolean isTransient(int status) {
        return status == 429 || status == 502 || status == 503 || status == 504;
    }

    private static void sleep(Duration d) {
        if (d.isZero() || d.isNegative()) {
            return;
        }
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Interrupted while waiting to retry LOV", e);
        }
    }

    private static JsonObject parse(String body, String url) {
        try {
            return JSON.parse(body);
        } catch (JsonException e) {
            throw new IllegalArgumentException("LOV returned no valid JSON for " + url, e);
        }
    }

    private static Optional<JsonObject> latest(JsonArray versions) {
        return versions.stream()
                .map(JsonValue::getAsObject)
                .max(Comparator.comparing(v -> string(v, "issued")));
    }

    private static Optional<JsonValue> path(JsonObject o, String... keys) {
        JsonValue v = o;
        for (String k : keys) {
            if (v == null || !v.isObject() || !v.getAsObject().hasKey(k)) {
                return Optional.empty();
            }
            v = v.getAsObject().get(k);
        }
        return Optional.ofNullable(v);
    }

    private static String string(JsonObject o, String key) {
        if (!o.hasKey(key)) {
            return "";
        }
        return firstString(o.get(key));
    }

    private static String firstString(JsonValue v) {
        if (v == null || v.isNull()) {
            return "";
        }
        if (v.isArray()) {
            return v.getAsArray().isEmpty() ? "" : firstString(v.getAsArray().get(0));
        }
        if (v.isString()) {
            return v.getAsString().value();
        }
        return v.toString();
    }

    private static String intValue(JsonObject o, String key) {
        if (!o.hasKey(key)) {
            return "?";
        }
        JsonValue v = o.get(key);
        return v.isNumber() ? String.valueOf(v.getAsNumber().value().longValue()) : firstString(v);
    }

    private static String strings(JsonValue v) {
        if (v == null || !v.isArray() || v.getAsArray().isEmpty()) {
            return v != null && v.isString() ? v.getAsString().value() : "";
        }
        List<String> out = new ArrayList<>();
        for (JsonValue e : v.getAsArray()) {
            out.add(e.isObject() ? firstString(e.getAsObject().get("uri")) : firstString(e));
        }
        return String.join(", ", out);
    }

    private static String names(JsonValue v) {
        if (v == null || !v.isArray()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        for (JsonValue e : v.getAsArray()) {
            if (e.isObject() && e.getAsObject().hasKey("name")) {
                out.add(firstString(e.getAsObject().get("name")));
            }
        }
        return String.join(", ", out);
    }

    private static String langValues(JsonValue v) {
        if (v == null || !v.isArray()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        for (JsonValue e : v.getAsArray()) {
            if (e.isObject() && e.getAsObject().hasKey("value")) {
                String lang = string(e.getAsObject(), "lang");
                out.add(firstString(e.getAsObject().get("value")) + (lang.isEmpty() ? "" : " @" + lang));
            }
        }
        return String.join("; ", out);
    }

    private static int size(JsonValue v) {
        return v != null && v.isArray() ? v.getAsArray().size() : 0;
    }

    private static void line(StringBuilder sb, String key, String value) {
        sb.append(key).append(": ").append(value == null || value.isEmpty() ? "-" : value).append('\n');
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
