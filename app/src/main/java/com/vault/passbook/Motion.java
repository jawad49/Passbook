package com.vault.passbook;

import android.content.Context;
import android.provider.Settings;

/** Motion tokens: the spring and timing values the whole app uses live here. */
public final class Motion {

    private Motion() {
    }

    // Damping: how much a spring settles (1 = no bounce)
    public static final float DAMPING_SMOOTH = 1f;   // most movement
    public static final float DAMPING_SNAPPY = 0.9f; // a tiny settle: sheets, flicks
    public static final float DAMPING_BOUNCY = 0.8f; // playful: a new card dropping in

    // Stiffness: how fast it moves
    public static final float STIFF_ENTER = 300f;    // things arriving: calm
    public static final float STIFF_EXIT = 420f;     // things leaving: quicker
    public static final float STIFF_PRESS = 600f;    // touch feedback: instant

    // Lists
    public static final long STAGGER_MS = 45L;       // delay between neighbouring cards
    public static final int STAGGER_MAX = 7;         // only the first cards stagger

    private static boolean reduced = false;

    /** Android's "Remove animations" switch (animator scale 0) is the Reduce Motion setting. */
    public static void update(Context context) {
        reduced = Settings.Global.getFloat(context.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
    }

    public static boolean reduced() {
        return reduced;
    }
}