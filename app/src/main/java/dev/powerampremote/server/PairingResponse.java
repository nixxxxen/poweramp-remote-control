package dev.powerampremote.server;

/** Successful exchange response carrying the persistent credential outside the QR code. */
final class PairingResponse {
    private PairingResponse() {
    }

    static String toJson(String serverId, String deviceName, String token) {
        return "{\"apiVersion\":1,\"serverId\":" + quote(serverId)
                + ",\"deviceName\":" + quote(deviceName)
                + ",\"token\":" + quote(token) + '}';
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"': result.append("\\\""); break;
                case '\\': result.append("\\\\"); break;
                case '\b': result.append("\\b"); break;
                case '\f': result.append("\\f"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (character < 0x20) {
                        result.append("\\u")
                                .append(HEX[(character >> 12) & 0x0F])
                                .append(HEX[(character >> 8) & 0x0F])
                                .append(HEX[(character >> 4) & 0x0F])
                                .append(HEX[character & 0x0F]);
                    } else {
                        result.append(character);
                    }
            }
        }
        return result.append('"').toString();
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();
}
