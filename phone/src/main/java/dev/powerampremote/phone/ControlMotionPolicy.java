package dev.powerampremote.phone;

/** Pure confirmed-state policy for idempotent, retargetable control motion. */
final class ControlMotionPolicy {
    enum Mode {
        NONE,
        IMMEDIATE,
        ANIMATE,
        PULSE,
        RESET
    }

    static final class Update {
        final Mode mode;
        final float startProgress;
        final float targetProgress;

        private Update(Mode mode, float startProgress, float targetProgress) {
            this.mode = mode;
            this.startProgress = startProgress;
            this.targetProgress = targetProgress;
        }

        static Update none(float targetProgress) {
            return new Update(Mode.NONE, targetProgress, targetProgress);
        }

        static Update immediate(float targetProgress) {
            return new Update(Mode.IMMEDIATE, targetProgress, targetProgress);
        }

        static Update animate(float startProgress, float targetProgress) {
            return new Update(Mode.ANIMATE, clamp(startProgress), targetProgress);
        }
    }

    static final class Binary {
        private Boolean confirmedState;

        Update update(
                boolean targetState,
                boolean replayingState,
                boolean hostVisible,
                boolean animationsEnabled,
                float currentProgress
        ) {
            float targetProgress = targetState ? 1f : 0f;
            if (confirmedState == null) {
                confirmedState = targetState;
                return Update.immediate(targetProgress);
            }
            if (confirmedState == targetState) return Update.none(targetProgress);

            confirmedState = targetState;
            if (replayingState || !hostVisible || !animationsEnabled) {
                return Update.immediate(targetProgress);
            }
            return Update.animate(currentProgress, targetProgress);
        }

        float confirmedProgress() {
            return Boolean.TRUE.equals(confirmedState) ? 1f : 0f;
        }
    }

    static final class Like {
        private Boolean confirmedLiked;

        Mode update(
                boolean liked,
                boolean replayingState,
                boolean hostVisible,
                boolean animationsEnabled
        ) {
            if (confirmedLiked == null) {
                confirmedLiked = liked;
                return Mode.RESET;
            }
            if (confirmedLiked == liked) return Mode.NONE;

            confirmedLiked = liked;
            return liked && !replayingState && hostVisible && animationsEnabled
                    ? Mode.PULSE : Mode.RESET;
        }
    }

    private ControlMotionPolicy() {
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
