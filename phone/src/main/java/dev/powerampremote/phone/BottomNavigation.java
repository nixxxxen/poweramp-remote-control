package dev.powerampremote.phone;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Button;

/** Shared four-tab navigation; all tabs continue to use the one service-owned runtime. */
final class BottomNavigation {
    enum Tab { PLAYER, LIBRARY, SEARCH, SETTINGS }

    static void bind(Activity activity, Tab selected) {
        Button player = activity.findViewById(R.id.nav_player);
        Button library = activity.findViewById(R.id.nav_library);
        Button search = activity.findViewById(R.id.nav_search);
        Button settings = activity.findViewById(R.id.nav_settings);
        style(activity, player, selected == Tab.PLAYER);
        style(activity, library, selected == Tab.LIBRARY);
        style(activity, search, selected == Tab.SEARCH);
        style(activity, settings, selected == Tab.SETTINGS);
        player.setOnClickListener(view -> select(activity, view, Tab.PLAYER));
        library.setOnClickListener(view -> select(activity, view, Tab.LIBRARY));
        search.setOnClickListener(view -> select(activity, view, Tab.SEARCH));
        settings.setOnClickListener(view -> select(activity, view, Tab.SETTINGS));
    }

    private static void select(Activity activity, View source, Tab tab) {
        source.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (activity instanceof LibrarySearchActivity
                && (tab == Tab.LIBRARY || tab == Tab.SEARCH)) {
            ((LibrarySearchActivity) activity).showTab(tab);
            return;
        }
        Class<?> target;
        if (tab == Tab.PLAYER) target = MainActivity.class;
        else if (tab == Tab.SETTINGS) target = SettingsActivity.class;
        else target = LibrarySearchActivity.class;
        if (target.isInstance(activity)) return;
        Intent intent = new Intent(activity, target)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (target == LibrarySearchActivity.class) {
            intent.putExtra(LibrarySearchActivity.EXTRA_TAB, tab.name());
        }
        activity.startActivity(intent);
    }

    private static void style(Activity activity, Button button, boolean selected) {
        button.setSelected(selected);
        button.setTextColor(activity.getColor(selected ? R.color.accent : R.color.text_secondary));
        button.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private BottomNavigation() { }
}
