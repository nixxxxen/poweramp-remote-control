package dev.powerampremote.server;

/** Small ContentResolver boundary so row parsing and API contracts stay JVM-testable. */
interface PowerampLibraryProvider {
    enum Failure {
        PERMISSION_REQUIRED,
        UNAVAILABLE,
        ERROR,
        CANCELLED
    }

    final class ProviderException extends Exception {
        private static final long serialVersionUID = 1L;

        final Failure failure;

        ProviderException(Failure failure) {
            super(failure.name());
            this.failure = failure;
        }
    }

    interface Rows extends AutoCloseable {
        boolean moveToNext() throws ProviderException;

        Long longValue(String column);

        String textValue(String column);

        @Override
        void close();
    }

    /** One public Queue edit client retained only for one serialized add operation. */
    interface QueueEditor extends AutoCloseable {
        Long maximumSort() throws ProviderException;

        boolean insert(long folderFileId, long sort) throws ProviderException;

        void reload() throws ProviderException;

        @Override
        void close();
    }

    boolean isPowerampInstalled();

    Rows query(
            PowerampLibraryContract.Query query,
            int limit,
            LibraryCancellation cancellation
    ) throws ProviderException;

    QueueEditor openQueueEditor(LibraryCancellation cancellation) throws ProviderException;
}
