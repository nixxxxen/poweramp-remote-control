package dev.powerampremote.phone;

/** Exact REST control request bodies accepted by API v1. */
final class RemoteCommandJson {
    private RemoteCommandJson() {
    }

    static String play() { return action("play"); }
    static String pause() { return action("pause"); }
    static String previous() { return action("previous"); }
    static String next() { return action("next"); }
    static String shuffle(boolean enabled) { return action(enabled ? "shuffle_on" : "shuffle_off"); }

    static String seek(int positionSeconds) {
        if (positionSeconds < 0) {
            throw new IllegalArgumentException("negative seek position");
        }
        return valued("seek", positionSeconds);
    }

    static String rating(int rating) {
        if (rating < 0 || rating > 5) {
            throw new IllegalArgumentException("rating out of range");
        }
        return valued("set_rating", rating);
    }

    static String volume(int volume) {
        if (volume < 0) {
            throw new IllegalArgumentException("negative volume");
        }
        return valued("set_volume", volume);
    }

    private static String action(String action) {
        return "{\"action\":\"" + action + "\"}";
    }

    private static String valued(String action, int value) {
        return "{\"action\":\"" + action + "\",\"value\":" + value + '}';
    }
}
