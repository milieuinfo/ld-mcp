package be.vlaanderen.omgeving.ld.mcp.core;

import java.nio.file.Path;

import com.apicatalog.jsonld.JsonLdError;
import com.apicatalog.jsonld.JsonLdErrorCode;
import com.apicatalog.jsonld.JsonLdOptions;
import com.apicatalog.jsonld.loader.DocumentLoader;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RDFParserBuilder;
import org.apache.jena.riot.system.jsonld.TitaniumJsonLdOptions;
import org.apache.jena.sparql.util.Context;

/**
 * Parsing without network access. A JSON-LD document may name a remote {@code @context} (or
 * {@code @import} one); by default the JSON-LD parser fetches it, which makes validation depend on
 * a third party and lets a data file trigger requests to any host. Every file the servers read goes
 * through {@link #parser(Path)}, whose JSON-LD parser refuses remote documents: such a file gives a
 * parse error instead.
 */
public final class JsonLd {

    private JsonLd() {
    }

    /** Refuses every remote document; the message names the URL. */
    public static final DocumentLoader LOCAL_ONLY = (url, options) -> {
        throw new JsonLdError(JsonLdErrorCode.LOADING_REMOTE_CONTEXT_FAILED,
                "remote @context not allowed (" + url + "); the server does not fetch JSON-LD contexts");
    };

    /**
     * Returns a parser context whose JSON-LD parser loads remote documents with the given loader.
     * A new options object per parse: the parser sets the base on it.
     *
     * @param loader how to load remote documents, e.g. {@link #LOCAL_ONLY}
     * @return the context to pass to {@link RDFParserBuilder#context(Context)}
     */
    public static Context context(DocumentLoader loader) {
        JsonLdOptions options = new JsonLdOptions(loader);
        Context context = new Context();
        context.set(TitaniumJsonLdOptions.JSONLD_OPTIONS, options);
        return context;
    }

    /**
     * Returns a parser for a file, without network access for JSON-LD; the syntax follows from
     * the extension.
     *
     * @param file the file
     * @return the parser builder
     */
    public static RDFParserBuilder parser(Path file) {
        return RDFParser.source(file).context(context(LOCAL_ONLY));
    }

    /**
     * Reads a file into a new model, without network access for JSON-LD.
     *
     * @param file the file
     * @return the triples (and prefixes) of the file
     * @throws org.apache.jena.riot.RiotException if the file cannot be parsed, also when it needs a
     *                                            remote JSON-LD context
     */
    public static Model load(Path file) {
        Model m = ModelFactory.createDefaultModel();
        parser(file).parse(m);
        return m;
    }
}
