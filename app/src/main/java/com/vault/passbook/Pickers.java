package com.vault.passbook;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentDialog;
import androidx.activity.OnBackPressedCallback;
import androidx.core.view.WindowCompat;
import androidx.dynamicanimation.animation.DynamicAnimation;

import java.util.function.Consumer;

/**
 * The app's pickers, both in the One UI style (dark rounded cards, plain text, pill buttons):
 *  - sheet(): a centred dialog with a title, a message and pill buttons (Add the back side?).
 *    The name is kept from the old bottom sheet so MainActivity does not need to change.
 *  - popup(): a menu that grows out of the top right corner (the "+" button)
 * Both dim the screen, can be dismissed by tapping outside or with the back button, leave the
 * same way they came in, and run the chosen action once they are gone.
 */
public final class Pickers {

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final int RED = 0xFFFF453A;
    private static final int POPUP = 0xFF252525;     // the One UI card colour (popup + dialog)
    private static final int PRESSED = 0xFF4A4A4D;
    private static final int PILL = 0xFF3A3A3C;      // secondary button
    private static final int GREY = 0xFFB4B4B9;      // message text

    private Pickers() {
    }

    /** One choice in a dialog or menu. icon = drawable resource, or 0 for none. */
    public static final class Option {
        final String label;
        final int icon;
        final boolean destructive;
        final Runnable action;

        public Option(String label, int icon, boolean destructive, Runnable action) {
            this.label = label;
            this.icon = icon;
            this.destructive = destructive;
            this.action = action;
        }
    }

    // A dialog whose back button / back gesture runs our own closing animation
    private static class Panel extends ComponentDialog {
        private Runnable onBack;

