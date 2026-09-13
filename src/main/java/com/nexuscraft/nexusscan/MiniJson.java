package com.nexuscraft.nexusscan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, dependency-free JSON parser -- just enough to read Mojang's version manifest and
 * per-version metadata (see {@link MinecraftAssetDownloader}) without pulling in a JSON library as
 * a new build dependency for a project that otherwise has none (every other JSON this plugin
 * touches, it builds by hand -- see {@link WebhookPusher}). Parses into plain
 * {@code Map<String,Object>}/{@code List<Object>}/{@code String}/{@code Double}/{@code Boolean}/
 * {@code null}, the same shape most minimal JSON libraries produce, so callers navigate it with
 * ordinary casts and {@code get(...)} calls.
 */
final class MiniJson {

    private final String text;
    private int pos;

    private MiniJson(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        MiniJson parser = new MiniJson(text);
        parser.skipWhitespace();
        return parser.parseValue();
    }

    private Object parseValue() {
        skipWhitespace();
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't', 'f' -> parseBoolean();
            case 'n' -> parseNull();
            default -> parseNumber();
        };
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // consume '{'
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            expect(':');
            Object value = parseValue();
            map.put(key, value);
            skipWhitespace();
            char c = text.charAt(pos++);
            if (c == '}') break;
            if (c != ',') throw new IllegalStateException("Expected ',' or '}' at position " + pos);
        }
        return map;
    }

    private List<Object> parseArray() {
        List<Object> list = new ArrayList<>();
        pos++; // consume '['
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(parseValue());
            skipWhitespace();
            char c = text.charAt(pos++);
            if (c == ']') break;
            if (c != ',') throw new IllegalStateException("Expected ',' or ']' at position " + pos);
        }
        return list;
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = text.charAt(pos++);
            if (c == '"') break;
            if (c == '\\') {
                char esc = text.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        String hex = text.substring(pos, pos + 4);
                        pos += 4;
                        sb.append((char) Integer.parseInt(hex, 16));
                    }
                    default -> sb.append(esc);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Double parseNumber() {
        int start = pos;
        while (pos < text.length() && "-+.eE0123456789".indexOf(text.charAt(pos)) >= 0) pos++;
        return Double.parseDouble(text.substring(start, pos));
    }

    private Boolean parseBoolean() {
        if (text.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (text.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw new IllegalStateException("Invalid literal at position " + pos);
    }

    private Object parseNull() {
        if (text.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw new IllegalStateException("Invalid literal at position " + pos);
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    private char peek() {
        return text.charAt(pos);
    }

    private void expect(char c) {
        if (text.charAt(pos) != c) throw new IllegalStateException("Expected '" + c + "' at position " + pos);
        pos++;
    }
}
