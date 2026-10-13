package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A {@link Fetcher} that enforces the network policy of the server in one place:
 * <ul>
 *   <li>only {@code http} and {@code https};</li>
 *   <li>no requests to hosts that resolve to a loopback, link-local (including the cloud metadata
 *       address 169.254.169.254), private, unique-local ({@code fc00::/7}), shared
 *       ({@code 100.64.0.0/10}), unspecified or multicast address, unless private hosts are
 *       allowed (intranet vocabularies);</li>
 *   <li>redirects are followed here, at most {@value #MAX_REDIRECTS}, and every hop is checked
 *       again;</li>
 *   <li>a limit on the size of the body, and one time-out that covers connecting, the headers and
 *       the whole body, capped by the deadline of the tool call.</li>
 * </ul>
 * Remaining risk: the address is checked before the request, and {@link HttpClient} resolves the
 * host again (DNS rebinding). Acceptable for a local tool; the policy stops the obvious cases.
 */
public final class GuardedFetcher implements Fetcher {

    /** Default maximum size of a response body: 20 MB. */
    public static final long DEFAULT_MAX_BYTES = 20L * 1024 * 1024;

    /** Maximum number of redirects followed per request. */
    public static final int MAX_REDIRECTS = 5;

    /** Resolves a host name to its addresses; a parameter so that tests need no DNS. */
    @FunctionalInterface
    public interface Resolver {

        /**
         * Resolves a host.
         *
         * @param host a host name or an address literal
         * @return all its addresses
         * @throws UnknownHostException if the host cannot be resolved
         */
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** Refusal of a request by the policy; an {@link IOException}, like any other failed request. */
    public static final class RefusedException extends IOException {

        private static final long serialVersionUID = 1L;

        RefusedException(String message) {
            super(message);
        }
    }

    private final HttpClient client;
    private final Duration timeout;
    private final long maxBytes;
    private final boolean allowPrivate;
    private final Resolver resolver;

    /**
     * Creates a fetcher with the system DNS.
     *
     * @param timeout      the time-out of one request (including its redirects and body)
     * @param maxBytes     the maximum size of a body
     * @param allowPrivate whether hosts with a private address may be fetched
     */
    public GuardedFetcher(Duration timeout, long maxBytes, boolean allowPrivate) {
        this(timeout, maxBytes, allowPrivate, InetAddress::getAllByName);
    }

    /**
     * Creates a fetcher.
     *
     * @param timeout      the time-out of one request (including its redirects and body)
     * @param maxBytes     the maximum size of a body
     * @param allowPrivate whether hosts with a private address may be fetched
     * @param resolver     how to resolve host names before the policy check
     */
    GuardedFetcher(Duration timeout, long maxBytes, boolean allowPrivate, Resolver resolver) {
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout)
                .build();
        this.timeout = timeout;
        this.maxBytes = maxBytes;
        this.allowPrivate = allowPrivate;
        this.resolver = resolver;
    }

    @Override
    public Response get(URI uri, String accept) throws IOException {
        return get(uri, accept, Deadline.NONE);
    }

    @Override
    public Response get(URI uri, String accept, Deadline deadline) throws IOException {
        // One time-out for the request with all its redirects, within the deadline of the call.
        Deadline request = Deadline.after(deadline.cap(timeout));
        URI target = Fetcher.withoutFragment(uri);
        for (int hop = 0; ; hop++) {
            check(target);
            HttpResponse<byte[]> response = send(target, accept, request);
            int status = response.statusCode();
            if (status / 100 == 3 && status != 304) {
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null) {
                    return response(response);
                }
                if (hop >= MAX_REDIRECTS) {
                    throw new IOException("more than " + MAX_REDIRECTS + " redirects, last to " + location);
                }
                target = Fetcher.withoutFragment(target.resolve(location.strip()));
                continue;
            }
            return response(response);
        }
    }

    /**
     * Checks a URI against the policy.
     *
     * @param uri the URI to fetch
     * @throws RefusedException if the policy refuses it
     * @throws IOException      if its host cannot be resolved
     */
    void check(URI uri) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new RefusedException("refused: only http and https, not '" + uri + "'");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new RefusedException("refused: no host in '" + uri + "'");
        }
        if (allowPrivate) {
            return;
        }
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        for (InetAddress a : resolver.resolve(bare)) {
            String why = blocked(a);
            if (why != null) {
                throw new RefusedException("refused: " + host + " resolves to " + why + " ("
                        + a.getHostAddress() + "); start the server with --allow-private-hosts "
                        + "for vocabularies on an intranet");
            }
        }
    }

    /**
     * Tells why an address may not be fetched.
     *
     * @param a an address
     * @return a description of the address class, or {@code null} if it is public
     */
    static String blocked(InetAddress a) {
        if (a.isLoopbackAddress()) {
            return "a loopback address";
        }
        if (a.isLinkLocalAddress()) {
            return "a link-local address";
        }
        if (a.isSiteLocalAddress()) {
            return "a private address";
        }
        if (a.isAnyLocalAddress()) {
            return "the unspecified address";
        }
        if (a.isMulticastAddress()) {
            return "a multicast address";
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            if (first == 0) {
                return "a 'this network' address";
            }
            if (first == 100 && (second & 0xc0) == 64) {
                return "a shared (carrier-grade NAT) address";
            }
            if (first == 255 && second == 255 && (b[2] & 0xff) == 255 && (b[3] & 0xff) == 255) {
                return "the broadcast address";
            }
        } else if (a instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) {
                return "a unique-local address";
            }
            // IPv4-mapped (::ffff:a.b.c.d) and NAT64 (64:ff9b::a.b.c.d): judge the IPv4 address.
            boolean mapped = isZero(b, 0, 10) && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
            boolean nat64 = (b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff
                    && (b[3] & 0xff) == 0x9b && isZero(b, 4, 12);
            if (mapped || nat64) {
                try {
                    return blocked(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return "an invalid address";
                }
            }
        }
        return null;
    }

    private static boolean isZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private HttpResponse<byte[]> send(URI target, String accept, Deadline deadline) throws IOException {
        Duration left = deadline.remaining();
        if (left.isZero()) {
            throw new IOException("time budget exhausted before fetching " + target);
        }
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(left)
                .header("Accept", accept)
                .header("User-Agent", "ld-mcp-vocab")
                .GET()
                .build();
        CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(request, info -> limited(maxBytes));
        try {
            // The future completes only when the whole body has arrived, so this bounds the body too.
            return future.get(left.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("timed out after " + left.toMillis() + " ms fetching " + target);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + target, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException(cause == null ? e.getMessage() : cause.toString(), cause);
        }
    }

    private static Response response(HttpResponse<byte[]> r) {
        String contentType = r.headers().firstValue("Content-Type").orElse("");
        return new Response(r.statusCode(), contentType, new String(r.body(), charset(contentType)),
                r.headers().map());
    }

    /** The charset named in a content type, or UTF-8. */
    static Charset charset(String contentType) {
        for (String part : contentType.split(";")) {
            String p = part.strip();
            if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                String name = p.substring("charset=".length()).replace("\"", "").strip();
                try {
                    return Charset.forName(name);
                } catch (RuntimeException e) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * A body subscriber that collects at most {@code max} bytes, and fails (cancelling the
     * transfer) as soon as the body is larger.
     */
    static HttpResponse.BodySubscriber<byte[]> limited(long max) {
        return new HttpResponse.BodySubscriber<>() {
            private final CompletableFuture<byte[]> result = new CompletableFuture<>();
            private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            private Flow.Subscription subscription;
            private long count;

            @Override
            public java.util.concurrent.CompletionStage<byte[]> getBody() {
                return result;
            }

            @Override
            public void onSubscribe(Flow.Subscription s) {
                subscription = s;
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(List<ByteBuffer> items) {
                if (result.isDone()) {
                    return;
                }
                for (ByteBuffer item : items) {
                    count += item.remaining();
                    if (count > max) {
                        subscription.cancel();
                        result.completeExceptionally(new IOException("response larger than " + max + " bytes"));
                        return;
                    }
                    byte[] bytes = new byte[item.remaining()];
                    item.get(bytes);
                    buffer.writeBytes(bytes);
                }
            }

            @Override
            public void onError(Throwable t) {
                result.completeExceptionally(t);
            }

            @Override
            public void onComplete() {
                result.complete(buffer.toByteArray());
            }
        };
    }
}
