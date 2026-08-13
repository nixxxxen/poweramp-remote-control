package dev.r4remote.poweramp.phone;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Presentation-only formatting that preserves raw bitrate and list position semantics. */
final class RemoteMetadataFormatter {
    private RemoteMetadataFormatter() {
    }

    static String audio(RemoteState state) {
        List<String> values = new ArrayList<>();
        add(values, state.fileTypeName);
        if (state.codec != null
                && (state.fileTypeName == null
                || !state.fileTypeName.equalsIgnoreCase(state.codec))) {
            add(values, state.codec.toUpperCase(Locale.ROOT));
        }
        if (state.bitsPerSample != null) values.add(state.bitsPerSample + " bit");
        if (state.sampleRate != null) values.add(state.sampleRate + " Hz");
        if (state.bitRate != null) values.add("bitrate " + state.bitRate + " raw");
        return join(values);
    }

    static String source(RemoteState state) {
        List<String> values = new ArrayList<>();
        add(values, state.sourceCategoryName);
        if (state.positionInList != null || state.listSize != null) {
            values.add("list " + nullable(state.positionInList)
                    + " / " + nullable(state.listSize) + " raw");
        }
        return join(values);
    }

    private static String nullable(Integer value) {
        return value == null ? "—" : value.toString();
    }

    private static void add(List<String> values, String value) {
        if (value != null && !value.trim().isEmpty()) values.add(value);
    }

    private static String join(List<String> values) {
        return values.isEmpty() ? null : String.join(" · ", values);
    }
}
