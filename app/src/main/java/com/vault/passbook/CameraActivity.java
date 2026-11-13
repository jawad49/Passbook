package com.vault.passbook;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.Size;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.dynamicanimation.animation.DynamicAnimation;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Live camera with a card-shaped frame. Returns the cropped photo's file path. */
public class CameraActivity extends AppCompatActivity {

    public static final String EXTRA_SIDE = "side"; // "front" or "back"
    public static final String EXTRA_PATH = "path"; // result: path of the cropped JPEG
    public static final String EXTRA_PORTRAIT = "portrait"; // start with a vertical frame

    private PreviewView previewView;
    private ScanFrameView scanFrame;
    private ImageCapture imageCapture;
    private Camera camera;
    private ImageView flashButton, frameButton;
    private boolean torchOn = false;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private boolean busy = false;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "Camera permission is needed to scan", Toast.LENGTH_LONG).show();
                    setResult(RESULT_CANCELED);
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        View root = findViewById(R.id.cameraRoot);
        previewView = findViewById(R.id.previewView);
        scanFrame = findViewById(R.id.scanFrame);
        TextView hint = findViewById(R.id.hintText);
        View shutter = findViewById(R.id.shutterButton);
        flashButton = findViewById(R.id.flashButton);
        frameButton = findViewById(R.id.frameButton);

        String side = getIntent().getStringExtra(EXTRA_SIDE);
        hint.setText("back".equals(side)
                ? "Fit the BACK of the card inside the frame"
                : "Fit the FRONT of the card inside the frame");

        WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightStatusBars(false);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        findViewById(R.id.cancelButton).setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        // Shutter shrinks the moment it is pressed
        shutter.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                Springs.to(v, DynamicAnimation.SCALE_X, 0.88f, 1f, 600f);
                Springs.to(v, DynamicAnimation.SCALE_Y, 0.88f, 1f, 600f);
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                Springs.to(v, DynamicAnimation.SCALE_X, 1f, 1f, 600f);
                Springs.to(v, DynamicAnimation.SCALE_Y, 1f, 1f, 600f);
            }
            return false;
        });
        shutter.setOnClickListener(v -> capture());

        // Frame button: horizontal card <-> vertical card
        boolean startPortrait = getIntent().getBooleanExtra(EXTRA_PORTRAIT, false);
        scanFrame.setPortrait(startPortrait);
        frameButton.setRotation(startPortrait ? 90f : 0f);
        pressFeedback(frameButton);
        frameButton.setOnClickListener(v -> {
            boolean p = !scanFrame.isPortrait();
            scanFrame.setPortrait(p);
            Springs.to(frameButton, DynamicAnimation.ROTATION, p ? 90f : 0f, 0.85f, 400f);
            frameButton.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        });

        // Flash button (a torch that stays on while you line up the card).
        // It only shows up once the camera is running and we know the phone has a flash.
        pressFeedback(flashButton);
        flashButton.setOnClickListener(v -> {
            if (camera == null) return;
            torchOn = !torchOn;
            camera.getCameraControl().enableTorch(torchOn);
            updateFlashButton();
            flashButton.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                // Preview and photo both use 4:3 so what you see is what you get
                ResolutionSelector previewSelector = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .build();
                Preview preview = new Preview.Builder()
                        .setResolutionSelector(previewSelector)
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ResolutionSelector captureSelector = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(new ResolutionStrategy(new Size(2560, 1920),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build();
                imageCapture = new ImageCapture.Builder()
                        .setResolutionSelector(captureSelector)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build();

                provider.unbindAll();
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, imageCapture);

                boolean hasFlash = camera.getCameraInfo().hasFlashUnit();
                flashButton.setVisibility(hasFlash ? View.VISIBLE : View.GONE);
                torchOn = false;
                updateFlashButton();
            } catch (Exception e) {
                Toast.makeText(this, "Could not start the camera", Toast.LENGTH_LONG).show();
                setResult(RESULT_CANCELED);
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // Yellow with a black bolt when the light is on, dark glass with a white bolt when off
    private void updateFlashButton() {
        flashButton.setSelected(torchOn);
        flashButton.setColorFilter(torchOn ? 0xFF000000 : 0xFFFFFFFF);
        flashButton.setContentDescription(torchOn ? "Turn flash off" : "Turn flash on");
    }

    // Buttons shrink the moment they are touched
    private void pressFeedback(View v) {
        v.setOnTouchListener((view, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                Springs.to(view, DynamicAnimation.SCALE_X, 0.88f, 1f, 600f);
                Springs.to(view, DynamicAnimation.SCALE_Y, 0.88f, 1f, 600f);
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                Springs.to(view, DynamicAnimation.SCALE_X, 1f, 1f, 600f);
                Springs.to(view, DynamicAnimation.SCALE_Y, 1f, 1f, 600f);
            }
            return false;
        });
    }

    private void capture() {
        if (imageCapture == null || busy) return;
        busy = true;
        previewView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);

        // Read the frame position now, on the main thread
        final RectF frame = new RectF(scanFrame.getFrameRect());
        final float viewW = previewView.getWidth();
        final float viewH = previewView.getHeight();

        imageCapture.takePicture(ioExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                Bitmap full = null;
                try {
                    full = image.toBitmap();
                    int rotation = image.getImageInfo().getRotationDegrees();
                    File out = cropAndSave(full, rotation, frame, viewW, viewH);
                    runOnUiThread(() -> {
                        Intent data = new Intent().putExtra(EXTRA_PATH, out.getAbsolutePath());
                        setResult(RESULT_OK, data);
                        finish();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        busy = false;
                        Toast.makeText(CameraActivity.this, "Could not take the photo",
                                Toast.LENGTH_SHORT).show();
                    });
                } finally {
                    image.close();
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                runOnUiThread(() -> {
                    busy = false;
                    Toast.makeText(CameraActivity.this, "Could not take the photo",
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    // Turns the frame on screen into the matching rectangle on the photo, and saves it
    private File cropAndSave(Bitmap full, int rotation, RectF frame,
                             float viewW, float viewH) throws Exception {
        Bitmap upright = full;
        if (rotation != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            upright = Bitmap.createBitmap(full, 0, 0, full.getWidth(), full.getHeight(), m, true);
        }

        // The preview fills the screen and is centered, so scale + offset map screen -> photo
        float s = Math.max(viewW / upright.getWidth(), viewH / upright.getHeight());
        float offX = (viewW - upright.getWidth() * s) / 2f;
        float offY = (viewH - upright.getHeight() * s) / 2f;

        int x = Math.round((frame.left - offX) / s);
        int y = Math.round((frame.top - offY) / s);
        int w = Math.round(frame.width() / s);
        int h = Math.round(frame.height() / s);
        x = Math.max(0, Math.min(x, upright.getWidth() - 1));
        y = Math.max(0, Math.min(y, upright.getHeight() - 1));
        w = Math.max(1, Math.min(w, upright.getWidth() - x));
        h = Math.max(1, Math.min(h, upright.getHeight() - y));

        Bitmap card = Bitmap.createBitmap(upright, x, y, w, h);
        File out = new File(getCacheDir(), "scan_" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            card.compress(Bitmap.CompressFormat.JPEG, 95, fos);
        }
        return out;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ioExecutor.shutdown();
    }
}