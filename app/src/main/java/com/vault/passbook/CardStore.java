package com.vault.passbook;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Cards live in the app's private folder.
 * Front photo:  <id>.jpg      Back photo (optional):  <id>_back.jpg
 */
public class CardStore {

    private static final int MAX_SIDE = 1800;
    private static final String BACK_SUFFIX = "_back.jpg";

    public static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "cards");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    // Fronts only, oldest first, so the newest card ends up on top of the stack
    public static List<File> list(Context ctx) {
        File[] files = dir(ctx).listFiles(
                (d, name) -> name.endsWith(".jpg") && !name.endsWith(BACK_SUFFIX));
        List<File> result = new ArrayList<>();
        if (files != null) {
            Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            Collections.addAll(result, files);
        }
        // The order saved by swiping a card to the front wins. Cards that are not in it yet
        // are new, so they stay at the end (= in front), oldest first.
        final List<String> saved = readOrder(ctx);
        if (!saved.isEmpty()) {
            Collections.sort(result, (a, b) -> Integer.compare(rank(saved, a), rank(saved, b)));
        }
        return result;
    }

    private static final String ORDER_FILE = "order.txt";

    private static int rank(List<String> order, File f) {
        int i = order.indexOf(f.getName());
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    private static List<String> readOrder(Context ctx) {
        List<String> names = new ArrayList<>();
        File file = new File(dir(ctx), ORDER_FILE);
        if (!file.exists()) return names;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty()) names.add(line);
            }
        } catch (IOException ignored) {
            // no saved order: the cards stay in the order they were added
        }
        return names;
    }

    private static void writeOrder(Context ctx, List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) sb.append(n).append('\n');
        try (FileOutputStream out = new FileOutputStream(new File(dir(ctx), ORDER_FILE))) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // the order is only lost if the phone has no space left
        }
    }

    // Puts a card in front of all the others (the last card in the list is the front of the stack)
    public static void bringToFront(Context ctx, File front) {
        List<String> names = new ArrayList<>();
        for (File f : list(ctx)) {
            if (!f.getName().equals(front.getName())) names.add(f.getName());
        }
        names.add(front.getName());
        writeOrder(ctx, names);
    }

    // Returns the back photo, or null if this card has none
    public static File backOf(File front) {
        File back = new File(front.getParentFile(), front.getName().replace(".jpg", BACK_SUFFIX));
        return back.exists() ? back : null;
    }

    public static void delete(File front) {
        File back = backOf(front);
        if (back != null) back.delete();
        front.delete();
    }

    public static File importFront(Context ctx, Uri uri) throws IOException {
        File out = new File(dir(ctx), System.currentTimeMillis() + ".jpg");
        saveScaled(ctx, uri, out);
        return out;
    }

    public static File importBack(Context ctx, File front, Uri uri) throws IOException {
        File out = new File(front.getParentFile(), front.getName().replace(".jpg", BACK_SUFFIX));
        saveScaled(ctx, uri, out);
        return out;
    }

    private static void saveScaled(Context ctx, Uri uri, File out) throws IOException {
        // 1. Read the size only
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }

        // 2. Decode (downsampled if the photo is huge)
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bmp == null) throw new IOException("Could not read image");

        // 3. Fix rotation
        int rotation = 0;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            ExifInterface exif = new ExifInterface(in);
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
            else if (o == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
            else if (o == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
        } catch (Exception ignored) {
        }

        // 4. Scale down to MAX_SIDE and apply rotation
        float scale = Math.min(1f, (float) MAX_SIDE / Math.max(bmp.getWidth(), bmp.getHeight()));
        Matrix m = new Matrix();
        m.postScale(scale, scale);
        if (rotation != 0) m.postRotate(rotation);
        Bitmap fixed = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);

        // 5. Save into the app's private storage
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fixed.compress(Bitmap.CompressFormat.JPEG, 95, fos);
        }
    }

    // width / height of a saved photo: below 1 means a vertical card
    public static float ratioOf(File file) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return 1.586f;
        return (float) o.outWidth / o.outHeight;
    }

    // Decode a saved photo for display at roughly the given width
    public static Bitmap load(File file, int reqWidth) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);

        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= reqWidth) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }
}