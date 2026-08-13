package dev.powerampremote.phone;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small strict parser for the API v1 flat JSON object; nested values are intentionally rejected. */
final class FlatJsonParser {
    private FlatJsonParser() {
    }

    static Map<String, Object> parseObject(String json) {
        Parser parser = new Parser(json);
        Map<String, Object> result = parser.parseObject();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("trailing data");
        }
        return result;
    }

    private static final class Parser {
        private final String input;
        private int position;

        Parser(String input) {
            if (input == null) {
                throw new IllegalArgumentException("JSON is null");
            }
            this.input = input;
        }

        Map<String, Object> parseObject() {
            skipWhitespace();
            expect('{');
            skipWhitespace();
            Map<String, Object> values = new LinkedHashMap<>();
            if (consume('}')) {
                return values;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object value = parseValue();
                if (values.containsKey(key)) {
                    throw error("duplicate key: " + key);
                }
                values.put(key, value);
                skipWhitespace();
                if (consume('}')) {
                    return values;
                }
                expect(',');
            }
        }

        private Object parseValue() {
            if (atEnd()) {
                throw error("missing value");
            }
            char next = input.charAt(position);
            if (next == '"') {
                return parseString();
            }
            if (next == '-' || next >= '0' && next <= '9') {
                return parseNumber();
            }
            if (consumeLiteral("true")) return Boolean.TRUE;
            if (consumeLiteral("false")) return Boolean.FALSE;
            if (consumeLiteral("null")) return null;
            throw error("unsupported value");
        }

        private Long parseNumber() {
            int start = position;
            if (consume('-') && atEnd()) throw error("incomplete number");
            if (consume('0')) {
                if (!atEnd() && Character.isDigit(input.charAt(position))) {
                    throw error("leading zero");
                }
            } else {
                int digits = position;
                while (!atEnd() && Character.isDigit(input.charAt(position))) position++;
                if (position == digits) throw error("invalid number");
            }
            if (!atEnd()) {
                char suffix = input.charAt(position);
                if (suffix == '.' || suffix == 'e' || suffix == 'E' || suffix == '+') {
                    throw error("integer required");
                }
            }
            try {
                return Long.parseLong(input.substring(start, position));
            } catch (NumberFormatException exception) {
                throw error("number out of range");
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (!atEnd()) {
                char character = input.charAt(position++);
                if (character == '"') return value.toString();
                if (character < 0x20) throw error("control character in string");
                if (character != '\\') {
                    value.append(character);
                    continue;
                }
                if (atEnd()) throw error("incomplete escape");
                char escaped = input.charAt(position++);
                switch (escaped) {
                    case '"':
                    case '\\':
                    case '/': value.append(escaped); break;
                    case 'b': value.append('\b'); break;
                    case 'f': value.append('\f'); break;
                    case 'n': value.append('\n'); break;
                    case 'r': value.append('\r'); break;
                    case 't': value.append('\t'); break;
                    case 'u': value.append(parseUnicode()); break;
                    default: throw error("invalid escape");
                }
            }
            throw error("unterminated string");
        }

        private char parseUnicode() {
            if (position + 4 > input.length()) throw error("incomplete unicode escape");
            int value = 0;
            for (int index = 0; index < 4; index++) {
                int digit = Character.digit(input.charAt(position++), 16);
                if (digit < 0) throw error("invalid unicode escape");
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private boolean consumeLiteral(String literal) {
            if (!input.regionMatches(position, literal, 0, literal.length())) return false;
            position += literal.length();
            return true;
        }

        private boolean consume(char expected) {
            if (!atEnd() && input.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!consume(expected)) throw error("expected " + expected);
        }

        void skipWhitespace() {
            while (!atEnd()) {
                char character = input.charAt(position);
                if (character != ' ' && character != '\n'
                        && character != '\r' && character != '\t') return;
                position++;
            }
        }

        boolean atEnd() {
            return position >= input.length();
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at " + position);
        }
    }
}
