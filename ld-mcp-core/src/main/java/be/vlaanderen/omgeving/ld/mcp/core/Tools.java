package be.vlaanderen.omgeving.ld.mcp.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/** Hulpmiddelen om tools met weinig boilerplate te declareren. */
public final class Tools {

    private Tools() {
    }

    /** JSON-schema-eigenschap van type string. */
    public static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    /** JSON-schema-eigenschap van type integer. */
    public static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    public static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * Tool waarvan de handler tekst teruggeeft. Een {@link IllegalArgumentException} wordt als
     * tool-fout (isError) gerapporteerd, zodat het model de invoer kan corrigeren.
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
                        return CallToolResult.builder()
                                .addTextContent(e.getMessage())
                                .isError(true)
                                .build();
                    }
                })
                .build();
    }

    public static String requiredString(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("Argument '" + key + "' is verplicht.");
        }
        return s.strip();
    }

    public static int optionalInt(Map<String, Object> args, String key, int fallback) {
        Object value = args == null ? null : args.get(key);
        return value instanceof Number n ? n.intValue() : fallback;
    }
}
