package com.vault.passbook;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.CornerPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Random;

/**
 * The body of the shredder: brushed aluminium that is dark at both edges and bright in
 * between, so it looks like a rounded metal case. An embossed warning triangle sits in the
 * middle. It is the background of the delete sheet (the sheet is the shredder).
 */
public class MetalDrawable extends Drawable {

    private final float d;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path body = new Path();
    private final Path tri = new Path();
    private final float[] hair = new float[110];
    private int alpha = 255;
    private int triAlpha = 255; // the warning triangle fades in and out on its own
    private LinearGradient across;
    private LinearGradient down;

    private static final int[] COLORS = {
            0xFF68686C, 0xFFC2C2C6, 0xFFF7F7F8, 0xFFE7E7E9, 0xFFD6D6D9,
            0xFFE9E9EB, 0xFFD2D2D5, 0xFFE8E8EA, 0xFFF4F4F5, 0xFFA6A6AA, 0xFF636367};
    private static final float[] POS = {
            0f, 0.022f, 0.06f, 0.18f, 0.33f, 0.50f, 0.68f, 0.84f, 0.94f, 0.978f, 1f};

    public MetalDrawable(Context context) {
        d = context.getResources().getDisplayMetrics().density;
        Random r = new Random(11);
        for (int i = 0; i < hair.length; i++) hair[i] = r.nextFloat();
    }

    @Override
    protected void onBoundsChange(Rect b) {
        across = new LinearGradient(b.left, 0, b.right, 0, COLORS, POS, Shader.TileMode.CLAMP);
        // a soft light at the top, a darker bevel at the bottom
        down = new LinearGradient(0, b.top, 0, b.bottom,
                new int[]{0x66FFFFFF, 0x00FFFFFF, 0x00000000, 0x45000000},
                new float[]{0f, 0.2f, 0.84f, 1f}, Shader.TileMode.CLAMP);
        float r = 10 * d;
        body.reset();
        body.addRoundRect(new RectF(b), new float[]{r, r, r, r, 0, 0, 0, 0}, Path.Direction.CW);
    }

    @Override
    public void draw(@NonNull Canvas c) {
        if (alpha == 0 || across == null) return;
        Rect b = getBounds();
        int save = c.saveLayerAlpha(b.left, b.top, b.right, b.bottom, alpha);
        c.clipPath(body);

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(across);
        c.drawRect(b, paint);
        paint.setShader(down);
        c.drawRect(b, paint);
        paint.setShader(null);

        // brushed grain: faint vertical hairlines
        paint.setStrokeWidth(Math.max(1f, 0.7f * d));
        for (int i = 0; i < hair.length; i += 2) {
            float x = b.left + hair[i] * b.width();
            paint.setColor(hair[i + 1 < hair.length ? i + 1 : i] > 0.5f ? 0x14000000 : 0x1AFFFFFF);
            c.drawLine(x, b.top, x, b.bottom, paint);
        }

        // top highlight, bottom edge
        paint.setColor(0xCCFFFFFF);
        c.drawRect(b.left, b.top, b.right, b.top + d, paint);
        paint.setColor(0xFF5A5A5F);
        c.drawRect(b.left, b.bottom - 1.5f * d, b.right, b.bottom, paint);
        paint.setColor(0x66FFFFFF);
        c.drawRect(b.left, b.bottom - 2.5f * d, b.right, b.bottom - 1.5f * d, paint);

        if (triAlpha > 0) {
            int save2 = c.saveLayerAlpha(b.left, b.top, b.right, b.bottom, triAlpha);
            drawWarning(c, b);
            c.restoreToCount(save2);
        }
        c.restoreToCount(save);
    }

    // The warning triangle, pressed into the metal: a light edge below, a grey line above
    private void drawWarning(Canvas c, Rect b) {
        float cx = b.exactCenterX();
        float cy = b.top + b.height() * 0.46f;
        float side = 76 * d;
        float hgt = side * 0.866f;

        tri.reset();
        tri.moveTo(cx, cy - hgt * 0.55f);
        tri.lineTo(cx + side / 2f, cy + hgt * 0.45f);
        tri.lineTo(cx - side / 2f, cy + hgt * 0.45f);
        tri.close();

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setPathEffect(new CornerPathEffect(6 * d));

        for (int pass = 0; pass < 2; pass++) {
            float off = pass == 0 ? 1.4f * d : 0f;
            paint.setColor(pass == 0 ? 0xC0FFFFFF : 0xFFA2A2A7);
            paint.setStrokeWidth(4.2f * d);
            c.save();
            c.translate(0, off);
            c.drawPath(tri, paint);
            paint.setPathEffect(null);
            c.drawLine(cx, cy - hgt * 0.16f, cx, cy + hgt * 0.1f, paint);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy + hgt * 0.27f, 2.6f * d, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setPathEffect(new CornerPathEffect(6 * d));
            c.restore();
        }
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL);
    }

    /** 0 hides the embossed warning triangle, 255 shows it. */
    public void setTriangleAlpha(int a) {
        triAlpha = Math.max(0, Math.min(255, a));
        invalidateSelf();
    }

    @Override
    public void setAlpha(int a) {
        alpha = a;
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}