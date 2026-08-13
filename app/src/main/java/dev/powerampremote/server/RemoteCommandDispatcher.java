package dev.powerampremote.server;

/** Pure routing layer so network actions can be tested independently of Android and Poweramp. */
final class RemoteCommandDispatcher {
    interface Target {
        void play();

        void pause();

        void previous();

        void next();

        void seekTo(int positionSeconds);

        void setShuffle(boolean enabled);

        void setRating(int rating);
    }

    private RemoteCommandDispatcher() {
    }

    static void dispatch(RemoteCommand command, Target target) {
        switch (command.action) {
            case PLAY:
                target.play();
                break;
            case PAUSE:
                target.pause();
                break;
            case PREVIOUS:
                target.previous();
                break;
            case NEXT:
                target.next();
                break;
            case SEEK:
                target.seekTo(command.positionSeconds);
                break;
            case SHUFFLE_ON:
                target.setShuffle(true);
                break;
            case SHUFFLE_OFF:
                target.setShuffle(false);
                break;
            case SET_RATING:
                target.setRating(command.rating);
                break;
            default:
                throw new IllegalArgumentException("unsupported action");
        }
    }
}
