package io.github.ofdcli.json;

import java.util.List;
import java.util.Map;

/**
 * Tiny hand-rolled JSON writer.
 *
 * <p>Most subcommands only emit a few top-level fields, so dragging in
 * Jackson's {@code ObjectMapper} (and its ~1.5MB of reflective infrastructure)
 * for {@code writeValueAsString} is overkill. This class handles the small
 * subset of JSON we actually emit: objects, arrays, strings, numbers, booleans,
 * and {@code null}.
 *
 * <p>Used by every subcommand's {@code --json} output. Strings are escaped
 * with backslash and quote only — that's enough for the values we produce
 * (paths, error messages, OFD document metadata).
 */
public final class JsonWriter {

    private final StringBuilder sb;
    private boolean firstField = true;

    private JsonWriter() {
        this.sb = new StringBuilder(256);
        this.sb.append('{');
    }

    public static JsonWriter object() {
        return new JsonWriter();
    }

    public JsonWriter field(String name, String value) {
        writeName(name);
        writeString(value);
        return this;
    }

    public JsonWriter field(String name, int value) {
        writeName(name);
        sb.append(value);
        return this;
    }

    public JsonWriter field(String name, long value) {
        writeName(name);
        sb.append(value);
        return this;
    }

    public JsonWriter field(String name, double value) {
        writeName(name);
        // Jackson prints doubles as bare numbers; do the same.
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            sb.append("null");
        } else {
            sb.append(value);
        }
        return this;
    }

    public JsonWriter field(String name, boolean value) {
        writeName(name);
        sb.append(value);
        return this;
    }

    public JsonWriter field(String name, Number value) {
        writeName(name);
        if (value == null) {
            sb.append("null");
        } else {
            sb.append(value);
        }
        return this;
    }

    public JsonWriter fieldNull(String name) {
        writeName(name);
        sb.append("null");
        return this;
    }

    /**
     * Emit a pre-serialized JSON value (must already be valid JSON).
     * Used to nest a hand-built object/array as a field value.
     */
    public JsonWriter rawField(String name, String rawJson) {
        writeName(name);
        sb.append(rawJson);
        return this;
    }

    public JsonWriter field(String name, List<String> list) {
        writeName(name);
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            writeString(list.get(i));
        }
        sb.append(']');
        return this;
    }

    public JsonWriter field(String name, Map<String, Object> map) {
        writeName(name);
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else if (v instanceof List) {
                sb.append('[');
                boolean f = true;
                for (Object item : (List<?>) v) {
                    if (!f) sb.append(',');
                    f = false;
                    sb.append('"').append(escape(String.valueOf(item))).append('"');
                }
                sb.append(']');
            } else {
                sb.append('"').append(escape(String.valueOf(v))).append('"');
            }
        }
        sb.append('}');
        return this;
    }

    @Override
    public String toString() {
        sb.append('}');
        return sb.toString();
    }

    private void writeName(String name) {
        if (!firstField) sb.append(',');
        firstField = false;
        sb.append('"').append(escape(name)).append("\":");
    }

    private void writeString(String value) {
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"').append(escape(value)).append('"');
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"':  out.append("\\\""); break;
                case '\n': out.append("\\n");  break;
                case '\r': out.append("\\r");  break;
                case '\t': out.append("\\t");  break;
                case '\b': out.append("\\b");  break;
                case '\f': out.append("\\f");  break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
