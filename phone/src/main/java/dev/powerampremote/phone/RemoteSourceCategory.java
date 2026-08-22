package dev.powerampremote.phone;

/** Verified Poweramp public category values carried unchanged by the API v1 state contract. */
final class RemoteSourceCategory {
    static final int ROOT = 0;
    static final int FILES = 30;
    static final int FOLDERS = 10;
    static final int FOLDERS_HIERARCHY = 20;
    static final int ALBUMS = 200;
    static final int ARTISTS = 500;
    static final int ARTIST_ALBUMS = 220;
    static final int ALBUM_ARTISTS = 520;
    static final int ALBUM_ARTIST_ALBUMS = 256;
    static final int ARTISTS_ALBUMS = 250;
    static final int GENRES = 320;
    static final int GENRE_ALBUMS = 210;
    static final int YEARS = 330;
    static final int YEAR_ALBUMS = 340;
    static final int COMPOSERS = 600;
    static final int COMPOSER_ALBUMS = 230;
    static final int PLAYLISTS = 100;
    static final int QUEUE = 800;
    static final int BOOKMARKS = 810;
    static final int STREAMS = 60;
    static final int MOST_PLAYED = 43;
    static final int TOP_RATED = 48;
    static final int LOW_RATED = 50;
    static final int RECENTLY_PLAYED = 58;
    static final int RECENTLY_ADDED = 53;
    static final int LONG_TRACKS = 55;

    private RemoteSourceCategory() {
    }
}