        Panel(Activity a) {
            super(a, android.R.style.Theme_Translucent_NoTitleBar);
            getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    if (onBack != null) onBack.run();
                    else dismiss();
                }
            });
        }

        void setOnBack(Runnable r) {
            onBack = r;
        }
    }

    // ---------------------------------------------------------------- One UI dialog

    /**
     * A centred One UI style dialog. With ONE option the two buttons sit side by side
     * (cancel on the left, the option on the right in white); with more options they are
     * stacked and cancel comes last.
     */
    public static void sheet(final Activity act, String title, String message,
                             String cancelLabel, Option... options) {
        final FrameLayout root = new FrameLayout(act);
        root.setLayoutParams(new ViewGroup.LayoutParams(MATCH, MATCH));

        final View scrim = new View(act);
        scrim.setBackgroundColor(Color.BLACK);
        scrim.setAlpha(0f);
        root.addView(scrim, new FrameLayout.LayoutParams(MATCH, MATCH));

        final LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(POPUP);
        bg.setCornerRadius(dp(act, 28));
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setElevation(dp(act, 14));
        card.setClickable(true); // taps inside the dialog must not reach the scrim
        card.setPadding(dp(act, 20), dp(act, 26), dp(act, 20), dp(act, 20));

        int screenW = act.getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(screenW - dp(act, 48), dp(act, 360));
        root.addView(card, new FrameLayout.LayoutParams(width, WRAP, Gravity.CENTER));

        final boolean[] closing = {false};
        final Panel panel = newPanel(act, root);
        final Consumer<Runnable> close = next -> {
            if (closing[0]) return;
            closing[0] = true;
            Springs.to(scrim, DynamicAnimation.ALPHA, 0f, 1f, 900f);
            Springs.to(card, DynamicAnimation.SCALE_X, 0.92f, 1f, 1000f);
            Springs.to(card, DynamicAnimation.SCALE_Y, 0.92f, 1f, 1000f);
            Springs.to(card, DynamicAnimation.ALPHA, 0f, 1f, 1000f)
                    .addEndListener((anim, canceled, value, velocity) -> {
                        dismiss(panel);
                        if (next != null) next.run();
                    });
        };
        panel.setOnBack(() -> close.accept(null));
        scrim.setOnClickListener(view -> close.accept(null));

        // title + message, centred
        if (title != null) {
            TextView t = text(act, title, 22, Color.WHITE, true);
            card.addView(t, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        if (message != null) {
            TextView m = text(act, message, 16, GREY, false);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(MATCH, WRAP);
            mp.topMargin = dp(act, 10);
            card.addView(m, mp);
        }

        // buttons
        final boolean sideBySide = options.length == 1;
        LinearLayout buttons = new LinearLayout(act);
        buttons.setOrientation(sideBySide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(MATCH, WRAP);
        bp.topMargin = dp(act, 24);
        card.addView(buttons, bp);

        TextView cancel = pill(act, cancelLabel, Color.WHITE, PILL, PRESSED);
        cancel.setOnClickListener(view -> close.accept(null));

        if (sideBySide) {
            buttons.addView(cancel, pillParams(act, true, false));
        }
        for (int i = 0; i < options.length; i++) {
            final Option o = options[i];
            TextView b = o.destructive
                    ? pill(act, o.label, Color.WHITE, RED, 0xFFD93A30)
                    : pill(act, o.label, Color.BLACK, 0xFFF2F2F7, 0xFFCFCFD4);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setOnClickListener(view -> close.accept(o.action));
            buttons.addView(b, pillParams(act, sideBySide, !sideBySide && i > 0));
        }
        if (!sideBySide) {
            buttons.addView(cancel, pillParams(act, false, true));
        }

        // Pops in from the centre the moment it is laid out (no flash)
        onFirstDraw(root, () -> {
            card.setPivotX(card.getWidth() / 2f);
            card.setPivotY(card.getHeight() / 2f);
            card.setScaleX(0.88f);
            card.setScaleY(0.88f);
            card.setAlpha(0f);
            Springs.to(card, DynamicAnimation.SCALE_X, 1f, 0.85f, 600f);
            Springs.to(card, DynamicAnimation.SCALE_Y, 1f, 0.85f, 600f);
            Springs.to(card, DynamicAnimation.ALPHA, 1f, 1f, 700f);
            Springs.to(scrim, DynamicAnimation.ALPHA, 0.4f, 1f, 600f);
        });
        panel.show();
    }

    // ---------------------------------------------------------------- One UI popup menu

    /**
     * A One UI style popup: a dark rounded card with plain text rows. It opens in the top
     * right corner over the button that asked for it, growing out of that corner, and
     * shrinks back into it when it closes.
     */
    public static void popup(final Activity act, final View anchor, Option... options) {
        final FrameLayout root = new FrameLayout(act);
        root.setLayoutParams(new ViewGroup.LayoutParams(MATCH, MATCH));

        final View scrim = new View(act);
        scrim.setBackgroundColor(Color.BLACK);
        scrim.setAlpha(0f);
        root.addView(scrim, new FrameLayout.LayoutParams(MATCH, MATCH));

        final LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(POPUP);
        bg.setCornerRadius(dp(act, 26));
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setElevation(dp(act, 14));
        card.setMinimumWidth(dp(act, 184));
        card.setClickable(true); // taps inside the popup must not reach the scrim
        root.addView(card, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.START));

        final boolean[] closing = {false};
        final Panel panel = newPanel(act, root);
        final Consumer<Runnable> close = next -> {
            if (closing[0]) return;
            closing[0] = true;
            Springs.to(scrim, DynamicAnimation.ALPHA, 0f, 1f, 900f);
            Springs.to(card, DynamicAnimation.SCALE_X, 0.6f, 1f, 1000f);
            Springs.to(card, DynamicAnimation.SCALE_Y, 0.6f, 1f, 1000f);
            Springs.to(card, DynamicAnimation.ALPHA, 0f, 1f, 1000f)
                    .addEndListener((anim, canceled, value, velocity) -> {
                        dismiss(panel);
                        if (next != null) next.run();
                    });
        };
        panel.setOnBack(() -> close.accept(null));
        scrim.setOnClickListener(view -> close.accept(null));

        for (final Option o : options) {
            TextView row = new TextView(act);
            row.setText(o.label);
            row.setTextColor(o.destructive ? RED : Color.WHITE);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            row.setPadding(dp(act, 27), 0, dp(act, 27), 0);
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackground(pressable(0x00000000, PRESSED, 0));
            row.setOnClickListener(view -> close.accept(o.action));
            card.addView(row, new LinearLayout.LayoutParams(MATCH, dp(act, 54)));
        }

        // Grows out of the top right corner, right where the button is
        onFirstDraw(root, () -> {
            int[] anchorLoc = new int[2];
            int[] rootLoc = new int[2];
            anchor.getLocationOnScreen(anchorLoc);
            root.getLocationOnScreen(rootLoc);
            float w = card.getWidth();
            float x = root.getWidth() - dp(act, 11) - w;
            float y = Math.max(dp(act, 24), anchorLoc[1] - rootLoc[1] - dp(act, 2));
            card.setTranslationX(x);
            card.setTranslationY(y);
            card.setPivotX(w - dp(act, 22));
            card.setPivotY(dp(act, 22));
            card.setScaleX(0.55f);
            card.setScaleY(0.55f);
            card.setAlpha(0f);
            Springs.to(card, DynamicAnimation.SCALE_X, 1f, 1f, 800f);
            Springs.to(card, DynamicAnimation.SCALE_Y, 1f, 1f, 800f);
            Springs.to(card, DynamicAnimation.ALPHA, 1f, 1f, 800f);
            Springs.to(scrim, DynamicAnimation.ALPHA, 0.2f, 1f, 600f);
        });
        panel.show();
    }

    // ---------------------------------------------------------------- building blocks

    private static int dp(Activity act, float v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    private static Panel newPanel(Activity act, FrameLayout root) {
        Panel p = new Panel(act);
        p.setContentView(root);
        Window w = p.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setLayout(MATCH, MATCH);
            WindowCompat.setDecorFitsSystemWindows(w, false); // draw under the system bars
        }
        return p;
    }

    private static void dismiss(Dialog d) {
        try {
            d.dismiss();
        } catch (Exception ignored) {
            // the screen is already gone
        }
    }

    // Runs once, right after the first layout and just before the first frame is drawn
    private static void onFirstDraw(final View v, final Runnable r) {
        v.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                v.getViewTreeObserver().removeOnPreDrawListener(this);
                r.run();
                return true;
            }
        });
    }

    // Centred text for the dialog's title and message
    private static TextView text(Activity act, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    // A pill shaped button that lights up the instant the finger touches it
    private static TextView pill(Activity act, String label, int textColor, int fill, int pressed) {
        TextView t = new TextView(act);
        t.setText(label);
        t.setTextColor(textColor);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setFocusable(true);
        t.setBackground(pressable(fill, pressed, dp(act, 26)));
        return t;
    }

    // Layout of one pill: equal share of a row, or full width in a column
    private static LinearLayout.LayoutParams pillParams(Activity act, boolean row, boolean gapAbove) {
        LinearLayout.LayoutParams p = row
                ? new LinearLayout.LayoutParams(0, dp(act, 52), 1f)
                : new LinearLayout.LayoutParams(MATCH, dp(act, 52));
        if (row) {
            p.leftMargin = dp(act, 5);
            p.rightMargin = dp(act, 5);
        } else if (gapAbove) {
            p.topMargin = dp(act, 10);
        }
        return p;
    }

    // Lights up the instant the finger touches it
    private static StateListDrawable pressable(int normal, int pressed, float radiusPx) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable p = new GradientDrawable();
        p.setColor(pressed);
        p.setCornerRadius(radiusPx);
        GradientDrawable n = new GradientDrawable();
        n.setColor(normal);
        n.setCornerRadius(radiusPx);
        s.addState(new int[]{android.R.attr.state_pressed}, p);
        s.addState(new int[]{}, n);
        return s;
    }
}