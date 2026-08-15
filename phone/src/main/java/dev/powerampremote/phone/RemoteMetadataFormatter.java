package dev.powerampremote.phone;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Human-readable presentation for raw API v1 metadata values. */
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
        if (state.bitsPerSample != null && state.bitsPerSample > 0) {
            values.add(state.bitsPerSample + " бит");
        }
        String sampleRate = formatSampleRate(state.sampleRate);
        if (sampleRate != null) values.add(sampleRate);
        String bitRate = formatBitRate(state.bitRate);
        if (bitRate != null) values.add(bitRate);
        return join(values);
    }

    static String codec(RemoteState state) {
        List<String> values = new ArrayList<>();
        add(values, state.fileTypeName);
        if (state.codec != null
                && (state.fileTypeName == null
                || !state.fileTypeName.equalsIgnoreCase(state.codec))) {
            add(values, state.codec.toUpperCase(Locale.ROOT));
        }
        return join(values);
    }

    static String bitDepth(RemoteState state) {
        return state.bitsPerSample == null || state.bitsPerSample <= 0
                ? null : state.bitsPerSample + " бит";
    }

    static String sampleRate(RemoteState state) {
        return formatSampleRate(state.sampleRate);
    }

    static String bitrate(RemoteState state) {
        return formatBitRate(state.bitRate);
    }

    static String source(RemoteState state) {
        List<String> values = new ArrayList<>();
        add(values, state.sourceCategoryName);
        if (state.positionInList != null
                && state.listSize != null
                && state.positionInList >= 0
                && state.listSize > 0
                && state.positionInList <= state.listSize) {
            // API v1 preserves Poweramp's value and its index base is not publicly guaranteed.
            // Remove diagnostic labels without inventing an offset in presentation code.
            values.add(state.positionInList + " / " + state.listSize);
        }
        return join(values);
    }

    static String formatSampleRate(Integer sampleRate) {
        if (sampleRate == null || sampleRate <= 0) return null;
        if (sampleRate >= 1_000_000) {
            return decimal(sampleRate, 1_000_000) + " МГц";
        }
        return decimal(sampleRate, 1_000) + " кГц";
    }

    static String formatBitRate(Integer bitRate) {
        if (bitRate == null || bitRate <= 0) return null;
        // API v1 intentionally preserves Poweramp's value. Presentation accepts both the
        // bit/s values used by current builds and older integrations already reporting kbit/s.
        int kiloBitsPerSecond = bitRate >= 10_000
                ? Math.round(bitRate / 1_000f)
                : bitRate;
        return kiloBitsPerSecond + " кбит/с";
    }

    private static void add(List<String> values, String value) {
        if (value != null && !value.trim().isEmpty()) values.add(value);
    }

    private static String join(List<String> values) {
        return values.isEmpty() ? null : String.join(" · ", values);
    }

    private static String decimal(int value, int divisor) {
        return BigDecimal.valueOf(value)
                .divide(BigDecimal.valueOf(divisor), 4, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
                .replace('.', ',');
    }
}
