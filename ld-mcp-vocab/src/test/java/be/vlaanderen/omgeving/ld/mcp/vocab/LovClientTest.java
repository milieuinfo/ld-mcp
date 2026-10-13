package be.vlaanderen.omgeving.ld.mcp.vocab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.jena.atlas.json.JsonObject;
import org.junit.jupiter.api.Test;

class LovClientTest {

    /** Trimmed response of /term/search?q=Sample&type=class, recorded on 2026-10-12. */
    private static final String SEARCH = """
            {"total_results":40,"page":1,"page_size":3,"queryString":"Sample","filters":{"type":"class"},
             "aggregations":{"vocabs":{"buckets":[{"key":"tribont-sample","doc_count":11},{"key":"sosa","doc_count":2}]}},
             "results":[
              {"type":"class","uri":"http://spinrdf.org/sp#Sample","prefixedName":"sp:Sample","vocabulary":{"prefix":"sp"},"score":72.9},
              {"type":"class","uri":"http://www.w3.org/ns/sosa/Sample","prefixedName":"sosa:Sample","vocabulary":{"prefix":"sosa"},"score":72.9},
              {"type":"class","uri":["https://w3id.org/tribont/sample#Sample"],"prefixedName":["tribont-sample:Sample"],"score":72.9}]}
            """;

    /** Trimmed response of /vocabulary/info?vocab=saref, recorded on 2026-10-12. */
    private static final String INFO = """
            {"uri":"https://saref.etsi.org/core/","nsp":"https://saref.etsi.org/core/","prefix":"saref",
             "titles":[{"value":"SAREF: the Smart Appliances REFerence ontology","lang":"en"}],
             "creatorIds":[{"name":"Laura Daniele"},{"name":"Maxime Lefrançois"}],
             "license":[],
             "incomRelExtends":["a","b"],"incomRelSpecializes":["c"],"incomRelImports":[],
             "versions":[
              {"name":"v1.0","issued":"2015-04-01T00:00:00.000Z","relExtends":["http://www.w3.org/2001/XMLSchema"]},
              {"name":"v0.9","issued":"2014-01-01T00:00:00.000Z"}]}
            """;

    private final List<String> requested = new ArrayList<>();

    private LovClient client(int status, String body) {
        return new LovClient("https://lov.example/api/", (uri, accept) -> {
            requested.add(uri.toString());
            return new Fetcher.Response(status, "application/json", body);
        });
    }

    @Test
    void searchListsHitsAndMarksLoadedTerms() {
        String out = client(200, SEARCH).searchTerms("Sample", "class", 3,
                iri -> iri.equals("http://www.w3.org/ns/sosa/Sample"));
        assertTrue(out.startsWith("LOV: 40 hit(s) for 'Sample' (class)."), out);
        assertTrue(out.contains("never a recommendation"), out);
        assertTrue(out.contains("Per vocabulary: tribont-sample (11), sosa (2)"), out);
        assertTrue(out.contains("- sosa:Sample  <http://www.w3.org/ns/sosa/Sample>  [class, loaded]"), out);
        assertTrue(out.contains("- sp:Sample  <http://spinrdf.org/sp#Sample>  [class, not loaded]"), out);
        assertTrue(out.contains("- tribont-sample:Sample  <https://w3id.org/tribont/sample#Sample>"), out);
        assertEquals("https://lov.example/api/term/search?q=Sample&type=class&page_size=3", requested.get(0));
    }

    @Test
    void searchRejectsUnknownType() {
        assertThrows(IllegalArgumentException.class,
                () -> client(200, SEARCH).searchTerms("Sample", "individual", 3, iri -> false));
    }

