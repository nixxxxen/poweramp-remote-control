package dev.powerampremote.phone;

import android.content.Context;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Locale-aware, resource-driven presentation for raw API v1 metadata values. */
final class RemoteMetadataFormatter {
    private final Locale locale;
    private final String bitUnit;
    private final String kilohertzUnit;
    private final String megahertzUnit;
    private final String kilobitsPerSecondUnit;
    private final Map<Integer, String> categoryNames;
    private final String otherSource;

    RemoteMetadataFormatter(
            Locale locale,
            String bitUnit,
            String kilohertzUnit,
            String megahertzUnit,
            String kilobitsPerSecondUnit,
            Map<Integer, String> categoryNames,
            String otherSource
    ) {
        this.locale = locale == null ? Locale.ENGLISH : locale;
        this.bitUnit = bitUnit;
        this.kilohertzUnit = kilohertzUnit;
        this.megahertzUnit = megahertzUnit;
        this.kilobitsPerSecondUnit = kilobitsPerSecondUnit;
        this.categoryNames = Collections.unmodifiableMap(new HashMap<>(categoryNames));
        this.otherSource = otherSource;
    }

    static RemoteMetadataFormatter from(Context context) {
        Map<Integer, String> categories = new HashMap<>();
        categories.put(RemoteSourceCategory.ROOT,
                context.getString(R.string.source_category_library_root));
        categories.put(RemoteSourceCategory.FILES,
                context.getString(R.string.source_category_all_tracks));
        categories.put(RemoteSourceCategory.FOLDERS,
                context.getString(R.string.source_category_folder));
        categories.put(RemoteSourceCategory.FOLDERS_HIERARCHY,
                context.getString(R.string.source_category_folder_hierarchy));
        categories.put(RemoteSourceCategory.ALBUMS,
                context.getString(R.string.source_category_album));
        categories.put(RemoteSourceCategory.ARTISTS,
                context.getString(R.string.source_category_artist));
        categories.put(RemoteSourceCategory.ARTIST_ALBUMS,
                context.getString(R.string.source_category_artist_albums));
        categories.put(RemoteSourceCategory.ALBUM_ARTISTS,
                context.getString(R.string.source_category_album_artist));
        categories.put(RemoteSourceCategory.ALBUM_ARTIST_ALBUMS,
                context.getString(R.string.source_category_album_artist_albums));
        categories.put(RemoteSourceCategory.ARTISTS_ALBUMS,
                context.getString(R.string.source_category_artists_albums));
        categories.put(RemoteSourceCategory.GENRES,
                context.getString(R.string.source_category_genre));
        categories.put(RemoteSourceCategory.GENRE_ALBUMS,
                context.getString(R.string.source_category_genre_albums));
        categories.put(RemoteSourceCategory.YEARS,
                context.getString(R.string.source_category_year));
        categories.put(RemoteSourceCategory.YEAR_ALBUMS,
                context.getString(R.string.source_category_year_albums));
        categories.put(RemoteSourceCategory.COMPOSERS,
                context.getString(R.string.source_category_composer));
        categories.put(RemoteSourceCategory.COMPOSER_ALBUMS,
                context.getString(R.string.source_category_composer_albums));
        categories.put(RemoteSourceCategory.PLAYLISTS,
                context.getString(R.string.source_category_playlist));
        categories.put(RemoteSourceCategory.QUEUE,
                context.getString(R.string.source_category_queue));
        categories.put(RemoteSourceCategory.BOOKMARKS,
                context.getString(R.string.source_category_bookmarks));
        categories.put(RemoteSourceCategory.STREAMS,
                context.getString(R.string.source_category_stream));
        categories.put(RemoteSourceCategory.MOST_PLAYED,
                context.getString(R.string.source_category_most_played));
        categories.put(RemoteSourceCategory.TOP_RATED,
                context.getString(R.string.source_category_top_rated));
        categories.put(RemoteSourceCategory.LOW_RATED,
                context.getString(R.string.source_category_low_rated));
        categories.put(RemoteSourceCategory.RECENTLY_PLAYED,
                context.getString(R.string.source_category_recently_played));
        categories.put(RemoteSourceCategory.RECENTLY_ADDED,
                context.getString(R.string.source_category_recently_added));
        categories.put(RemoteSourceCategory.LONG_TRACKS,
                context.getString(R.string.source_category_long_tracks));
        return new RemoteMetadataFormatter(
                PhoneLocale.presentationLocale(context),
                context.getString(R.string.unit_bit),
                context.getString(R.string.unit_kilohertz),
                context.getString(R.string.unit_megahertz),
                context.getString(R.string.unit_kilobits_per_second),
                categories,
                context.getString(R.string.source_category_other)
        );
    }

