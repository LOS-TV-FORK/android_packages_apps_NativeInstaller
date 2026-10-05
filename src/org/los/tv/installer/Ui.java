package org.los.tv.installer;

import android.app.Activity;
import android.view.ViewGroup;
import android.widget.Button;

/** Shared row layout params + high-contrast focus effect. */
final class Ui {
    private Ui() {}

    static ViewGroup.LayoutParams rowParams(Activity ctx) {
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = (int) (8 * ctx.getResources().getDisplayMetrics().density);
        lp.setMargins(0, m, 0, m);
        return lp;
    }

    static Button styledPrimary(Activity ctx, int textId) {
        Button b =
                new Button(
                        ctx, null, 0, R.style.SudGlifButton_Primary);
        b.setText(textId);
        b.setFocusable(true);
        b.setFocusableInTouchMode(false);
        focusFx(b);
        return b;
    }

    /** Strong focus contrast on top of the Sud style. */
    static void focusFx(android.view.View v) {
        applyFocus(v, v.isFocused());
        v.setOnFocusChangeListener(
                (view, hasFocus) -> applyFocus(view, hasFocus));
    }

    private static void applyFocus(android.view.View v, boolean focused) {
        v.setAlpha(focused ? 1.0f : 0.55f);
        v.setScaleX(focused ? 1.04f : 1.0f);
        v.setScaleY(focused ? 1.04f : 1.0f);
    }
}
