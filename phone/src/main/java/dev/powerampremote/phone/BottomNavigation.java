package dev.powerampremote.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.content.Intent;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.ImageButton;

import java.util.WeakHashMap;

/** Fixed tab chrome plus content-only transitions over the one service-owned runtime. */
final class BottomNavigation {
    private static final String EXTRA_TRANSITION_FROM =
            "dev.powerampremote.phone.extra.TAB_TRANSITION_FROM";
    private static final String EXTRA_TRANSITION_TO =
            "dev.powerampremote.phone.extra.TAB_TRANSITION_TO";
    private static final long EXIT_DURATION_MILLISECONDS = 130L;
    private static final long ENTER_DURATION_MILLISECONDS = 170L;
    private static final WeakHashMap<Activity, Binding> BINDINGS = new WeakHashMap<>();

    enum Tab {
        PLAYER(0),
        LIBRARY(1),
        SEARCH(2),
        SETTINGS(3);

        final int index;

        Tab(int index) {
            this.index = index;
        }
    }

    private static final class Binding {
        Tab selected;
        View content;
        boolean transitionRunning;
        long generation;
    }

    static void bind(Activity activity, Tab selected, View content) {
        if (content == null) {
            throw new IllegalArgumentException("Tab content is required");
        }
        Binding binding = BINDINGS.get(activity);
        if (binding == null) {
            binding = new Binding();
            BINDINGS.put(activity, binding);
        }
        binding.selected = selected;
        binding.content = content;

        ImageButton player = activity.findViewById(R.id.nav_player);
        ImageButton library = activity.findViewById(R.id.nav_library);
        ImageButton search = activity.findViewById(R.id.nav_search);
        ImageButton settings = activity.findViewById(R.id.nav_settings);
        style(player, selected == Tab.PLAYER);
        style(library, selected == Tab.LIBRARY);
        style(search, selected == Tab.SEARCH);
        style(settings, selected == Tab.SETTINGS);
        player.setOnClickListener(view -> select(activity, view, Tab.PLAYER));
        library.setOnClickListener(view -> select(activity, view, Tab.LIBRARY));
        search.setOnClickListener(view -> select(activity, view, Tab.SEARCH));
        settings.setOnClickListener(view -> select(activity, view, Tab.SETTINGS));
        consumePendingEnter(activity, binding);
    }

    static void open(Activity activity, Tab tab) {
        request(activity, tab);
    }

    static void cancel(Activity activity) {
        Binding binding = BINDINGS.get(activity);
        if (binding == null) return;
        binding.generation++;
        binding.transitionRunning = false;
        if (binding.content != null) {
            binding.content.animate().setListener(null);
            binding.content.animate().cancel();
            binding.content.setTranslationX(0f);
        }
    }

    static void release(Activity activity) {
        cancel(activity);
        BINDINGS.remove(activity);
    }

    private static void select(Activity activity, View source, Tab tab) {
        Binding binding = BINDINGS.get(activity);
        if (binding != null
                && (binding.transitionRunning || binding.selected == tab)) {
            return;
        }
        source.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        request(activity, tab);
    }

