package dev.powerampremote.server;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class TrackMetadataFormatter {
    private TrackMetadataFormatter() {
    }

    static String formatAudio(TrackInfo.AudioProperties audio) {
        List<String> values = new ArrayList<>();

        String fileType = fileTypeName(audio.fileType);
        String codec = cleanCodec(audio.codec);
        if (fileType != null && codec != null && !fileType.equalsIgnoreCase(codec)) {
            values.add(fileType + " / " + codec);
        } else if (fileType != null) {
            values.add(fileType);
        } else if (codec != null) {
            values.add(codec);
        }

        if (audio.bitsPerSample > 0) {
            values.add(audio.bitsPerSample + " bit");
        }
        String sampleRate = formatSampleRate(audio.sampleRate);
        if (sampleRate != null) {
            values.add(sampleRate);
        }
        String bitRate = formatBitRate(audio.bitRate);
        if (bitRate != null) {
            values.add(bitRate);
        }
        return join(values);
    }

    static String formatSource(TrackInfo.PlaybackSource source) {
        List<String> values = new ArrayList<>();
        String category = categoryName(source.category);
        if (category != null) {
            values.add(category);
        }
        if (source.positionInList >= 0
                && source.listSize > 0
                && source.positionInList <= source.listSize) {
            // The public index base is not guaranteed, so preserve the raw presentation value.
            values.add(source.positionInList + " / " + source.listSize);
        }
        return join(values);
    }

    static String formatSampleRate(int sampleRate) {
        if (sampleRate <= 0) {
            return null;
        }
        if (sampleRate >= 1_000_000) {
            return decimal(sampleRate, 1_000_000) + " MHz";
        }
        return decimal(sampleRate, 1_000) + " kHz";
    }

    static String formatBitRate(int bitRate) {
        if (bitRate <= 0) {
            return null;
        }
        // The public API does not specify the unit. Current builds normally expose bit/s, while
        // older integrations may already expose kbit/s, so accept both representations.
        int kiloBitsPerSecond = bitRate >= 10_000
                ? Math.round(bitRate / 1_000f)
                : bitRate;
        return kiloBitsPerSecond + " kbps";
    }

    static String fileTypeName(int fileType) {
        switch (fileType) {
            case PowerampContract.FileTypes.MP3:
                return "MP3";
            case PowerampContract.FileTypes.FLAC:
                return "FLAC";
            case PowerampContract.FileTypes.M4A:
                return "M4A";
            case PowerampContract.FileTypes.MP4:
                return "MP4";
            case PowerampContract.FileTypes.OGG:
                return "OGG";
            case PowerampContract.FileTypes.WMA:
                return "WMA";
            case PowerampContract.FileTypes.WAV:
                return "WAV";
            case PowerampContract.FileTypes.TTA:
                return "TTA";
            case PowerampContract.FileTypes.APE:
                return "APE";
            case PowerampContract.FileTypes.WV:
                return "WV";
            case PowerampContract.FileTypes.AAC:
                return "AAC";
            case PowerampContract.FileTypes.MPGA:
                return "MPGA";
            case PowerampContract.FileTypes.AMR:
                return "AMR";
            case PowerampContract.FileTypes.THREE_GP:
                return "3GP";
            case PowerampContract.FileTypes.MPC:
                return "MPC";
            case PowerampContract.FileTypes.AIFF:
                return "AIFF";
            case PowerampContract.FileTypes.AIF:
                return "AIF";
            case PowerampContract.FileTypes.FLV:
                return "FLV";
            case PowerampContract.FileTypes.OPUS:
                return "OPUS";
            case PowerampContract.FileTypes.DFF:
                return "DFF";
            case PowerampContract.FileTypes.DSF:
                return "DSF";
            case PowerampContract.FileTypes.MKA:
                return "MKA";
            case PowerampContract.FileTypes.TAK:
                return "TAK";
            case PowerampContract.FileTypes.STREAM:
                return "STREAM";
            case PowerampContract.FileTypes.MKV:
                return "MKV";
            case PowerampContract.FileTypes.MOD:
                return "MOD";
            case PowerampContract.FileTypes.XM:
                return "XM";
            case PowerampContract.FileTypes.S3M:
                return "S3M";
            case PowerampContract.FileTypes.IT:
                return "IT";
            case PowerampContract.FileTypes.MPTM:
                return "MPTM";
            case PowerampContract.FileTypes.OGA:
                return "OGA";
            case PowerampContract.FileTypes.WEBM:
                return "WEBM";
            default:
                return null;
        }
    }

    static String categoryName(int category) {
        switch (category) {
            case PowerampContract.Categories.ROOT:
                return "Library root";
            case PowerampContract.Categories.FILES:
                return "All tracks";
            case PowerampContract.Categories.FOLDERS:
                return "Folder";
            case PowerampContract.Categories.FOLDERS_HIER:
                return "Folder hierarchy";
            case PowerampContract.Categories.ALBUMS:
                return "Album";
            case PowerampContract.Categories.ARTISTS:
                return "Artist";
            case PowerampContract.Categories.ARTISTS_ID_ALBUMS:
                return "Artist albums";
            case PowerampContract.Categories.ALBUM_ARTISTS:
                return "Album artist";
            case PowerampContract.Categories.ALBUM_ARTISTS_ID_ALBUMS:
                return "Album artist albums";
            case PowerampContract.Categories.ARTISTS_ALBUMS:
                return "Albums by artist";
            case PowerampContract.Categories.GENRES:
                return "Genre";
            case PowerampContract.Categories.GENRES_ID_ALBUMS:
                return "Genre albums";
            case PowerampContract.Categories.YEARS:
                return "Year";
            case PowerampContract.Categories.YEARS_ID_ALBUMS:
                return "Year albums";
            case PowerampContract.Categories.COMPOSERS:
                return "Composer";
            case PowerampContract.Categories.COMPOSERS_ID_ALBUMS:
                return "Composer albums";
            case PowerampContract.Categories.PLAYLISTS:
                return "Playlist";
            case PowerampContract.Categories.QUEUE:
                return "Queue";
            case PowerampContract.Categories.BOOKMARKS:
                return "Bookmarks";
            case PowerampContract.Categories.STREAM_FILES:
                return "Stream";
            case PowerampContract.Categories.MOST_PLAYED:
                return "Most played";
            case PowerampContract.Categories.TOP_RATED:
                return "Top rated";
            case PowerampContract.Categories.LOW_RATED:
                return "Low rated";
            case PowerampContract.Categories.RECENTLY_PLAYED:
                return "Recently played";
            case PowerampContract.Categories.RECENTLY_ADDED:
                return "Recently added";
            case PowerampContract.Categories.LONG_TRACKS:
                return "Long tracks";
            default:
                return category >= 0 ? "Other source" : null;
        }
    }

    private static String cleanCodec(String codec) {
        if (codec == null) {
            return null;
        }
        String value = codec.trim();
        return value.isEmpty() ? null : value.toUpperCase(Locale.ROOT);
    }

    private static String decimal(int value, int divisor) {
        return BigDecimal.valueOf(value)
                .divide(BigDecimal.valueOf(divisor), 4, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(" · ");
            }
            result.append(value);
        }
        return result.toString();
    }
}
