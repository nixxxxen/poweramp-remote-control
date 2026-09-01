package dev.powerampremote.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class MetadataChipStylePolicyTest {
    @Test
    public void sameRawMetadataAlwaysProducesSameStyle() {
        assertEquals(
                MetadataChipStylePolicy.codec("FLAC", "flac"),
                MetadataChipStylePolicy.codec("FLAC", "flac")
        );
        assertEquals(
                MetadataChipStylePolicy.bitDepth(24),
                MetadataChipStylePolicy.bitDepth(24)
        );
        assertEquals(
                MetadataChipStylePolicy.sampleRate(96_000),
                MetadataChipStylePolicy.sampleRate(96_000)
        );
    }

    @Test
    public void codecMatchingIsRootNormalizedAndCaseInsensitive() {
        assertEquals(
                MetadataChipStylePolicy.Style.CODEC_FLAC,
                MetadataChipStylePolicy.codec("flac", "FlAc")
        );
        assertEquals(
                MetadataChipStylePolicy.Style.CODEC_AAC,
                MetadataChipStylePolicy.codec("m4a", "aac-lc")
        );
        assertEquals(
                MetadataChipStylePolicy.Style.CODEC_OPUS,
                MetadataChipStylePolicy.codec("ogg", "OpUs")
        );
    }

    @Test
    public void commonCodecAndContainerFamiliesHaveStableVariants() {
        assertEquals(MetadataChipStylePolicy.Style.CODEC_FLAC,
                MetadataChipStylePolicy.codec("FLAC", null));
        assertEquals(MetadataChipStylePolicy.Style.CODEC_MP3,
                MetadataChipStylePolicy.codec("MPEG Audio", "MP3"));
        assertEquals(MetadataChipStylePolicy.Style.CODEC_MP3,
                MetadataChipStylePolicy.codec(null, "MPEG-1 Audio Layer III"));
        assertEquals(MetadataChipStylePolicy.Style.CODEC_AAC,
                MetadataChipStylePolicy.codec("M4A", "AAC"));
        assertEquals(MetadataChipStylePolicy.Style.CODEC_ALAC,
                MetadataChipStylePolicy.codec("M4A", "Apple Lossless ALAC"));
        assertEquals(MetadataChipStylePolicy.Style.CODEC_WAV_PCM,
                MetadataChipStylePolicy.codec("WAV", "PCM S24LE"));
        assertEquals(MetadataChipStylePolicy.Style.CODEC_AIFF,
                MetadataChipStylePolicy.codec("AIFF", "PCM"));
        assertEquals(MetadataChipStylePolicy.Style.CODEC_APE,
                MetadataChipStylePolicy.codec("APE", null));
        assertEquals(MetadataChipStylePolicy.Style.CODEC_APE,
                MetadataChipStylePolicy.codec("Monkey's Audio", null));
        assertEquals(MetadataChipStylePolicy.Style.CODEC_OGG_VORBIS,
                MetadataChipStylePolicy.codec("OGG", "Vorbis"));
        assertEquals(MetadataChipStylePolicy.Style.CODEC_OPUS,
                MetadataChipStylePolicy.codec("OGG", "Opus"));
        assertEquals(MetadataChipStylePolicy.Style.CODEC_WMA,
                MetadataChipStylePolicy.codec("WMA", "Windows Media Audio"));
    }

    @Test
    public void unknownCodecUsesSafeFallback() {
        assertEquals(
                MetadataChipStylePolicy.Style.CODEC_OTHER,
                MetadataChipStylePolicy.codec("XYZ", "vendor codec")
        );
        assertEquals(
                MetadataChipStylePolicy.Style.CODEC_OTHER,
                MetadataChipStylePolicy.codec(null, null)
        );
    }

    @Test
    public void bitDepthVariantsStayDistinctWithStableFallback() {
        MetadataChipStylePolicy.Style sixteen = MetadataChipStylePolicy.bitDepth(16);
        MetadataChipStylePolicy.Style twentyFour = MetadataChipStylePolicy.bitDepth(24);
        MetadataChipStylePolicy.Style thirtyTwo = MetadataChipStylePolicy.bitDepth(32);
        assertNotEquals(sixteen, twentyFour);
        assertNotEquals(twentyFour, thirtyTwo);
        assertNotEquals(sixteen, thirtyTwo);
        assertEquals(MetadataChipStylePolicy.Style.BIT_DEPTH_OTHER,
                MetadataChipStylePolicy.bitDepth(20));
        assertEquals(MetadataChipStylePolicy.Style.BIT_DEPTH_OTHER,
                MetadataChipStylePolicy.bitDepth(null));
        assertEquals(MetadataChipStylePolicy.Style.BIT_DEPTH_OTHER,
                MetadataChipStylePolicy.bitDepth(0));
    }

    @Test
    public void sampleRatesHaveStableExactHighAndFallbackVariants() {
        assertEquals(MetadataChipStylePolicy.Style.SAMPLE_RATE_44_1,
                MetadataChipStylePolicy.sampleRate(44_100));
        assertEquals(MetadataChipStylePolicy.Style.SAMPLE_RATE_48,
                MetadataChipStylePolicy.sampleRate(48_000));
        assertEquals(MetadataChipStylePolicy.Style.SAMPLE_RATE_88_2,
                MetadataChipStylePolicy.sampleRate(88_200));
        assertEquals(MetadataChipStylePolicy.Style.SAMPLE_RATE_96,
                MetadataChipStylePolicy.sampleRate(96_000));
        assertEquals(MetadataChipStylePolicy.Style.SAMPLE_RATE_176_4,
                MetadataChipStylePolicy.sampleRate(176_400));
        assertEquals(MetadataChipStylePolicy.Style.SAMPLE_RATE_192,
                MetadataChipStylePolicy.sampleRate(192_000));
        assertEquals(MetadataChipStylePolicy.Style.SAMPLE_RATE_HIGH,
                MetadataChipStylePolicy.sampleRate(384_000));
        assertEquals(MetadataChipStylePolicy.Style.SAMPLE_RATE_OTHER,
                MetadataChipStylePolicy.sampleRate(50_000));
        assertEquals(MetadataChipStylePolicy.Style.SAMPLE_RATE_OTHER,
                MetadataChipStylePolicy.sampleRate(null));
        assertEquals(MetadataChipStylePolicy.Style.SAMPLE_RATE_OTHER,
                MetadataChipStylePolicy.sampleRate(-1));
    }

    @Test
    public void bitrateHasNoUnverifiedQualityClassification() {
        assertEquals(
                MetadataChipStylePolicy.Style.BITRATE_MUTED,
                MetadataChipStylePolicy.bitrate(128)
        );
        assertEquals(
                MetadataChipStylePolicy.Style.BITRATE_MUTED,
                MetadataChipStylePolicy.bitrate(320_000)
        );
        assertEquals(
                MetadataChipStylePolicy.Style.BITRATE_MUTED,
                MetadataChipStylePolicy.bitrate(null)
        );
    }

    @Test
    public void stylePolicyHasNoArtworkPaletteInputOrState() {
        for (Method method : MetadataChipStylePolicy.class.getDeclaredMethods()) {
            for (Class<?> parameterType : method.getParameterTypes()) {
                assertNotEquals(ArtworkPalette.class, parameterType);
            }
        }
        for (Field field : MetadataChipStylePolicy.class.getDeclaredFields()) {
            assertNotEquals(ArtworkPalette.class, field.getType());
        }
    }
}
