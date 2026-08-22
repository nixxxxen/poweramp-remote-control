/*
 * Portions derived from PowerampAPI.java.
 * Copyright (C) 2011-2026 Maksim Petrov.
 * Modified for Poweramp Remote; see THIRD_PARTY_NOTICES.md for the upstream license.
 */
package dev.powerampremote.server;

/**
 * The small subset of Poweramp's public Intent API used by this MVP.
 *
 * <p>The canonical API definition lives at
 * https://github.com/maxmpz/powerampapi/blob/master/poweramp_api_lib/src/main/java/com/maxmpz/poweramp/player/PowerampAPI.java.
 * The source snapshot through Poweramp build 1026-beta was re-audited for release 0.9.0 and has no
 * public volume command; player-device volume therefore belongs to the separate Android system
 * volume adapter rather than this contract.</p>
 */
final class PowerampContract {
    static final String PACKAGE_NAME = "com.maxmpz.audioplayer";
    static final String API_RECEIVER_NAME = "com.maxmpz.audioplayer.player.PowerampAPIReceiver";

    static final String ACTION_API_COMMAND = "com.maxmpz.audioplayer.API_COMMAND";
    static final String ACTION_TRACK_CHANGED = "com.maxmpz.audioplayer.TRACK_CHANGED";
    static final String ACTION_STATUS_CHANGED = "com.maxmpz.audioplayer.STATUS_CHANGED";
    static final String ACTION_TRACK_POSITION_SYNC = "com.maxmpz.audioplayer.TPOS_SYNC";
    static final String ACTION_PLAYING_MODE_CHANGED =
            "com.maxmpz.audioplayer.PLAYING_MODE_CHANGED";

    static final String EXTRA_COMMAND = "cmd";
    static final String EXTRA_PACKAGE = "pak";
    static final String EXTRA_TRACK = "track";
    static final String EXTRA_STATE = "state";
    static final String EXTRA_RATING = "rating";
    static final String EXTRA_SHUFFLE = "shuffle";

    static final int COMMAND_TOGGLE_PLAY_PAUSE = 1;
    static final int COMMAND_PAUSE = 2;
    static final int COMMAND_PLAY = 3;
    static final int COMMAND_NEXT = 4;
    static final int COMMAND_PREVIOUS = 5;
    static final int COMMAND_SHUFFLE = 9;
    static final int COMMAND_SEEK = 15;
    static final int COMMAND_POSITION_SYNC = 16;
    static final int COMMAND_LIKE = 18;
    static final int COMMAND_UNLIKE = 19;
    static final int COMMAND_SET_RATING = 24;

    static final int STATE_UNKNOWN = -1;
    static final int STATE_STOPPED = 0;
    static final int STATE_PLAYING = 1;
    static final int STATE_PAUSED = 2;

    static final String ALBUM_ART_AUTHORITY = "com.maxmpz.audioplayer.aa";

    static final class Track {
        static final String ID = "id";
        static final String REAL_ID = "realId";
        static final String TITLE = "title";
        static final String ALBUM = "album";
        static final String ARTIST = "artist";
        static final String DURATION_SECONDS = "dur";
        static final String DURATION_MILLISECONDS = "durMs";
        static final String POSITION_SECONDS = "pos";
        static final String FILE_TYPE = "fileType";
        static final String CODEC = "codec";
        static final String SAMPLE_RATE = "sampleRate";
        static final String BITS_PER_SAMPLE = "bitsPerSample";
        static final String BIT_RATE = "bitRate";
        static final String RATING = "rating";
        static final String CATEGORY = "cat";
        static final String CATEGORY_URI = "catUri";
        static final String POSITION_IN_LIST = "posInList";
        static final String LIST_SIZE = "listSize";

        private Track() {
        }
    }

    static final class ShuffleModes {
        static final int NONE = 0;
        static final int ALL = 1;
        static final int SONGS = 2;
        static final int CATEGORIES = 3;
        static final int SONGS_AND_CATEGORIES = 4;
        static final int MAX = SONGS_AND_CATEGORIES;

        private ShuffleModes() {
        }
    }

    static final class FileTypes {
        static final int UNKNOWN = -1;
        static final int MP3 = 0;
        static final int FLAC = 1;
        static final int M4A = 2;
        static final int MP4 = 3;
        static final int OGG = 4;
        static final int WMA = 5;
        static final int WAV = 6;
        static final int TTA = 7;
        static final int APE = 8;
        static final int WV = 9;
        static final int AAC = 10;
        static final int MPGA = 11;
        static final int AMR = 12;
        static final int THREE_GP = 13;
        static final int MPC = 14;
        static final int AIFF = 15;
        static final int AIF = 16;
        static final int FLV = 17;
        static final int OPUS = 18;
        static final int DFF = 19;
        static final int DSF = 20;
        static final int MKA = 21;
        static final int TAK = 22;
        static final int STREAM = 23;
        static final int MKV = 24;
        static final int MOD = 25;
        static final int XM = 26;
        static final int S3M = 27;
        static final int IT = 28;
        static final int MPTM = 29;
        static final int OGA = 30;
        static final int WEBM = 31;
        static final int MAX = WEBM;

        private FileTypes() {
        }
    }

    static final class Categories {
        static final int ROOT = 0;
        static final int FILES = 30;
        static final int FOLDERS = 10;
        static final int FOLDERS_HIER = 20;
        static final int ALBUMS = 200;
        static final int ARTISTS = 500;
        static final int ARTISTS_ID_ALBUMS = 220;
        static final int ALBUM_ARTISTS = 520;
        static final int ALBUM_ARTISTS_ID_ALBUMS = 256;
        static final int ARTISTS_ALBUMS = 250;
        static final int GENRES = 320;
        static final int YEARS = 330;
        static final int GENRES_ID_ALBUMS = 210;
        static final int YEARS_ID_ALBUMS = 340;
        static final int COMPOSERS = 600;
        static final int COMPOSERS_ID_ALBUMS = 230;
        static final int PLAYLISTS = 100;
        static final int QUEUE = 800;
        static final int BOOKMARKS = 810;
        static final int STREAM_FILES = 60;
        static final int MOST_PLAYED = 43;
        static final int TOP_RATED = 48;
        static final int LOW_RATED = 50;
        static final int RECENTLY_PLAYED = 58;
        static final int RECENTLY_ADDED = 53;
        static final int LONG_TRACKS = 55;

        private Categories() {
        }
    }

    private PowerampContract() {
    }
}