    String audio(RemoteState state) {
        List<String> values = new ArrayList<>();
        add(values, state.fileTypeName);
        if (state.codec != null
                && (state.fileTypeName == null
                || !state.fileTypeName.equalsIgnoreCase(state.codec))) {
            add(values, state.codec.toUpperCase(Locale.ROOT));
        }
        if (state.bitsPerSample != null && state.bitsPerSample > 0) {
            values.add(withUnit(state.bitsPerSample, bitUnit));
        }
        String sampleRate = formatSampleRate(state.sampleRate);
        if (sampleRate != null) values.add(sampleRate);
        String bitRate = formatBitRate(state.bitRate);
        if (bitRate != null) values.add(bitRate);
        return join(values);
    }

    String codec(RemoteState state) {
        List<String> values = new ArrayList<>();
        add(values, state.fileTypeName);
        if (state.codec != null
                && (state.fileTypeName == null
                || !state.fileTypeName.equalsIgnoreCase(state.codec))) {
            add(values, state.codec.toUpperCase(Locale.ROOT));
        }
        return join(values);
    }

    String bitDepth(RemoteState state) {
        return state.bitsPerSample == null || state.bitsPerSample <= 0
                ? null : withUnit(state.bitsPerSample, bitUnit);
    }

    String sampleRate(RemoteState state) {
        return formatSampleRate(state.sampleRate);
    }

    String bitrate(RemoteState state) {
        return formatBitRate(state.bitRate);
    }

    String source(RemoteState state) {
        List<String> values = new ArrayList<>();
        if (state.sourceCategory != null && state.sourceCategory >= 0) {
            values.add(categoryNames.getOrDefault(state.sourceCategory, otherSource));
        }
        if (state.positionInList != null
                && state.listSize != null
                && state.positionInList >= 0
                && state.listSize > 0
                && state.positionInList <= state.listSize) {
            // API v1 preserves Poweramp's value and its index base is not publicly guaranteed.
            values.add(state.positionInList + " / " + state.listSize);
        }
        return join(values);
    }

    String formatSampleRate(Integer sampleRate) {
        if (sampleRate == null || sampleRate <= 0) return null;
        if (sampleRate >= 1_000_000) {
            return withUnit(decimal(sampleRate, 1_000_000), megahertzUnit);
        }
        return withUnit(decimal(sampleRate, 1_000), kilohertzUnit);
    }

    String formatBitRate(Integer bitRate) {
        if (bitRate == null || bitRate <= 0) return null;
        // API v1 intentionally preserves Poweramp's value. Presentation accepts both the
        // bit/s values used by current builds and older integrations already reporting kbit/s.
        int kiloBitsPerSecond = bitRate >= 10_000
                ? Math.round(bitRate / 1_000f)
                : bitRate;
        return withUnit(kiloBitsPerSecond, kilobitsPerSecondUnit);
    }

    private String decimal(int value, int divisor) {
        String result = BigDecimal.valueOf(value)
                .divide(BigDecimal.valueOf(divisor), 4, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
        char separator = DecimalFormatSymbols.getInstance(locale).getDecimalSeparator();
        return separator == '.' ? result : result.replace('.', separator);
    }

    private static String withUnit(Object value, String unit) {
        return value + " " + unit;
    }

    private static void add(List<String> values, String value) {
        if (value != null && !value.trim().isEmpty()) values.add(value);
    }

    private static String join(List<String> values) {
        return values.isEmpty() ? null : String.join(" · ", values);
    }
}
