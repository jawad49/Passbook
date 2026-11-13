package com.vault.passbook;

import android.view.View;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * One reusable spring per (view, property).
 * Re-targeting a running spring keeps its current value AND velocity,
 * so every animation can be interrupted and reversed smoothly.
 */
public final class Springs {

    public static final float STIFFNESS = Motion.STIFF_ENTER; // about 0.36s response

    private static final Map<View, Map<DynamicAnimation.ViewProperty, SpringAnimation>> ALL =
            new WeakHashMap<>();

    private Springs() {
    }

    private static SpringAnimation get(View v, DynamicAnimation.ViewProperty p) {
        Map<DynamicAnimation.ViewProperty, SpringAnimation> m = ALL.get(v);
        if (m == null) {
            m = new HashMap<>();
            ALL.put(v, m);
        }
        SpringAnimation a = m.get(p);
        if (a == null) {
            a = new SpringAnimation(v, p);
            a.setSpring(new SpringForce());
            m.put(p, a);
        }
        return a;
    }

    public static SpringAnimation to(View v, DynamicAnimation.ViewProperty p,
                                     float target, float damping, float stiffness) {
        return toWithVelocity(v, p, target, damping, stiffness, Float.NaN);
    }

    public static SpringAnimation toWithVelocity(View v, DynamicAnimation.ViewProperty p,
                                                 float target, float damping, float stiffness,
                                                 float velocity) {
        SpringAnimation a = get(v, p);
        if (Motion.reduced()) {
            // Reduce Motion: no bounce, and it arrives almost at once (callbacks still run)
            damping = 1f;
            stiffness = Math.max(stiffness, 2500f);
        }
        a.getSpring().setDampingRatio(damping).setStiffness(stiffness);
        if (!Float.isNaN(velocity) && !a.isRunning()) {
            a.setStartVelocity(velocity);
        }
        a.animateToFinalPosition(target);
        return a;
    }

    public static void cancel(View v, DynamicAnimation.ViewProperty p) {
        Map<DynamicAnimation.ViewProperty, SpringAnimation> m = ALL.get(v);
        if (m == null) return;
        SpringAnimation a = m.get(p);
        if (a != null && a.isRunning()) a.cancel();
    }

    public static void jump(View v, DynamicAnimation.ViewProperty p, float value) {
        cancel(v, p);
        p.setValue(v, value);
    }
}