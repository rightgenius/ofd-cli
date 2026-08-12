package io.github.ofdcli;

import io.github.ofdcli.json.JsonWriter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the hand-rolled JSON writer.
 *
 * <p>We keep the writer small and predictable, so a focused test
 * catches the regressions that matter (escaping, nesting, primitive
 * type rendering) without dragging in a JSON parsing dependency.
 */
class JsonWriterTest {

    @Test
    void emptyObject() {
        assertEquals("{}", JsonWriter.object().toString());
    }

    @Test
    void stringField() {
        String json = JsonWriter.object()
                .field("name", "ofd-cli")
                .toString();
        assertEquals("{\"name\":\"ofd-cli\"}", json);
    }

    @Test
    void escapingQuotesAndBackslashes() {
        String json = JsonWriter.object()
                .field("path", "C:\\Users\\alice\\file.txt")
                .toString();
        assertEquals("{\"path\":\"C:\\\\Users\\\\alice\\\\file.txt\"}", json);
    }

    @Test
    void escapeNewlines() {
        String json = JsonWriter.object()
                .field("text", "line1\nline2")
                .toString();
        assertTrue(json.contains("line1\\nline2"), "Got: " + json);
    }

    @Test
    void nullStringField() {
        String json = JsonWriter.object()
                .field("missing", (String) null)
                .toString();
        assertEquals("{\"missing\":null}", json);
    }

    @Test
    void nullSentinel() {
        String json = JsonWriter.object()
                .fieldNull("absent")
                .toString();
        assertEquals("{\"absent\":null}", json);
    }

    @Test
    void numbers() {
        String json = JsonWriter.object()
                .field("i", 42)
                .field("l", 9_000_000_000L)
                .field("d", 3.14)
                .field("b", true)
                .field("n", (Number) null)
                .toString();
        assertTrue(json.contains("\"i\":42"));
        assertTrue(json.contains("\"l\":9000000000"));
        assertTrue(json.contains("\"d\":3.14"));
        assertTrue(json.contains("\"b\":true"));
        assertTrue(json.contains("\"n\":null"));
    }

    @Test
    void listField() {
        String json = JsonWriter.object()
                .field("items", List.of("a", "b", "c"))
                .toString();
        assertEquals("{\"items\":[\"a\",\"b\",\"c\"]}", json);
    }

    @Test
    void rawFieldInjectsPreBuiltJson() {
        String inner = JsonWriter.object()
                .field("x", 1)
                .toString();
        String outer = JsonWriter.object()
                .field("name", "outer")
                .rawField("inner", inner)
                .toString();
        assertEquals("{\"name\":\"outer\",\"inner\":{\"x\":1}}", outer);
    }

    @Test
    void mapField() {
        Map<String, Object> inner = new java.util.LinkedHashMap<>();
        inner.put("k", "v");
        inner.put("n", 7);
        String json = JsonWriter.object()
                .field("data", inner)
                .toString();
        assertTrue(json.contains("\"k\":\"v\""));
        assertTrue(json.contains("\"n\":7"));
    }
}
