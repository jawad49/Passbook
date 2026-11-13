package com.vault.passbook;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;

import java.util.Random;

/** Builds a denim (jeans) texture in code, so no image file is needed. */
public final class Denim {

    private Denim() {
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    public static Drawable create(Resources res) {
        final int size = 256;
        Random rnd = new Random(7);

        // Slightly uneven threads, like real fabric
        float[] rowShade = new float[size];
        float[] colShade = new float[size];
        for (int i = 0; i < size; i++) {
            rowShade[i] = (rnd.nextFloat() - 0.5f) * 10f;
            colShade[i] = (rnd.nextFloat() - 0.5f) * 10f;
        }

        int[] px = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                // Diagonal twill ridges. Period 8 divides 256, so the tile repeats seamlessly.
                int t = ((x - y) % 8 + 8) % 8;
                float ridge = t < 3 ? 14f : (t < 4 ? 4f : -8f);
                float shade = ridge + rowShade[y] + colShade[x] + (rnd.nextFloat() - 0.5f) * 18f;
                if (rnd.nextFloat() < 0.035f) shade += 28f; // light flecks of thread

                int r = clamp(44 + shade * 0.8f);
                int g = clamp(68 + shade * 0.95f);
                int b = clamp(102 + shade * 1.2f);
                px[y * size + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        Bitmap bmp = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        BitmapDrawable tile = new BitmapDrawable(res, bmp);
        tile.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);

        // Soft light at the top, darker towards the bottom
        GradientDrawable shade = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x18FFFFFF, 0x00000000, 0x88000000});

        return new LayerDrawable(new Drawable[]{tile, shade});
    }
}
