package be.vlaanderen.omgeving.ld.mcp.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Helpers for declaring tools with little boilerplate. */
public final class Tools {

    private static final Logger LOG = LoggerFactory.getLogger(Tools.class);

    private Tools() {
    }

    /**
     * Declares a string property for an input schema.
     *
     * @param description what the property means, shown to the model
     * @return a JSON Schema property of type string
     */
    public static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    /**
     * Declares an integer property for an input schema.
     *
     * @param description what the property means, shown to the model
     * @return a JSON Schema property of type integer
     */
    public static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    /**
     * Declares the input schema of a tool.
     *
     * @param properties the properties, by name
     * @param required   the names of the required properties
     * @return a JSON Schema of type object that allows no additional properties
     */
    public static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * Declares a tool whose handler returns text. An exception thrown by the handler is reported
     * as a tool error ({@code isError}), never as a protocol error: an
     * {@link IllegalArgumentException} with its message (an error in the input, which the model can
     * correct), any other {@link RuntimeException} as {@code <class>: <message>}, with the stack
     * trace in the log.
     *
     * @param name        tool name
     * @param description what the tool does, shown to the model
     * @param inputSchema JSON Schema of the arguments, e.g. from {@link #objectSchema}
     * @param handler     maps the arguments to the text result
     * @return the tool specification
     */
    public static SyncToolSpecification textTool(String name, String description,
                                                 Map<String, Object> inputSchema,
                                                 Function<Map<String, Object>, String> handler) {
        Tool tool = Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        return CallToolResult.builder()
                                .addTextContent(handler.apply(request.arguments()))
                                .build();
                    } catch (IllegalArgumentException e) {
                        return error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    } catch (RuntimeException e) {
                        LOG.error("Tool {} failed", name, e);
                        return error(e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                })
                .build();
    }

    private static CallToolResult error(String text) {
        return CallToolResult.builder().addTextContent(text).isError(true).build();
    }

    /**
     * Reads a required string argument.
     *
     * @param args the tool arguments (may be {@code null})
     * @param key  the argument name
     * @return the argument, stripped of surrounding whitespace
     * @throws IllegalArgumentException if the argument is missing, not a string or blank
     */
    public static String requiredString(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("Argument '" + key + "' is required.");
        }
        return s.strip();
    }

    /**
     * Reads an optional integer argument.
     *
     * @param args     the tool arguments (may be {@code null})
     * @param key      the argument name
     * @param fallback the value to use when the argument is missing or not a number
     * @return the argument as an int, or {@code fallback}
     */
    public static int optionalInt(Map<String, Object> args, String key, int fallback) {
        Object value = args == null ? null : args.get(key);
        return value instanceof Number n ? n.intValue() : fallback;
    }

    /**
     * Reads an optional string argument.
     *
     * @param args the tool arguments (may be {@code null})
     * @param key  the argument name
     * @return the argument, stripped of surrounding whitespace, or {@code null} if it is missing,
     *         not a string or blank
     */
    public static String optionalString(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value instanceof String s && !s.isBlank() ? s.strip() : null;
    }
}
