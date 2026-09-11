package dev.powerampremote.phone;

/** Fixed, bounded preference scopes for logical track-list views. */
enum LibrarySortView {
    ALL_TRACKS("all_tracks"),
    ARTIST("artist"),
    ALBUM("album"),
    FOLDER("folder"),
    PLAYLIST("playlist"),
    OTHER("other");

    final String key;

    LibrarySortView(String key) {
        this.key = key;
    }
}
