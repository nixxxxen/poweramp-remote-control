package dev.powerampremote.server;

/**
 * Historical API v1 category-name values retained byte-for-byte for wire compatibility.
 *
 * <p>Native and Web presentation must use the raw numeric category with their own localized
 * labels. These values are protocol data, not Server UI copy.</p>
 */
final class ApiV1SourceCategoryNames {
    private ApiV1SourceCategoryNames() {
    }

    static String name(int category) {
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
}
