package be.vlaanderen.omgeving.ld.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import org.junit.jupiter.api.Test;

class ToolsTest {

    static CallToolResult call(SyncToolSpecification tool, Map<String, Object> args) {
        return tool.callHandler().apply(null, new CallToolRequest(tool.tool().name(), args));
    }

    static String text(CallToolResult r) {
        return ((TextContent) r.content().get(0)).text();
    }

    private static SyncToolSpecification tool(java.util.function.Function<Map<String, Object>, String> h) {
        return Tools.textTool("t", "test", Tools.objectSchema(Map.of("x", Tools.string("x")), List.of("x")), h);
    }

    @Test
    void returnsTheTextOfTheHandler() {
        CallToolResult r = call(tool(a -> "hello " + a.get("x")), Map.of("x", "world"));
        assertEquals("hello world", text(r));
        assertFalse(Boolean.TRUE.equals(r.isError()));
    }

    @Test
    void anIllegalArgumentBecomesAToolError() {
        CallToolResult r = call(tool(a -> {
            throw new IllegalArgumentException("bad input");
        }), Map.of());
        assertTrue(r.isError());
        assertEquals("bad input", text(r));
    }

    @Test
    void schemaAllowsNoAdditionalProperties() {
        Map<String, Object> schema = Tools.objectSchema(Map.of(), List.of());
        assertEquals(false, schema.get("additionalProperties"));
    }

    @Test
    void readsArguments() {
        Map<String, Object> a = Map.of("s", "  x ", "n", 3, "blank", " ");
        assertEquals("x", Tools.requiredString(a, "s"));
        assertThrows(IllegalArgumentException.class, () -> Tools.requiredString(a, "blank"));
        assertThrows(IllegalArgumentException.class, () -> Tools.requiredString(null, "s"));
        assertEquals(3, Tools.optionalInt(a, "n", 7));
        assertEquals(7, Tools.optionalInt(a, "missing", 7));
        assertNull(Tools.optionalString(a, "blank"));
    }

    @Test
    void anyRuntimeExceptionBecomesAToolError() {
        CallToolResult r = call(tool(a -> {
            throw new IllegalStateException("broken");
        }), Map.of());
        assertTrue(r.isError());
        assertEquals("IllegalStateException: broken", text(r));
    }

    @Test
    void anIllegalArgumentWithoutMessageStillSaysSomething() {
        CallToolResult r = call(tool(a -> {
            throw new NumberFormatException();
        }), Map.of());
        assertTrue(r.isError());
        assertEquals("NumberFormatException", text(r));
    }

    @Test
    void boundedIntegersAreCheckedAndDeclared() {
        Map<String, Object> a = new java.util.HashMap<>(Map.of("ok", 5, "low", -1, "high", 201, "frac", 2.5,
                "whole", 3.0, "text", "7"));
        assertEquals(5, Tools.optionalInt(a, "ok", 25, 1, 200));
        assertEquals(3, Tools.optionalInt(a, "whole", 25, 1, 200));
        assertEquals(25, Tools.optionalInt(a, "missing", 25, 1, 200));
        for (String key : List.of("low", "high", "frac", "text")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Tools.optionalInt(a, key, 25, 1, 200));
            assertTrue(e.getMessage().startsWith(key + " must be a whole number between 1 and 200, not "), e.getMessage());
        }
        assertEquals(Map.of("type", "integer", "description", "n", "minimum", 1, "maximum", 9), Tools.integer("n", 1, 9));
    }

    @Test
    void annotationsMarkReadOnlyTools() {
        SyncToolSpecification t = Tools.textTool("t", "d", Tools.objectSchema(Map.of(), List.of()),
                Tools.readOnly(true), a -> "x");
        assertTrue(t.tool().annotations().readOnlyHint());
        assertTrue(t.tool().annotations().openWorldHint());
        assertFalse(t.tool().annotations().destructiveHint());
    }
}
