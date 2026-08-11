package dev.r4remote.poweramp;

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
            values.add(audio.bitsPerSample + " бит");
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
                && source.positionInList < source.listSize) {
            // Poweramp exposes a list index. Present it as a human-friendly one-based position.
            values.add((source.positionInList + 1) + " / " + source.listSize);
        }
        return join(values);
    }

    static String formatSampleRate(int sampleRate) {
        if (sampleRate <= 0) {
            return null;
        }
        if (sampleRate >= 1_000_000) {
            return decimal(sampleRate, 1_000_000) + " МГц";
        }
        return decimal(sampleRate, 1_000) + " кГц";
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
        return kiloBitsPerSecond + " кбит/с";
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
                return "Корень библиотеки";
            case PowerampContract.Categories.FILES:
                return "Все треки";
            case PowerampContract.Categories.FOLDERS:
                return "Папка";
            case PowerampContract.Categories.FOLDERS_HIER:
                return "Иерархия папок";
            case PowerampContract.Categories.ALBUMS:
                return "Альбом";
            case PowerampContract.Categories.ARTISTS:
                return "Исполнитель";
            case PowerampContract.Categories.ARTISTS_ID_ALBUMS:
                return "Альбомы исполнителя";
            case PowerampContract.Categories.ALBUM_ARTISTS:
                return "Исполнитель альбома";
            case PowerampContract.Categories.ALBUM_ARTISTS_ID_ALBUMS:
                return "Альбомы исполнителя альбома";
            case PowerampContract.Categories.ARTISTS_ALBUMS:
                return "Альбомы по исполнителям";
            case PowerampContract.Categories.GENRES:
                return "Жанр";
            case PowerampContract.Categories.GENRES_ID_ALBUMS:
                return "Альбомы жанра";
            case PowerampContract.Categories.YEARS:
                return "Год";
            case PowerampContract.Categories.YEARS_ID_ALBUMS:
                return "Альбомы года";
            case PowerampContract.Categories.COMPOSERS:
                return "Композитор";
            case PowerampContract.Categories.COMPOSERS_ID_ALBUMS:
                return "Альбомы композитора";
            case PowerampContract.Categories.PLAYLISTS:
                return "Плейлист";
            case PowerampContract.Categories.QUEUE:
                return "Очередь";
            case PowerampContract.Categories.BOOKMARKS:
                return "Закладки";
            case PowerampContract.Categories.STREAM_FILES:
                return "Поток";
            case PowerampContract.Categories.MOST_PLAYED:
                return "Часто воспроизводимые";
            case PowerampContract.Categories.TOP_RATED:
                return "Высоко оценённые";
            case PowerampContract.Categories.LOW_RATED:
                return "Низко оценённые";
            case PowerampContract.Categories.RECENTLY_PLAYED:
                return "Недавно воспроизводимые";
            case PowerampContract.Categories.RECENTLY_ADDED:
                return "Недавно добавленные";
            case PowerampContract.Categories.LONG_TRACKS:
                return "Длинные треки";
            default:
                return category >= 0 ? "Другой источник" : null;
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
                .toPlainString()
                .replace('.', ',');
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
