package dev.powerampremote.phone;

import android.view.View;
import android.view.Window;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowCompat;

/** Applies system-bar and display-cutout padding once on a root View. */
final class SafeDrawingInsets {
    private SafeDrawingInsets() { }

    static void enableEdgeToEdge(Window window) {
        WindowCompat.setDecorFitsSystemWindows(window, false);
    }

    static void apply(View root) {
        int baseLeft = root.getPaddingLeft();
        int baseTop = root.getPaddingTop();
        int baseRight = root.getPaddingRight();
        int baseBottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets safeDrawing = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout()
            );
            view.setPadding(
                    baseLeft + safeDrawing.left,
                    baseTop + safeDrawing.top,
                    baseRight + safeDrawing.right,
                    baseBottom + safeDrawing.bottom
            );
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