    private static void request(Activity activity, Tab tab) {
        Binding binding = BINDINGS.get(activity);
        if (binding == null || binding.content == null) {
            navigateNow(activity, tab, inferredTab(activity));
            return;
        }
        TabTransitionPolicy.Direction direction = TabTransitionPolicy.direction(
                binding.selected.index,
                tab.index
        );
        if (direction == TabTransitionPolicy.Direction.NONE
                || binding.transitionRunning) {
            return;
        }
        binding.transitionRunning = true;
        long generation = ++binding.generation;
        View content = binding.content;
        content.animate().setListener(null);
        content.animate().cancel();
        content.animate()
                .translationX(TabTransitionPolicy.outgoingOffset(
                        direction,
                        transitionWidth(activity, content)
                ))
                .setDuration(EXIT_DURATION_MILLISECONDS)
                .setListener(new AnimatorListenerAdapter() {
                    private boolean cancelled;

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        cancelled = true;
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (cancelled || binding.generation != generation) return;
                        content.animate().setListener(null);
                        if (activity instanceof LibrarySearchActivity
                                && (tab == Tab.LIBRARY || tab == Tab.SEARCH)) {
                            // Replace the content while the old presentation is fully offscreen.
                            // Resetting it to zero before showTab() exposes one stale frame.
                            ((LibrarySearchActivity) activity).showTab(tab);
                            animateEnter(activity, binding, direction);
                        } else {
                            // Keep the old content offscreen until the destination window covers it.
                            // onStop() restores the retained Activity for a future REORDER_TO_FRONT.
                            navigateNow(activity, tab, binding.selected);
                        }
                    }
                });
    }

    @SuppressWarnings("deprecation")
    private static void navigateNow(Activity activity, Tab tab, Tab from) {
        Class<?> target;
        if (tab == Tab.PLAYER) target = MainActivity.class;
        else if (tab == Tab.SETTINGS) target = SettingsActivity.class;
        else target = LibrarySearchActivity.class;
        if (target.isInstance(activity)) return;
        Intent intent = new Intent(activity, target)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (from != null && from != tab) {
            intent.putExtra(EXTRA_TRANSITION_FROM, from.index);
            intent.putExtra(EXTRA_TRANSITION_TO, tab.index);
        }
        if (target == LibrarySearchActivity.class) {
            intent.putExtra(LibrarySearchActivity.EXTRA_TAB, tab.name());
        }
        activity.startActivity(intent);
        activity.overridePendingTransition(0, 0);
    }

    private static void consumePendingEnter(Activity activity, Binding binding) {
        Intent intent = activity.getIntent();
        if (intent == null || !intent.hasExtra(EXTRA_TRANSITION_FROM)
                || !intent.hasExtra(EXTRA_TRANSITION_TO)) {
            return;
        }
        int fromIndex = intent.getIntExtra(EXTRA_TRANSITION_FROM, binding.selected.index);
        int toIndex = intent.getIntExtra(EXTRA_TRANSITION_TO, binding.selected.index);
        intent.removeExtra(EXTRA_TRANSITION_FROM);
        intent.removeExtra(EXTRA_TRANSITION_TO);
        if (toIndex != binding.selected.index) return;
        TabTransitionPolicy.Direction direction =
                TabTransitionPolicy.direction(fromIndex, toIndex);
        if (direction != TabTransitionPolicy.Direction.NONE) {
            animateEnter(activity, binding, direction);
        }
    }

    private static void animateEnter(
            Activity activity,
            Binding binding,
            TabTransitionPolicy.Direction direction
    ) {
        View content = binding.content;
        if (content == null) return;
        binding.transitionRunning = true;
        long generation = ++binding.generation;
        content.animate().setListener(null);
        content.animate().cancel();
        content.setTranslationX(TabTransitionPolicy.incomingOffset(
                direction,
                transitionWidth(activity, content)
        ));
        content.post(() -> {
            if (binding.generation != generation || binding.content != content) return;
            content.animate()
                    .translationX(0f)
                    .setDuration(ENTER_DURATION_MILLISECONDS)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            if (binding.generation != generation) return;
                            content.animate().setListener(null);
                            content.setTranslationX(0f);
                            binding.transitionRunning = false;
                        }
                    });
        });
    }

    private static int transitionWidth(Activity activity, View content) {
        int width = content.getWidth();
        return width > 0 ? width : activity.getResources().getDisplayMetrics().widthPixels;
    }

    private static Tab inferredTab(Activity activity) {
        if (activity instanceof MainActivity) return Tab.PLAYER;
        if (activity instanceof SettingsActivity) return Tab.SETTINGS;
        return activity instanceof LibrarySearchActivity ? Tab.LIBRARY : null;
    }

    private static void style(ImageButton button, boolean selected) {
        button.setSelected(selected);
    }

    private BottomNavigation() { }
}