    @Test
    void searchReportsUnreachableLov() {
        LovClient failing = new LovClient("https://lov.example/api", (uri, accept) -> {
            throw new IOException("no route to host");
        });
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> failing.searchTerms("Sample", null, 3, iri -> false));
        assertTrue(e.getMessage().contains("Cannot reach LOV"), e.getMessage());
    }

    @Test
    void vocabularyInfoIsEmptyWhenUnknown() {
        assertTrue(client(404, "").vocabularyInfo("nope").isEmpty());
    }

    @Test
    void summaryShowsLatestVersionAndReuse() {
        Optional<JsonObject> info = client(200, INFO).vocabularyInfo("saref");
        assertTrue(info.isPresent());
        String out = LovClient.summarise(info.get());
        assertTrue(out.contains("title: SAREF: the Smart Appliances REFerence ontology @en"), out);
        assertTrue(out.contains("creators: Laura Daniele, Maxime Lefrançois"), out);
        assertTrue(out.contains("licence (LOV): -"), out);
        assertTrue(out.contains("versions in LOV: 2"), out);
        assertTrue(out.contains("latest version in LOV: v1.0, issued 2015-04-01"), out);
        assertTrue(out.contains("  extends: http://www.w3.org/2001/XMLSchema"), out);
        assertTrue(out.contains("reused by (extends/specializes/imports): 2/1/0 vocabularies"), out);
    }

    /** A client with three attempts and no pause, whose fetcher answers from a script of outcomes. */
    private static LovClient scripted(AtomicInteger calls, Object... outcomes) {
        return new LovClient("https://lov.example/api", (uri, accept) -> {
            Object o = outcomes[Math.min(calls.getAndIncrement(), outcomes.length - 1)];
            if (o instanceof IOException e) {
                throw e;
            }
            return new Fetcher.Response((Integer) o, "application/json", "");
        }, 3, Duration.ZERO);
    }

    @Test
    void retriesAfterABrokenHandshake() {
        AtomicInteger calls = new AtomicInteger();
        LovClient lov = scripted(calls, new IOException("Remote host terminated the handshake"), 404);
        assertTrue(lov.vocabularyInfo("nope").isEmpty());
        assertEquals(2, calls.get());
    }

    @Test
    void givesUpAfterTheLastAttempt() {
        AtomicInteger calls = new AtomicInteger();
        LovClient lov = scripted(calls, new IOException("HTTP connect timed out"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> lov.vocabularyInfo("p-plan"));
        assertTrue(e.getMessage().contains("Cannot reach LOV") && e.getMessage().contains("(3 attempts)"), e.getMessage());
        assertEquals(3, calls.get());
    }

    @Test
    void retriesOnTryAgainLaterStatusOnly() {
        AtomicInteger busy = new AtomicInteger();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> scripted(busy, 503).vocabularyInfo("p-plan"));
        assertTrue(e.getMessage().contains("LOV answered 503"), e.getMessage());
        assertEquals(3, busy.get());

        AtomicInteger unknown = new AtomicInteger();
        assertTrue(scripted(unknown, 404).vocabularyInfo("nope").isEmpty());
        assertEquals(1, unknown.get());
    }

    @Test
    void waitsAsLongAsRetryAfterAsks() {
        AtomicInteger calls = new AtomicInteger();
        LovClient lov = new LovClient("https://lov.example/api", (uri, accept) -> calls.getAndIncrement() == 0
                ? new Fetcher.Response(429, "", "", Map.of("Retry-After", List.of("1")))
                : new Fetcher.Response(404, "", ""), 3, Duration.ZERO);
        long start = System.nanoTime();
        assertTrue(lov.vocabularyInfo("x").isEmpty());
        assertTrue(System.nanoTime() - start >= 900_000_000L, "did not wait for Retry-After");
        assertEquals(2, calls.get());
    }

    @Test
    void doesNotRetryBeyondTheDeadline() {
        AtomicInteger calls = new AtomicInteger();
        LovClient lov = new LovClient("https://lov.example/api", (uri, accept) -> {
            calls.incrementAndGet();
            return new Fetcher.Response(503, "", "", Map.of("Retry-After", List.of("60")));
        }, 3, Duration.ZERO);
        long start = System.nanoTime();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> lov.vocabularyInfo("x", Deadline.after(Duration.ofSeconds(2))));
        assertTrue(e.getMessage().contains("LOV answered 503"), e.getMessage());
        assertEquals(1, calls.get());
        assertTrue(System.nanoTime() - start < 1_000_000_000L);
    }

    @Test
    void anExpiredDeadlineMakesNoRequest() {
        AtomicInteger calls = new AtomicInteger();
        LovClient lov = scripted(calls, 200);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> lov.searchTerms("x", null, 5, iri -> false, Deadline.after(Duration.ZERO)));
        assertTrue(e.getMessage().contains("time budget exhausted"), e.getMessage());
        assertEquals(0, calls.get());
    }

    @Test
    void aRefusalIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        LovClient lov = new LovClient("https://lov.example/api", (uri, accept) -> {
            calls.incrementAndGet();
            throw new GuardedFetcher.RefusedException("refused: test");
        }, 3, Duration.ofSeconds(5));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> lov.vocabularyInfo("x"));
        assertTrue(e.getMessage().contains("refused: test"), e.getMessage());
        assertEquals(1, calls.get());
    }
}
