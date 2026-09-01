package dev.powerampremote.phone;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

import java.util.EnumMap;

/** Activity-owned bounded cache of fixed metadata-chip backgrounds. */
final class MetadataChipDrawableFactory {
    private final EnumMap<MetadataChipStylePolicy.Style, Integer> accentColors =
            new EnumMap<>(MetadataChipStylePolicy.Style.class);
    private final EnumMap<MetadataChipStylePolicy.Style, Drawable> backgrounds =
            new EnumMap<>(MetadataChipStylePolicy.Style.class);
    private final int surfaceColor;
    private final int strokeWidthPixels;
    private final float cornerRadiusPixels;

    MetadataChipDrawableFactory(Context context) {
        surfaceColor = context.getColor(R.color.metadata_chip_surface);
        strokeWidthPixels = context.getResources().getDimensionPixelSize(
                R.dimen.metadata_chip_stroke_width
        );
        cornerRadiusPixels = context.getResources().getDimension(
                R.dimen.metadata_chip_corner_radius
        );
        for (MetadataChipStylePolicy.Style style : MetadataChipStylePolicy.Style.values()) {
            accentColors.put(style, context.getColor(accentColorResource(style)));
        }
    }

    Drawable background(MetadataChipStylePolicy.Style style) {
        Drawable cached = backgrounds.get(style);
        if (cached != null) return cached;
        GradientDrawable created = new GradientDrawable();
        created.setShape(GradientDrawable.RECTANGLE);
        created.setColor(surfaceColor);
        created.setStroke(strokeWidthPixels, textColor(style));
        created.setCornerRadius(cornerRadiusPixels);
        backgrounds.put(style, created);
        return created;
    }

    int textColor(MetadataChipStylePolicy.Style style) {
        Integer color = accentColors.get(style);
        if (color == null) throw new AssertionError("Missing metadata style color: " + style);
        return color;
    }

    private static int accentColorResource(MetadataChipStylePolicy.Style style) {
        switch (style) {
            case CODEC_FLAC:
                return R.color.metadata_codec_flac;
            case CODEC_MP3:
                return R.color.metadata_codec_mp3;
            case CODEC_AAC:
                return R.color.metadata_codec_aac;
            case CODEC_ALAC:
                return R.color.metadata_codec_alac;
            case CODEC_WAV_PCM:
                return R.color.metadata_codec_wav_pcm;
            case CODEC_AIFF:
                return R.color.metadata_codec_aiff;
            case CODEC_APE:
                return R.color.metadata_codec_ape;
            case CODEC_OGG_VORBIS:
                return R.color.metadata_codec_ogg_vorbis;
            case CODEC_OPUS:
                return R.color.metadata_codec_opus;
            case CODEC_WMA:
                return R.color.metadata_codec_wma;
            case CODEC_OTHER:
                return R.color.metadata_codec_other;
            case BIT_DEPTH_16:
                return R.color.metadata_bit_depth_16;
            case BIT_DEPTH_24:
                return R.color.metadata_bit_depth_24;
            case BIT_DEPTH_32:
                return R.color.metadata_bit_depth_32;
            case BIT_DEPTH_OTHER:
                return R.color.metadata_bit_depth_other;
            case SAMPLE_RATE_44_1:
                return R.color.metadata_sample_rate_44_1;
            case SAMPLE_RATE_48:
                return R.color.metadata_sample_rate_48;
            case SAMPLE_RATE_88_2:
                return R.color.metadata_sample_rate_88_2;
            case SAMPLE_RATE_96:
                return R.color.metadata_sample_rate_96;
            case SAMPLE_RATE_176_4:
                return R.color.metadata_sample_rate_176_4;
            case SAMPLE_RATE_192:
                return R.color.metadata_sample_rate_192;
            case SAMPLE_RATE_HIGH:
                return R.color.metadata_sample_rate_high;
            case SAMPLE_RATE_OTHER:
                return R.color.metadata_sample_rate_other;
            case BITRATE_MUTED:
                return R.color.metadata_bitrate_muted;
            default:
                throw new AssertionError("Unhandled metadata style: " + style);
        }
    }
}
