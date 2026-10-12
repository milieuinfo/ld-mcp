package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Fetches a document over HTTP with a given {@code Accept} header. An interface, so that the tools
 * that use the network can be tested with recorded responses.
 */
@FunctionalInterface
public interface Fetcher {

    /**
     * The outcome of a request.
     *
     * @param status      HTTP status code of the final response, after redirects
     * @param contentType value of the {@code Content-Type} header, or an empty string
     * @param body        response body as text
     */
    record Response(int status, String contentType, String body) {

        /**
         * Tells whether the request succeeded.
         *
         * @return whether the status is in the 2xx range
         */
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /**
     * Fetches a document.
     *
     * @param uri    the document to fetch; a fragment ({@code #…}) is ignored
     * @param accept the {@code Accept} header to send
     * @return the response
     * @throws IOException if the request fails (no connection, time-out, …)
     */
    Response get(URI uri, String accept) throws IOException;

    /**
     * Returns a fetcher that uses {@link HttpClient}, follows redirects and gives up after the
     * time-out.
     *
     * @param timeout the time-out for connecting and for the whole request
     * @return the fetcher
     */
    static Fetcher http(Duration timeout) {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(timeout)
                .build();
        return (uri, accept) -> {
            URI target = withoutFragment(uri);
            HttpRequest request = HttpRequest.newBuilder(target)
                    .timeout(timeout)
                    .header("Accept", accept)
                    .header("User-Agent", "ld-mcp-vocab")
                    .GET()
                    .build();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                return new Response(response.statusCode(),
                        response.headers().firstValue("Content-Type").orElse(""),
                        response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while fetching " + target, e);
            }
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
