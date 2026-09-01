package dev.powerampremote.phone;

import java.util.Locale;

/** Pure, artwork-independent style selection from raw API v1 metadata. */
final class MetadataChipStylePolicy {
    enum Style {
        CODEC_FLAC,
        CODEC_MP3,
        CODEC_AAC,
        CODEC_ALAC,
        CODEC_WAV_PCM,
        CODEC_AIFF,
        CODEC_APE,
        CODEC_OGG_VORBIS,
        CODEC_OPUS,
        CODEC_WMA,
        CODEC_OTHER,
        BIT_DEPTH_16,
        BIT_DEPTH_24,
        BIT_DEPTH_32,
        BIT_DEPTH_OTHER,
        SAMPLE_RATE_44_1,
        SAMPLE_RATE_48,
        SAMPLE_RATE_88_2,
        SAMPLE_RATE_96,
        SAMPLE_RATE_176_4,
        SAMPLE_RATE_192,
        SAMPLE_RATE_HIGH,
        SAMPLE_RATE_OTHER,
        BITRATE_MUTED
    }

    static Style codec(String fileTypeName, String codec) {
        String tokens = normalizeTokens(fileTypeName) + normalizeTokens(codec);
        if (has(tokens, "FLAC")) return Style.CODEC_FLAC;
        if (has(tokens, "MP3")
                || has(tokens, "MPEG LAYER III")
                || has(tokens, "MPEG LAYER 3")
                || has(tokens, "AUDIO LAYER III")
                || has(tokens, "AUDIO LAYER 3")) {
            return Style.CODEC_MP3;
        }
        if (has(tokens, "ALAC") || has(tokens, "APPLE LOSSLESS")) {
            return Style.CODEC_ALAC;
        }
        if (has(tokens, "AAC")
                || has(tokens, "M4A")
                || has(tokens, "M4B")
                || has(tokens, "MP4A")
                || has(tokens, "MPEG4 AUDIO")
                || has(tokens, "MPEG 4 AUDIO")) {
            return Style.CODEC_AAC;
        }
        if (has(tokens, "OPUS")) return Style.CODEC_OPUS;
        if (has(tokens, "AIFF") || has(tokens, "AIF") || has(tokens, "AIFC")) {
            return Style.CODEC_AIFF;
        }
        if (has(tokens, "APE") || has(tokens, "MONKEY")) {
            return Style.CODEC_APE;
        }
        if (has(tokens, "OGG") || has(tokens, "VORBIS")) {
            return Style.CODEC_OGG_VORBIS;
        }
        if (has(tokens, "WMA") || has(tokens, "WINDOWS MEDIA AUDIO")) {
            return Style.CODEC_WMA;
        }
        if (has(tokens, "WAV")
                || has(tokens, "WAVE")
                || has(tokens, "PCM")
                || has(tokens, "LPCM")) {
            return Style.CODEC_WAV_PCM;
        }
        return Style.CODEC_OTHER;
    }

    static Style bitDepth(Integer bitsPerSample) {
        if (bitsPerSample == null || bitsPerSample <= 0) return Style.BIT_DEPTH_OTHER;
        switch (bitsPerSample) {
            case 16:
                return Style.BIT_DEPTH_16;
            case 24:
                return Style.BIT_DEPTH_24;
            case 32:
                return Style.BIT_DEPTH_32;
            default:
                return Style.BIT_DEPTH_OTHER;
        }
    }

    static Style sampleRate(Integer sampleRate) {
        if (sampleRate == null || sampleRate <= 0) return Style.SAMPLE_RATE_OTHER;
        switch (sampleRate) {
            case 44_100:
                return Style.SAMPLE_RATE_44_1;
            case 48_000:
                return Style.SAMPLE_RATE_48;
            case 88_200:
                return Style.SAMPLE_RATE_88_2;
            case 96_000:
                return Style.SAMPLE_RATE_96;
            case 176_400:
                return Style.SAMPLE_RATE_176_4;
            case 192_000:
                return Style.SAMPLE_RATE_192;
            default:
                return sampleRate > 192_000
                        ? Style.SAMPLE_RATE_HIGH : Style.SAMPLE_RATE_OTHER;
        }
    }

    static Style bitrate(Integer bitRate) {
        return Style.BITRATE_MUTED;
    }

    private MetadataChipStylePolicy() {
    }

    private static boolean has(String normalizedTokens, String tokenOrPhrase) {
        return normalizedTokens.contains(" " + tokenOrPhrase + " ");
    }

    private static String normalizeTokens(String value) {
        if (value == null || value.isEmpty()) return " ";
        String upper = value.toUpperCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(upper.length() + 2);
        normalized.append(' ');
        boolean separator = true;
        for (int index = 0; index < upper.length(); index++) {
            char character = upper.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                normalized.append(character);
                separator = false;
            } else if (!separator) {
                normalized.append(' ');
                separator = true;
            }
        }
        if (!separator) normalized.append(' ');
        return normalized.toString();
    }
}
