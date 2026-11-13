package com.vault.passbook;

import android.graphics.Canvas;
import android.widget.EdgeEffect;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.RecyclerView;

/** iOS-style rubber-band bounce at the top and bottom of the list. */
public class BounceEdgeEffectFactory extends RecyclerView.EdgeEffectFactory {

    @NonNull
    @Override
    protected EdgeEffect createEdgeEffect(@NonNull RecyclerView recyclerView, int direction) {
        return new EdgeEffect(recyclerView.getContext()) {

            private final SpringAnimation anim =
                    new SpringAnimation(recyclerView, DynamicAnimation.TRANSLATION_Y)
                            .setSpring(new SpringForce()
                                    .setFinalPosition(0f)
                                    .setDampingRatio(0.8f)
                                    .setStiffness(200f));

            private float sign() {
                return direction == DIRECTION_BOTTOM ? -1f : 1f;
            }

            private void pull(float deltaDistance) {
                anim.cancel();
                float delta = recyclerView.getHeight() * deltaDistance * sign() * 0.35f;
                recyclerView.setTranslationY(recyclerView.getTranslationY() + delta);
            }

            @Override
            public void onPull(float deltaDistance) {
                pull(deltaDistance);
            }

            @Override
            public void onPull(float deltaDistance, float displacement) {
                pull(deltaDistance);
            }

            // Used by Android 12+ instead of onPull
            public float onPullDistance(float deltaDistance, float displacement) {
                pull(deltaDistance);
                return deltaDistance;
            }

            @Override
            public void onRelease() {
                if (recyclerView.getTranslationY() != 0f) anim.start();
            }

            @Override
            public void onAbsorb(int velocity) {
                anim.setStartVelocity(sign() * velocity * 0.3f);
                anim.start();
            }

            @Override
            public boolean draw(Canvas canvas) {
                return false;
            }

            @Override
            public boolean isFinished() {
                return !anim.isRunning();
            }
        };
    }
}