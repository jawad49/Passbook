package com.vault.passbook;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Random;

/**
 * The comb of the shredder: a row of small silver hooks that hang down from the bottom edge
 * of the metal body (the top edge of this view), with a soft shadow under the body. The hooks
 * line up with the cuts the shredder will make: call configure() with where the first cut is
 * and the distance between cuts.
 */
public class TeethView extends View {

    private static final int N = 64;

    private final float d;
    private float x0;
    private float pitch;
    private final float[] len = new float[N];
    private final float[] tilt = new float[N];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path hook = new Path();
    private LinearGradient shadow;

    public TeethView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        d = context.getResources().getDisplayMetrics().density;
        x0 = 0f;
        pitch = 20 * d;
        Random r = new Random(5);
        for (int i = 0; i < N; i++) {
            len[i] = 11f + r.nextFloat() * 8f;       // hooks are not all the same length
            tilt[i] = (r.nextFloat() - 0.5f) * 4.5f; // and not perfectly straight
        }
    }

    /** x0: where the first cut is (in this view's coordinates); pitch: distance between cuts. */
    public void configure(float x0, float pitch) {
        this.x0 = x0;
        this.pitch = Math.max(pitch, 4 * d);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        shadow = new LinearGradient(0, 0, 0, 16 * d,
                0x70000000, 0x00000000, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();

        // the shadow the body throws below itself
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(shadow);
        c.drawRect(0, 0, w, 16 * d, paint);
        paint.setShader(null);

        int first = (int) Math.floor((0 - x0) / pitch);
        int last = (int) Math.ceil((w - x0) / pitch);
        for (int k = first; k <= last; k++) {
            float x = x0 + k * pitch;
            int i = ((k % N) + N) % N;
            float l = len[i] * d;
            float t = tilt[i] * d;

            hook.reset();
            hook.moveTo(x - 2f * d, 0f);
            hook.quadTo(x - 1.4f * d + t * 0.4f, l * 0.5f, x + t - 0.6f * d, l);
            hook.lineTo(x + t + 0.6f * d, l);
            hook.quadTo(x + 1.4f * d + t * 0.4f, l * 0.5f, x + 2f * d, 0f);
            hook.close();

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFDCDCDF);
            c.drawPath(hook, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.9f * d);
            paint.setColor(0xCC3A3A3F);
            c.drawPath(hook, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }
}