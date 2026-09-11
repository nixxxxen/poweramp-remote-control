package dev.powerampremote.phone;

/** Pure Queue loading/error policy; loaded rows remain visible through transient failures. */
final class QueuePresentationPolicy {
    enum Message {
        NONE,
        LOADING,
        EMPTY,
        DISCONNECTED,
        PERMISSION_REQUIRED,
        UNSUPPORTED,
        PROVIDER_UNAVAILABLE,
        PAGE_EXPIRED,
        ERROR,
        INCOMPLETE
    }

    enum Action { NONE, RETRY, RELOAD, LOAD_MORE }

    static final class Model {
        final boolean rowsVisible;
        final boolean progressVisible;
        final Message message;
        final Action action;

        private Model(
                boolean rowsVisible,
                boolean progressVisible,
                Message message,
                Action action
        ) {
            this.rowsVisible = rowsVisible;
            this.progressVisible = progressVisible;
            this.message = message;
            this.action = action;
        }
    }

    static Model resolve(
            boolean connected,
            boolean initialized,
            boolean loading,
            int itemCount,
            boolean canLoadMore,
            boolean truncated,
            RemoteClientController.LibraryFailure failure
    ) {
        boolean rowsVisible = itemCount > 0;
        if (!connected) {
            return new Model(rowsVisible, false, Message.DISCONNECTED, Action.NONE);
        }
        if (failure != null) {
            switch (failure) {
                case DISCONNECTED:
                    return new Model(rowsVisible, false, Message.DISCONNECTED, Action.RETRY);
                case PERMISSION_REQUIRED:
                    return new Model(
                            rowsVisible, false, Message.PERMISSION_REQUIRED, Action.RETRY
                    );
                case UNSUPPORTED:
                    return new Model(rowsVisible, false, Message.UNSUPPORTED, Action.NONE);
                case PROVIDER_UNAVAILABLE:
                    return new Model(
                            rowsVisible, false, Message.PROVIDER_UNAVAILABLE, Action.RETRY
                    );
                case PAGE_EXPIRED:
                    return new Model(rowsVisible, false, Message.PAGE_EXPIRED, Action.RELOAD);
                case AUTHENTICATION:
                case SERVER_ERROR:
                default:
                    return new Model(rowsVisible, false, Message.ERROR, Action.RETRY);
            }
        }
        if (loading) {
            return new Model(rowsVisible, true, rowsVisible ? Message.NONE : Message.LOADING,
                    Action.NONE);
        }
        if (initialized && itemCount == 0) {
            return new Model(false, false, Message.EMPTY, Action.NONE);
        }
        if (truncated) {
            return new Model(rowsVisible, false, Message.INCOMPLETE, Action.NONE);
        }
        if (canLoadMore) {
            return new Model(rowsVisible, false, Message.NONE, Action.LOAD_MORE);
        }
        return new Model(rowsVisible, false, Message.NONE, Action.NONE);
    }

    private QueuePresentationPolicy() { }
}
