package com.vault.passbook;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

/** Dark overlay with a card-shaped window (ratio 1.586:1, horizontal or vertical) and corner marks. */
public class ScanFrameView extends View {

    private static final float CARD_RATIO = 1.586f;

    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path dimPath = new Path();
    private final Path cornerPath = new Path();
    private final RectF frame = new RectF();          // what is drawn right now (animated)
    private final RectF landscapeRect = new RectF();  // where the frame ends up in each mode
    private final RectF portraitRect = new RectF();
    private final float density;
    private final float radius;
    private final float cornerLen;
    private boolean portrait = false;
    private ValueAnimator anim;

    public ScanFrameView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 16 * density;
        cornerLen = 32 * density;

        dimPaint.setStyle(Paint.Style.FILL);
        dimPaint.setColor(0xAA000000);

        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(1.5f * density);
        outlinePaint.setColor(0x99FFFFFF);

        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeWidth(4 * density);
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);
        cornerPaint.setColor(0xFFFFFFFF);
    }

    // The window the photo will be cut from, in this view's own coordinates
    public RectF getFrameRect() {
        return portrait ? portraitRect : landscapeRect;
    }

    public boolean isPortrait() {
        return portrait;
    }

    // Switches between a horizontal and a vertical card frame (the frame glides over)
    public void setPortrait(boolean value) {
        if (value == portrait) return;
        portrait = value;
        if (getWidth() == 0) return; // not laid out yet: onSizeChanged picks the right one

        final RectF from = new RectF(frame);
        final RectF to = portrait ? portraitRect : landscapeRect;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(300);
        anim.setInterpolator(new DecelerateInterpolator(2f));
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            RectF r = new RectF(
                    from.left + (to.left - from.left) * t,
                    from.top + (to.top - from.top) * t,
                    from.right + (to.right - from.right) * t,
                    from.bottom + (to.bottom - from.bottom) * t);
            applyFrame(r);
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float margin = 24 * density;

        // horizontal card: full width, a bit above the middle
        float fw = w - 2 * margin;
        float fh = fw / CARD_RATIO;
        float cy = h * 0.46f;
        landscapeRect.set(margin, cy - fh / 2f, margin + fw, cy + fh / 2f);

        // vertical card: as tall as fits between the hint text and the buttons
        float top = 56 * density;
        float bottom = 124 * density;
        float availH = h - top - bottom;
        float ph = Math.min(fw * CARD_RATIO, availH);
        float pw = ph / CARD_RATIO;
        float pcy = top + availH / 2f;
        portraitRect.set(w / 2f - pw / 2f, pcy - ph / 2f, w / 2f + pw / 2f, pcy + ph / 2f);

        if (anim != null) anim.cancel();
        applyFrame(portrait ? portraitRect : landscapeRect);
    }

    // Rebuilds the dark overlay and the corner marks for the given window
    private void applyFrame(RectF r) {
        frame.set(r);
        int w = getWidth();
        int h = getHeight();

        dimPath.reset();
        dimPath.setFillType(Path.FillType.EVEN_ODD);
        dimPath.addRect(0, 0, w, h, Path.Direction.CW);
        dimPath.addRoundRect(frame, radius, radius, Path.Direction.CW);

        float l = frame.left, t = frame.top, rt = frame.right, b = frame.bottom;
        float rr = radius;
        float L = cornerLen;
        cornerPath.reset();
        // top-left
        cornerPath.moveTo(l, t + L);
        cornerPath.lineTo(l, t + rr);
        cornerPath.arcTo(l, t, l + 2 * rr, t + 2 * rr, 180, 90, false);
        cornerPath.lineTo(l + L, t);
        // top-right
        cornerPath.moveTo(rt - L, t);
        cornerPath.lineTo(rt - rr, t);
        cornerPath.arcTo(rt - 2 * rr, t, rt, t + 2 * rr, 270, 90, false);
        cornerPath.lineTo(rt, t + L);
        // bottom-right
        cornerPath.moveTo(rt, b - L);
        cornerPath.lineTo(rt, b - rr);
        cornerPath.arcTo(rt - 2 * rr, b - 2 * rr, rt, b, 0, 90, false);
        cornerPath.lineTo(rt - L, b);
        // bottom-left
        cornerPath.moveTo(l + L, b);
        cornerPath.lineTo(l + rr, b);
        cornerPath.arcTo(l, b - 2 * rr, l + 2 * rr, b, 90, 90, false);
        cornerPath.lineTo(l, b - L);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawPath(dimPath, dimPaint);
        canvas.drawRoundRect(frame, radius, radius, outlinePaint);
        canvas.drawPath(cornerPath, cornerPaint);
    }
}