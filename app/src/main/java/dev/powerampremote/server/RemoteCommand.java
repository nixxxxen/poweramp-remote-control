package dev.powerampremote.server;

import java.util.HashMap;
import java.util.Map;

/** Validated command accepted by POST /api/v1/control. */
final class RemoteCommand {
    enum Action {
        PLAY("play"),
        PAUSE("pause"),
        PREVIOUS("previous"),
        NEXT("next"),
        SEEK("seek"),
        SHUFFLE_ON("shuffle_on"),
        SHUFFLE_OFF("shuffle_off"),
        SET_RATING("set_rating"),
        SET_VOLUME("set_volume");

        final String wireName;

        Action(String wireName) {
            this.wireName = wireName;
        }

        static Action fromWireName(String value) {
            for (Action action : values()) {
                if (action.wireName.equals(value)) {
                    return action;
                }
            }
            return null;
        }
    }

    final Action action;
    final int rating;
    final int positionSeconds;
    final int volume;

    private RemoteCommand(Action action, int rating, int positionSeconds, int volume) {
        this.action = action;
        this.rating = rating;
        this.positionSeconds = positionSeconds;
        this.volume = volume;
    }

    static RemoteCommand parse(String json) {
        Map<String, Object> values = new Parser(json).parseObject();
        for (String key : values.keySet()) {
            if (!"action".equals(key) && !"value".equals(key)) {
                throw new IllegalArgumentException("unknown field: " + key);
            }
        }

        Object actionValue = values.get("action");
        if (!(actionValue instanceof String)) {
            throw new IllegalArgumentException("action must be a string");
        }
        Action action = Action.fromWireName((String) actionValue);
        if (action == null) {
            throw new IllegalArgumentException("unsupported action");
        }

        boolean hasValue = values.containsKey("value");
        if (action == Action.SET_RATING) {
            Object ratingValue = values.get("value");
            if (!(ratingValue instanceof Long)) {
                throw new IllegalArgumentException("set_rating value must be an integer");
            }
            long rating = (Long) ratingValue;
            if (rating < 0L || rating > 5L) {
                throw new IllegalArgumentException("rating must be in range 0..5");
            }
            return new RemoteCommand(action, (int) rating, -1, -1);
        }
        if (action == Action.SEEK) {
            Object positionValue = values.get("value");
            if (!(positionValue instanceof Long)) {
                throw new IllegalArgumentException("seek value must be an integer");
            }
            long positionSeconds = (Long) positionValue;
            if (positionSeconds < 0L || positionSeconds > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("seek value must be in range 0..2147483647");
            }
            return new RemoteCommand(action, -1, (int) positionSeconds, -1);
        }
        if (action == Action.SET_VOLUME) {
            Object volumeValue = values.get("value");
            if (!(volumeValue instanceof Long)) {
                throw new IllegalArgumentException("set_volume value must be an integer");
            }
            long volume = (Long) volumeValue;
            if (volume < 0L || volume > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "set_volume value must be in range 0..2147483647"
                );
            }
            return new RemoteCommand(action, -1, -1, (int) volume);
        }
        if (hasValue) {
            throw new IllegalArgumentException(
                    "value is only valid for set_rating, seek, or set_volume"
            );
        }
        return new RemoteCommand(action, -1, -1, -1);
    }

    private static final class Parser {
        private final String input;
        private int position;

        Parser(String input) {
            if (input == null) {
                throw new IllegalArgumentException("request body is required");
            }
            this.input = input;
        }

        Map<String, Object> parseObject() {
            skipWhitespace();
            expect('{');
            Map<String, Object> values = new HashMap<>();
            skipWhitespace();
            if (take('}')) {
                finish();
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
                    throw new IllegalArgumentException("duplicate field: " + key);
                }
                values.put(key, value);
                skipWhitespace();
                if (take('}')) {
                    finish();
                    return values;
                }
                expect(',');
            }
        }

        private Object parseValue() {
            if (peek() == '"') {
                return parseString();
            }
            if (peek() == '-' || isDigit(peek())) {
                return parseInteger();
            }
            if (matches("null")) {
                position += 4;
                return null;
            }
            throw new IllegalArgumentException("only string and integer values are supported");
        }

        private Long parseInteger() {
            int start = position;
            if (take('-') && !isDigit(peek())) {
                throw new IllegalArgumentException("invalid integer");
            }
            if (take('0')) {
                if (isDigit(peek())) {
                    throw new IllegalArgumentException("invalid integer");
                }
            } else {
                if (!isDigit(peek())) {
                    throw new IllegalArgumentException("invalid integer");
                }
                while (isDigit(peek())) {
                    position++;
                }
            }
            if (peek() == '.' || peek() == 'e' || peek() == 'E') {
                throw new IllegalArgumentException("value must be an integer");
            }
            try {
                return Long.parseLong(input.substring(start, position));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("integer is out of range");
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (position < input.length()) {
                char character = input.charAt(position++);
                if (character == '"') {
                    return value.toString();
                }
                if (character < 0x20) {
                    throw new IllegalArgumentException("control character in string");
                }
                if (character != '\\') {
                    value.append(character);
                    continue;
                }
                if (position >= input.length()) {
                    throw new IllegalArgumentException("unfinished string escape");
                }
                char escaped = input.charAt(position++);
                switch (escaped) {
                    case '"':
                    case '\\':
                    case '/':
                        value.append(escaped);
                        break;
                    case 'b':
                        value.append('\b');
                        break;
                    case 'f':
                        value.append('\f');
                        break;
                    case 'n':
                        value.append('\n');
                        break;
                    case 'r':
                        value.append('\r');
                        break;
                    case 't':
                        value.append('\t');
                        break;
                    case 'u':
                        value.append(parseUnicodeEscape());
                        break;
                    default:
                        throw new IllegalArgumentException("invalid string escape");
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private char parseUnicodeEscape() {
            if (position + 4 > input.length()) {
                throw new IllegalArgumentException("unfinished unicode escape");
            }
            int value = 0;
            for (int index = 0; index < 4; index++) {
                int digit = Character.digit(input.charAt(position++), 16);
                if (digit < 0) {
                    throw new IllegalArgumentException("invalid unicode escape");
                }
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private void finish() {
            skipWhitespace();
            if (position != input.length()) {
                throw new IllegalArgumentException("unexpected trailing content");
            }
        }

        private void skipWhitespace() {
            while (position < input.length()) {
                char character = input.charAt(position);
                if (character != ' ' && character != '\t'
                        && character != '\r' && character != '\n') {
                    return;
                }
                position++;
            }
        }

        private boolean take(char expected) {
            if (peek() != expected) {
                return false;
            }
            position++;
            return true;
        }

        private void expect(char expected) {
            if (!take(expected)) {
                throw new IllegalArgumentException("expected '" + expected + "'");
            }
        }

        private boolean matches(String value) {
            return input.regionMatches(position, value, 0, value.length());
        }

        private char peek() {
            return position < input.length() ? input.charAt(position) : '\0';
        }

        private static boolean isDigit(char character) {
            return character >= '0' && character <= '9';
        }
    }
}
