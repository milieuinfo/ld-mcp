package be.vlaanderen.omgeving.ld.mcp.vocab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import be.vlaanderen.omgeving.ld.mcp.vocab.support.HttpFixture;

class GuardedFetcherTest {

    private final HttpFixture http = new HttpFixture();

    @AfterEach
    void close() {
        http.close();
    }

    private static final Duration TEN_SECONDS = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.8.9.10", "::1", "169.254.169.254", "fe80::1", "10.1.2.3",
            "172.16.0.1", "172.31.255.255", "192.168.1.1", "100.64.0.1", "100.127.255.255", "fc00::1",
            "fd12:3456::1", "::ffff:127.0.0.1", "::ffff:169.254.169.254", "64:ff9b::a00:1", "0.0.0.0",
            "0.1.2.3", "::", "224.0.0.1", "ff02::1", "255.255.255.255", "fec0::1"})
    void blocksNonPublicAddresses(String address) throws UnknownHostException {
        assertNotNull(GuardedFetcher.blocked(InetAddress.getByName(address)), address);
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "100.63.255.255", "100.128.0.1", "172.32.0.1", "193.191.168.5",
            "2001:4860:4860::8888", "64:ff9b::808:808"})
    void allowsPublicAddresses(String address) throws UnknownHostException {
        assertNull(GuardedFetcher.blocked(InetAddress.getByName(address)), address);
    }

    @Test
    void refusesLoopbackWithoutARequest() {
        http.body("/v", 200, "text/turtle", "");
        GuardedFetcher f = new GuardedFetcher(TEN_SECONDS, 1000, false);
        IOException e = assertThrows(GuardedFetcher.RefusedException.class,
                () -> f.get(URI.create(http.base() + "/v"), "text/turtle"));
        assertTrue(e.getMessage().contains("loopback") && e.getMessage().contains("--allow-private-hosts"),
                e.getMessage());
        assertEquals(0, http.requests());
    }

    @Test
    void refusesAHostThatResolvesToTheMetadataAddress() {
        GuardedFetcher f = new GuardedFetcher(TEN_SECONDS, 1000, false,
                host -> new InetAddress[] {InetAddress.getByName("169.254.169.254")});
        IOException e = assertThrows(GuardedFetcher.RefusedException.class,
                () -> f.get(URI.create("http://metadata.test/latest/"), "*/*"));
        assertTrue(e.getMessage().contains("link-local"), e.getMessage());
    }

    @Test
    void refusesWhenOneOfSeveralAddressesIsPrivate() {
        GuardedFetcher f = new GuardedFetcher(TEN_SECONDS, 1000, false, host -> new InetAddress[] {
                InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.1")});
        assertThrows(GuardedFetcher.RefusedException.class, () -> f.get(URI.create("http://mixed.test/"), "*/*"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "ftp://example.org/x", "jar:file:/x!/y", "http:///nohost"})
    void refusesOtherSchemesAndMissingHosts(String uri) {
        GuardedFetcher f = new GuardedFetcher(TEN_SECONDS, 1000, true);
        assertThrows(GuardedFetcher.RefusedException.class, () -> f.get(URI.create(uri), "*/*"));
    }

    @Test
    void checksEveryRedirectHop() {
        // 127.0.0.1 passes as if public; localhost does not.
        GuardedFetcher f = new GuardedFetcher(TEN_SECONDS, 1000, false, host -> host.equals("127.0.0.1")
                ? new InetAddress[] {InetAddress.getByName("8.8.8.8")}
                : InetAddress.getAllByName(host));
        int port = URI.create(http.base()).getPort();
        http.redirect("/public", "http://localhost:" + port + "/internal")
                .body("/internal", 200, "text/plain", "secret");
        IOException e = assertThrows(GuardedFetcher.RefusedException.class,
                () -> f.get(URI.create(http.base() + "/public"), "*/*"));
        assertTrue(e.getMessage().contains("localhost"), e.getMessage());
        assertEquals(List.of("/public"), http.paths());
    }

    @Test
    void followsRelativeRedirectsAndDropsTheFragment() throws IOException {
        http.redirect("/a", "/b#frag").body("/b", 200, "text/turtle; charset=utf-8", "<urn:a> <urn:b> <urn:c> .");
        Fetcher.Response r = new GuardedFetcher(TEN_SECONDS, 1000, true).get(URI.create(http.base() + "/a#x"), "*/*");
        assertEquals(200, r.status());
        assertEquals("<urn:a> <urn:b> <urn:c> .", r.body());
        assertEquals(List.of("/a", "/b"), http.paths());
    }

    @Test
    void stopsAfterFiveRedirects() {
        http.redirect("/loop", "/loop");
        IOException e = assertThrows(IOException.class,
                () -> new GuardedFetcher(TEN_SECONDS, 1000, true).get(URI.create(http.base() + "/loop"), "*/*"));
        assertTrue(e.getMessage().contains("more than 5 redirects"), e.getMessage());
        assertEquals(6, http.requests());
    }

    @Test
    void aRedirectWithoutLocationIsTheAnswer() throws IOException {
        http.status("/nowhere", 302, Map.of());
        assertEquals(302, new GuardedFetcher(TEN_SECONDS, 1000, true).get(URI.create(http.base() + "/nowhere"), "*/*").status());
    }

    @Test
    void aTricklingBodyIsCutOffAtTheTimeOut() {
        http.trickle("/slow", 100);
        long start = System.nanoTime();
        IOException e = assertThrows(IOException.class, () -> new GuardedFetcher(Duration.ofSeconds(1), 1_000_000, true)
                .get(URI.create(http.base() + "/slow"), "*/*"));
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(e.getMessage().contains("timed out"), e.getMessage());
        assertTrue(ms < 3000, "took " + ms + " ms");
    }

    @Test
    void theDeadlineOfTheCallShortensTheTimeOut() {
        http.trickle("/slow", 100);
        long start = System.nanoTime();
        assertThrows(IOException.class, () -> new GuardedFetcher(TEN_SECONDS, 1_000_000, true)
                .get(URI.create(http.base() + "/slow"), "*/*", Deadline.after(Duration.ofMillis(400))));
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms < 2000, "took " + ms + " ms");
    }

    @Test
    void anExpiredDeadlineMakesNoRequest() {
        http.body("/v", 200, "text/plain", "x");
        Deadline gone = Deadline.after(Duration.ZERO);
        assertThrows(IOException.class, () -> new GuardedFetcher(TEN_SECONDS, 1000, true)
                .get(URI.create(http.base() + "/v"), "*/*", gone));
        assertEquals(0, http.requests());
    }

    @Test
    void aBodyAboveTheLimitIsRefused() {
        http.large("/big", 5_000_000);
        IOException e = assertThrows(IOException.class, () -> new GuardedFetcher(TEN_SECONDS, 1_000_000, true)
                .get(URI.create(http.base() + "/big"), "*/*"));
        assertTrue(e.getMessage().contains("larger than 1000000 bytes"), e.getMessage());
    }

    @Test
    void aBodyAtTheLimitIsAccepted() throws IOException {
        http.large("/exact", 1000);
        assertEquals(1000, new GuardedFetcher(TEN_SECONDS, 1000, true).get(URI.create(http.base() + "/exact"), "*/*")
                .body().length());
    }

    @Test
    void decodesTheBodyWithTheCharsetOfTheContentType() {
        assertEquals(StandardCharsets.ISO_8859_1, GuardedFetcher.charset("text/turtle; charset=\"ISO-8859-1\""));
        assertEquals(StandardCharsets.UTF_8, GuardedFetcher.charset("text/turtle"));
        assertEquals(StandardCharsets.UTF_8, GuardedFetcher.charset("text/turtle; charset=nonsense-42"));
    }

    @Test
    void readsRetryAfter() {
        assertEquals(Duration.ofSeconds(7),
                new Fetcher.Response(429, "", "", Map.of("Retry-After", List.of("7"))).retryAfter().orElseThrow());
        assertEquals(Duration.ZERO,
                new Fetcher.Response(429, "", "", Map.of("retry-after", List.of("-3"))).retryAfter().orElseThrow());
        assertEquals(Duration.ZERO, new Fetcher.Response(429, "", "",
                Map.of("Retry-After", List.of("Wed, 21 Oct 2015 07:28:00 GMT"))).retryAfter().orElseThrow());
        assertTrue(new Fetcher.Response(429, "", "", Map.of("Retry-After", List.of("soon"))).retryAfter().isEmpty());
        assertTrue(new Fetcher.Response(429, "", "").retryAfter().isEmpty());
    }

    @Test
    void offlineRefusesEverything() {
        assertThrows(GuardedFetcher.RefusedException.class,
                () -> Fetcher.offline().get(URI.create("https://example.org/"), "*/*"));
    }
}
