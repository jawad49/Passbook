package com.vault.passbook;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewPropertyAnimator;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.shape.ShapeAppearanceModel;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class MainActivity extends AppCompatActivity {

    private static final float S = Springs.STIFFNESS;
    private static final float S_EXIT = Motion.STIFF_EXIT; // things leaving are quicker

    // Views: stack screen
    private CardAdapter adapter;
    private RecyclerView recycler;
    private TextView emptyText;
    private View stackHost, dim;
    private FrameLayout overlay;

    // Views: opened card
    private FrameLayout cardHolder;      // moves + scales: card and bar together
    private MaterialCardView flipCard;   // turns around (rotationY)
    private ImageView frontImage, backImage, infoButton, trashButton;
    private FrameLayout backFace;
    private TextView noBackText, barTitle, doneButton;
    private View backBar;
    private SpringAnimation flipSpring;
    private OnBackPressedCallback backCallback;

    // Views: delete sheet (the shredder)
    private View sheet, sheetContent, sheetTrash;
    private TeethView sheetTeeth;      // the comb of hooks under the metal body
    private MetalDrawable sheetMetal;  // the brushed-metal body of the shredder

    // State
    private float density;
    private int insetTop, insetBottom;
    private boolean isOpen = false;
    private boolean hasBack = false;
    private boolean dismissArmed = false;
    private boolean showingBack = false;  // which face is turned towards the user
    private boolean sheetOpen = false;
    private boolean sheetBusy = false;    // true while the card is being shredded
    private Runnable afterFlip = null;    // runs once the card is face up (Delete was confirmed)
    private boolean sheetFlipping = false; // the shredder turns together with the card
    private File currentFront;   // card shown in the overlay
    private File pendingFront;   // card waiting for a back photo
    private View openedCardView; // list item hidden while the overlay shows it
    private float originX, originY, restX, restY;
    // App lock. "unlocked" is static so turning the phone does not ask again.
    private static boolean unlocked = false;
    private View lockCover;
    private BiometricPrompt biometricPrompt;
    private boolean authenticating = false;
    private boolean askedThisVisit = false;
    private boolean ownScreen = false; // true while our own camera / photo picker is on screen

    private View addButton; // the round + (the popup grows out of it)
    private float restScale = 1.04f; // size of the opened card (smaller for a tall card on a short screen)

    // A vertical card is taller than its slot in the list. The part below the slot is
    // revealed (clip edge slides down) while the card lifts out.
    private int cardW, slotH, fullH;
    private final FloatValueHolder revealValue = new FloatValueHolder(0f);
    private SpringAnimation revealSpring;

    // The bar is the top part of the card holder: it sits flush on top of the card
    private int barH;
    private float barBaseY = 0f;

    // Touch state for the opened card
    private float downX, downY, startAngle, startTransY;
    private int mode = 0; // 0 = undecided, 1 = flipping, 2 = dismissing
    private VelocityTracker tracker;
    private int touchSlop;

    private final ActivityResultLauncher<PickVisualMediaRequest> frontPicker =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    frontViaCamera = false;
                    addFront(uri);
                }
            });

    private final ActivityResultLauncher<PickVisualMediaRequest> backPicker =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) addBack(uri);
            });

    private boolean scanningBack = false;
    private boolean frontViaCamera = false; // how the front was added: the back uses the same way

    private final ActivityResultLauncher<Intent> scanLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                String path = result.getData().getStringExtra(CameraActivity.EXTRA_PATH);
                if (path == null) return;
                Uri uri = Uri.fromFile(new File(path));
                if (scanningBack) {
                    addBack(uri);
                } else {
                    frontViaCamera = true;
                    addFront(uri);
                }
            });

    private final DynamicAnimation.OnAnimationEndListener closeEnd =
            (anim, canceled, value, velocity) -> {
                if (!canceled && !isOpen) finishClose();
            };

    private final DynamicAnimation.OnAnimationEndListener sheetHideEnd =
            (anim, canceled, value, velocity) -> {
                if (!canceled && !sheetOpen && !sheetBusy) sheet.setVisibility(View.INVISIBLE);
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        density = getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        barH = dp(40); // same as the bar height in activity_main.xml

        View root = findViewById(R.id.main);
        root.setBackgroundColor(Color.BLACK); // iOS 18 Wallet: plain black
        stackHost = findViewById(R.id.stackHost);
        recycler = findViewById(R.id.recycler);
        emptyText = findViewById(R.id.emptyText);
        overlay = findViewById(R.id.overlay);
        dim = findViewById(R.id.dim);
        cardHolder = findViewById(R.id.cardHolder);
        flipCard = findViewById(R.id.flipCard);
        frontImage = findViewById(R.id.frontImage);
        backFace = findViewById(R.id.backFace);
        backImage = findViewById(R.id.backImage);
        noBackText = findViewById(R.id.noBackText);
        infoButton = findViewById(R.id.infoButton);
        backBar = findViewById(R.id.backBar);
        trashButton = findViewById(R.id.trashButton);
        barTitle = findViewById(R.id.barTitle);
        doneButton = findViewById(R.id.doneButton);
        sheet = findViewById(R.id.sheet);
        sheetContent = findViewById(R.id.sheetContent);
        sheetTrash = findViewById(R.id.sheetTrash);
        // The sheet is the real shredder from the start: brushed metal with the Delete and
        // Cancel buttons on it, and a comb of hooks hanging under it. The warning triangle
        // only appears when Delete is pressed.
        sheetTeeth = findViewById(R.id.teeth);
        sheetMetal = new MetalDrawable(this);
        sheetMetal.setTriangleAlpha(0);
        ((View) sheetContent.getParent()).setBackground(sheetMetal);

        // Light status bar icons on our dark background
        WindowInsetsControllerCompat wic = WindowCompat.getInsetsController(getWindow(), root);
        wic.setAppearanceLightStatusBars(false);
        wic.setAppearanceLightNavigationBars(false);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            insetTop = bars.top;
            insetBottom = bars.bottom;
            stackHost.setPadding(bars.left, bars.top, bars.right, 0);
            recycler.setPadding(0, dp(8), 0, dp(80) + bars.bottom);
            sheet.setPadding(0, 0, 0, bars.bottom);
            return insets;
        });

        // Card list (every card shown whole, like the Wallet app) with iOS-style bounce
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setEdgeEffectFactory(new BounceEdgeEffectFactory());
        adapter = new CardAdapter(this::openCard);
        recycler.setAdapter(adapter);

        // Flip spring: slightly bouncy, follows the finger's velocity
        flipCard.setCameraDistance(12000f * density);
        sheet.setCameraDistance(12000f * density); // the shredder turns with the card
        backFace.setRotationY(180f); // so the back is not mirrored once the card is turned

        // The back rounds its own bottom corners. The card's shape stops clipping its children
        // once the top corners are made square (where the bar attaches), which left the back
        // with square corners. The outline reaches 16dp ABOVE the view, so only the bottom
        // corners are rounded and the top stays square, flush with the bar.
        backFace.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                int r = dp(16);
                outline.setRoundRect(0, -r, view.getWidth(), view.getHeight(), r);
            }
        });
        backFace.setClipToOutline(true);

        // The back is framed all the way round, like the iOS 6 back: the bar's colour runs down
        // both sides and along the bottom, and the photo sits inside it like a panel. Its bottom
        // corners are rounded to match (16dp outer corner - 3dp frame = 13dp); the top stays
        // square, flush with the bar.
        backFace.setPadding(dp(3), 0, dp(3), dp(3));
        backImage.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                int r = dp(13);
                outline.setRoundRect(0, -r, view.getWidth(), view.getHeight(), r);
            }
        });
        backImage.setClipToOutline(true);

        // The card and the bar above it are cut to exactly the same rounded outline, so they
        // always have the same width (the turning card was a hair wider than the flat bar)
        cardHolder.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(16));
            }
        });
        cardHolder.setClipToOutline(true);
        flipSpring = new SpringAnimation(flipCard, DynamicAnimation.ROTATION_Y);
        flipSpring.setSpring(new SpringForce().setDampingRatio(0.85f).setStiffness(220f));
        flipSpring.addUpdateListener((a, value, velocity) -> {
            applyFaces(value);
            // Delete was confirmed: the shredder turns together with the card. It starts facing
            // us with the question, and ends facing us as the shredder (warning triangle); the
            // faces swap at 90 degrees, at the same moment as the card's.
            if (sheetFlipping) {
                float turn = Math.max(0f, Math.min(180f, value));
                sheet.setRotationY(180f - turn);
                boolean shredderFace = turn < 90f;
                sheetContent.setAlpha(shredderFace ? 0f : 1f);
                sheetMetal.setTriangleAlpha(shredderFace ? 255 : 0);
            }
            // Delete was confirmed: the moment the card is face up, the shredding starts
            if (afterFlip != null && value < 2f) {
                Runnable r = afterFlip;
                afterFlip = null;
                flipCard.post(r); // not from inside the spring's own callback
            }
        });

        // Buttons
        addButton = findViewById(R.id.addButton);
        pressFeedback(addButton);
        addButton.setOnClickListener(v -> chooseSource());

        pressFeedback(infoButton);
        infoButton.setOnClickListener(v -> {
            if (!isOpen || sheetOpen) return;
            flipSpring.animateToFinalPosition(180f);
            flipCard.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        });

        pressFeedback(doneButton);
        doneButton.setOnClickListener(v -> {
            if (!isOpen || sheetOpen || !showingBack) return;
            flipSpring.animateToFinalPosition(0f);
            flipCard.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        });

        pressFeedback(trashButton);
        trashButton.setOnClickListener(v -> {
            if (!isOpen || sheetOpen || !showingBack) return;
            showSheet();
        });

        sheet.findViewById(R.id.sheetDelete).setOnClickListener(v -> {
            if (sheetOpen && !sheetBusy) confirmDelete();
        });
        sheet.findViewById(R.id.sheetCancel).setOnClickListener(v -> {
            if (sheetOpen && !sheetBusy) hideSheet();
        });

        dim.setOnClickListener(v -> {
            if (sheetOpen) {
                if (!sheetBusy) hideSheet();
            } else {
                closeCard(Float.NaN);
            }
        });
        flipCard.setOnTouchListener(this::onCardTouch);

        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (sheetOpen) {
                    if (!sheetBusy) hideSheet();
                } else {
                    closeCard(Float.NaN);
                }
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);

        // App lock
        buildLockCover((FrameLayout) root);
        biometricPrompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result) {
                        authenticating = false;
                        unlock();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        authenticating = false; // stay locked, the Unlock button asks again
                    }
                });
    }

    // ---------------------------------------------------------------- app lock

    // A black screen on top of everything; nothing underneath can be seen or tapped
    private void buildLockCover(FrameLayout root) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.BLACK);
        box.setClickable(true);

        TextView title = new TextView(this);
        title.setText("Passbook");
        title.setTextColor(Color.WHITE);
        title.setTextSize(32);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setGravity(Gravity.CENTER); // the default width fills the screen, so centre the text itself
        box.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView button = new TextView(this);
        button.setText("Unlock");
        button.setTextColor(Color.BLACK);
        button.setTextSize(17);
        button.setTypeface(button.getTypeface(), Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(32), dp(12), dp(32), dp(12));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Color.WHITE);
        pill.setCornerRadius(dp(24));
        button.setBackground(pill);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(28);
        box.addView(button, blp);
        pressFeedback(button);
        button.setOnClickListener(v -> authenticate());

        root.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        lockCover = box;
        lockCover.setVisibility(unlocked ? View.GONE : View.VISIBLE);
    }

    // The phone's fingerprint; from Android 11 the PIN / pattern also works as a backup
    private int allowedAuthenticators() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return BiometricManager.Authenticators.BIOMETRIC_WEAK
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        }
        return BiometricManager.Authenticators.BIOMETRIC_WEAK;
    }

    private void authenticate() {
        if (authenticating || unlocked) return;
        int allowed = allowedAuthenticators();
        int status = BiometricManager.from(this).canAuthenticate(allowed);
        if (status == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
                || status == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE) {
            // nothing to unlock with on this phone, so there is nothing we can ask for
            Toast.makeText(this, "Set up a fingerprint or screen lock to protect your cards",
                    Toast.LENGTH_LONG).show();
            unlock();
            return;
        }
        if (status != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, "Fingerprint is not available right now", Toast.LENGTH_SHORT).show();
            return;
        }
        BiometricPrompt.PromptInfo.Builder info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Passbook")
                .setSubtitle("Unlock to see your cards")
                .setAllowedAuthenticators(allowed);
        if ((allowed & BiometricManager.Authenticators.DEVICE_CREDENTIAL) == 0) {
            info.setNegativeButtonText("Cancel"); // required when there is no PIN backup
        }
        authenticating = true;
        biometricPrompt.authenticate(info.build());
    }

    private void unlock() {
        unlocked = true;
        lockCover.animate().cancel();
        lockCover.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            lockCover.setVisibility(View.GONE);
            lockCover.setAlpha(1f);
        }).start();
        adapter.replayReveal();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Coming back from our own camera / photo picker does not lock the app
        if (ownScreen) ownScreen = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Hide the cards right away, so they are not visible in the recent-apps screen
        if (!ownScreen && !authenticating && !isChangingConfigurations()) {
            lockCover.animate().cancel();
            lockCover.setAlpha(1f);
            lockCover.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!ownScreen && !isChangingConfigurations()) {
            unlocked = false;
            askedThisVisit = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Motion.update(this);
        applyMotionSettings();
        if (unlocked) {
            lockCover.animate().cancel();
            lockCover.setAlpha(1f);
            lockCover.setVisibility(View.GONE);
        } else if (!askedThisVisit && !authenticating) {
            askedThisVisit = true;
            authenticate();
        }
        if (!isOpen && overlay.getVisibility() != View.VISIBLE) refresh();
    }

    // Reduce Motion (Remove animations): the card turns without bounce and almost at once
    private void applyMotionSettings() {
        boolean r = Motion.reduced();
        flipSpring.getSpring().setDampingRatio(r ? 1f : 0.85f).setStiffness(r ? 2500f : 220f);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    // Buttons shrink the moment the finger touches them
    private void pressFeedback(View v) {
        v.setOnTouchListener((view, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                Springs.to(view, DynamicAnimation.SCALE_X, 0.88f, 1f, Motion.STIFF_PRESS);
                Springs.to(view, DynamicAnimation.SCALE_Y, 0.88f, 1f, Motion.STIFF_PRESS);
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                Springs.to(view, DynamicAnimation.SCALE_X, 1f, 1f, Motion.STIFF_PRESS);
                Springs.to(view, DynamicAnimation.SCALE_Y, 1f, 1f, Motion.STIFF_PRESS);
            }
            return false;
        });
    }

    private void launchPicker(ActivityResultLauncher<PickVisualMediaRequest> picker) {
        ownScreen = true;
        picker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    // The "+" popup: camera scanner or gallery, for the FRONT of a new card
    private void chooseSource() {
        Pickers.popup(this, addButton,
                new Pickers.Option("Scan with camera", 0, false, () -> launchScanner(false)),
                new Pickers.Option("Choose from gallery", 0, false, () -> launchPicker(frontPicker)));
    }

    // The same two choices for the BACK of an opened card, in the centred dialog style
    private void chooseBackSource() {
        Pickers.sheet(this, "Add the back side",
                "How do you want to add it?", "Cancel",
                new Pickers.Option("Scan with camera", 0, false, () -> launchScanner(true)),
                new Pickers.Option("Choose from gallery", 0, false, () -> launchPicker(backPicker)));
    }

    // Opens our camera scanner for the front or the back of a card
    private void launchScanner(boolean back) {
        scanningBack = back;
        Intent i = new Intent(this, CameraActivity.class);
        i.putExtra(CameraActivity.EXTRA_SIDE, back ? "back" : "front");
        // the back of a vertical card is scanned vertically too
        i.putExtra(CameraActivity.EXTRA_PORTRAIT,
                back && pendingFront != null && CardStore.ratioOf(pendingFront) < 1f);
        ownScreen = true;
        scanLauncher.launch(i);
    }

    // Scanned photos are temporary: remove them once imported
    private void cleanup(Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            new File(uri.getPath()).delete();
        }
    }

    private void refresh() {
        List<File> cards = CardStore.list(this);
        adapter.setFiles(cards, unlocked);
        emptyText.setVisibility(cards.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- adding photos

    private void addFront(Uri uri) {
        new Thread(() -> {
            try {
                File f = CardStore.importFront(this, uri);
                cleanup(uri);
                runOnUiThread(() -> {
                    pendingFront = f;
                    refresh();
                    askForBack();
                });
            } catch (Exception e) {
                cleanup(uri);
                runOnUiThread(() ->
                        Toast.makeText(this, "Could not add photo", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void askForBack() {
        Pickers.sheet(this, "Add the back side?",
                "You can also add it later from the back of the opened card.", "Skip",
                new Pickers.Option("Add back", 0, false, () -> {
                    // the back is added the same way the front was
                    if (frontViaCamera) launchScanner(true);
                    else launchPicker(backPicker);
                }));
    }

    private void addBack(Uri uri) {
        final File target = pendingFront;
        if (target == null) return;
        new Thread(() -> {
            try {
                CardStore.importBack(this, target, uri);
                cleanup(uri);
                runOnUiThread(() -> {
                    if (isOpen && target.equals(currentFront)) {
                        loadBack();
                        flipSpring.animateToFinalPosition(180f); // show the new back
                    } else if (!isOpen) {
                        refresh();
                    }
                });
            } catch (Exception e) {
                cleanup(uri);
                runOnUiThread(() ->
                        Toast.makeText(this, "Could not add photo", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ---------------------------------------------------------------- card colours

    // The main colour of the card, used to tint the bar above it
    private float[] cardHsv(Bitmap bmp) {
        Bitmap small = Bitmap.createScaledBitmap(bmp, 24, 15, true);
        double r = 0, g = 0, b = 0, total = 0;
        float[] t = new float[3];
        for (int y = 0; y < small.getHeight(); y++) {
            for (int x = 0; x < small.getWidth(); x++) {
                int p = small.getPixel(x, y);
                Color.colorToHSV(p, t);
                double w = 0.05 + t[1] * t[2]; // coloured areas count more than white/grey
                r += Color.red(p) * w;
                g += Color.green(p) * w;
                b += Color.blue(p) * w;
                total += w;
            }
        }
        if (small != bmp) small.recycle();
        float[] hsv = new float[3];
        Color.colorToHSV(Color.rgb((int) (r / total), (int) (g / total), (int) (b / total)), hsv);
        hsv[2] = Math.max(0.38f, Math.min(0.78f, hsv[2])); // dark enough for white text
        return hsv;
    }

    private int shade(float[] hsv, float vMul) {
        float[] c = {hsv[0], hsv[1], Math.max(0f, Math.min(1f, hsv[2] * vMul))};
        return Color.HSVToColor(c);
    }

    private GradientDrawable gradient(int top, int bottom, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1), stroke);
        return g;
    }

    // Paints the bar and its two buttons in the colour of the card
    private void tintBar(float[] hsv) {
        // The bar: same width as the card, no border of its own (a border made it look
        // framed and a little different from the card under it), just a thin line at the bottom
        GradientDrawable barBase = gradient(shade(hsv, 1.18f), shade(hsv, 0.82f), 0, 0x00000000);
        float r = dp(16);
        barBase.setCornerRadii(new float[]{r, r, r, r, 0f, 0f, 0f, 0f}); // flush on the card
        GradientDrawable barLine = new GradientDrawable();
        barLine.setColor(0x55000000);
        android.graphics.drawable.LayerDrawable barBg =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{barBase, barLine});
        barBg.setLayerInset(1, 0, barH - dp(1), 0, 0);
        backBar.setBackground(barBg);

        // The delete button: the card's colour with a subtle red in it, like iOS 6
        final int redLight = 0xFFD0453B;
        final int redDark = 0xFF8E1F1A;
        final float mix = 0.5f;
        StateListDrawable button = new StateListDrawable();
        button.addState(new int[]{android.R.attr.state_pressed},
                gradient(androidx.core.graphics.ColorUtils.blendARGB(shade(hsv, 0.45f), redDark, mix),
                        androidx.core.graphics.ColorUtils.blendARGB(shade(hsv, 0.75f), redDark, mix),
                        6, 0x66000000));
        button.addState(new int[]{},
                gradient(androidx.core.graphics.ColorUtils.blendARGB(shade(hsv, 0.92f), redLight, mix),
                        androidx.core.graphics.ColorUtils.blendARGB(shade(hsv, 0.55f), redDark, mix),
                        6, 0x66000000));
        trashButton.setBackground(button);

        StateListDrawable button2 = new StateListDrawable();
        button2.addState(new int[]{android.R.attr.state_pressed},
                gradient(shade(hsv, 0.45f), shade(hsv, 0.75f), 6, 0x66000000));
        button2.addState(new int[]{},
                gradient(shade(hsv, 0.92f), shade(hsv, 0.55f), 6, 0x66000000));
        doneButton.setBackground(button2);

        // The frame round the back: the bar's colour carried down the sides and along the bottom
        backFace.setBackground(gradient(shade(hsv, 0.82f), shade(hsv, 0.6f), 0, 0x00000000));
        // The panel inside the frame: plain card colour (what shows when there is no back photo)
        backImage.setBackground(gradient(shade(hsv, 1.1f), shade(hsv, 0.7f), 0, 0x00000000));
        // a thin dark line where the panel meets the frame
        GradientDrawable panelEdge = new GradientDrawable();
        panelEdge.setColor(0x00000000);
        panelEdge.setStroke(dp(1), 0x66000000);
        float pr = dp(13);
        panelEdge.setCornerRadii(new float[]{0f, 0f, 0f, 0f, pr, pr, pr, pr});
        backImage.setForeground(panelEdge);
    }

    // ---------------------------------------------------------------- open / close

    private void loadBack() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        File back = CardStore.backOf(currentFront);
        hasBack = back != null;
        backImage.setImageBitmap(hasBack ? CardStore.load(back, screenW) : null);
        backImage.setVisibility(View.VISIBLE); // without a photo it shows the plain panel
        noBackText.setVisibility(hasBack ? View.GONE : View.VISIBLE);
        barTitle.setText(hasBack ? "Back side" : "No back side");
    }

    private void openCard(File front, View cardView) {
        if (isOpen || overlay.getVisibility() == View.VISIBLE) return;
        isOpen = true;
        backCallback.setEnabled(true);
        currentFront = front;
        openedCardView = cardView;

        Springs.jump(cardView, DynamicAnimation.SCALE_X, 1f);
        Springs.jump(cardView, DynamicAnimation.SCALE_Y, 1f);

        int[] c = new int[2];
        int[] o = new int[2];
        cardView.getLocationOnScreen(c);
        overlay.getLocationOnScreen(o);
        originX = c[0] - o[0];
        originY = c[1] - o[1] - barH; // the holder starts one bar higher than the card
        int w = cardView.getWidth();
        int h = cardView.getHeight();

        // A vertical card keeps the width of its slot but is taller than the slot
        // (a vertical card that is in front is already shown whole in the list: nothing to unroll)
        float ratio = CardStore.ratioOf(front);
        boolean tall = ratio < 1f;
        int fullHeight = h;
        if (tall && Math.round(w / ratio) - h > dp(2)) fullHeight = Math.round(w / ratio);
        cardW = w;
        slotH = h;
        fullH = fullHeight;

        float areaTop = insetTop;
        float areaBottom = overlay.getHeight() - insetBottom;
        restX = (overlay.getWidth() - w) / 2f;
        if (tall) {
            // tall card: centre card + bar as one group, shrink it if the screen is too short
            float groupH = fullHeight + barH;
            float fitWidth = (overlay.getWidth() - dp(32)) / (float) w;
            restScale = Math.min(Math.max(1.04f, fitWidth),
                    (areaBottom - areaTop - dp(16)) / groupH);
            restY = areaTop + (areaBottom - areaTop - groupH) / 2f;
        } else {
            restScale = 1.04f;
            restY = areaTop + (areaBottom - areaTop - h) / 2f - barH; // the CARD is centred
        }

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) cardHolder.getLayoutParams();
        lp.width = w;
        lp.height = fullHeight + barH; // the bar lives inside the holder, so its buttons get touches
        lp.gravity = Gravity.TOP | Gravity.START;
        cardHolder.setLayoutParams(lp);
        cardHolder.post(cardHolder::invalidateOutline);
        FrameLayout.LayoutParams cp = (FrameLayout.LayoutParams) flipCard.getLayoutParams();
        cp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        cp.height = fullHeight;
        cp.gravity = Gravity.BOTTOM;
        flipCard.setLayoutParams(cp);

        int screenW = getResources().getDisplayMetrics().widthPixels;
        Bitmap frontBitmap = CardStore.load(front, screenW);
        frontImage.setImageBitmap(frontBitmap);
        tintBar(cardHsv(frontBitmap));
        loadBack();

        // Fresh state: front side up, bar hidden, sheet hidden
        showingBack = false;
        sheetOpen = false;
        sheetBusy = false;
        sheet.setVisibility(View.INVISIBLE);
        sheetContent.setAlpha(1f);
        sheetTrash.setAlpha(0f);
        Springs.jump(backBar, DynamicAnimation.ALPHA, 0f);
        Springs.jump(backBar, DynamicAnimation.TRANSLATION_Y, barBaseY + dp(8));
        setCardCorners(false);

        // Start exactly where the list card is
        sheetFlipping = false;
        sheet.setRotationY(0f);
        afterFlip = null;
        flipSpring.cancel();
        cardHolder.setVisibility(View.VISIBLE);
        flipCard.setVisibility(View.VISIBLE);
        flipCard.setRotationY(0f);
        applyFaces(0f);
        Springs.jump(cardHolder, DynamicAnimation.TRANSLATION_X, originX);
        Springs.jump(cardHolder, DynamicAnimation.TRANSLATION_Y, originY);
        Springs.jump(cardHolder, DynamicAnimation.SCALE_X, 1f);
        Springs.jump(cardHolder, DynamicAnimation.SCALE_Y, 1f);
        Springs.jump(flipCard, DynamicAnimation.TRANSLATION_Z, 0f);
        Springs.jump(dim, DynamicAnimation.ALPHA, 0f);

        overlay.setVisibility(View.VISIBLE);
        cardView.setVisibility(View.INVISIBLE);
        flipCard.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);

        // The card lifts out to the middle, everything else is pushed back
        Springs.to(cardHolder, DynamicAnimation.TRANSLATION_X, restX, 1f, S);
        Springs.to(cardHolder, DynamicAnimation.TRANSLATION_Y, restY, 1f, S);
        Springs.to(cardHolder, DynamicAnimation.SCALE_X, restScale, 1f, S);
        Springs.to(cardHolder, DynamicAnimation.SCALE_Y, restScale, 1f, S);
        if (fullHeight > h) {
            revealFrom(h, fullHeight); // the lower part of a vertical card unrolls
        } else {
            clearReveal();
        }
        Springs.to(flipCard, DynamicAnimation.TRANSLATION_Z, dp(20), 1f, S);
        Springs.to(dim, DynamicAnimation.ALPHA, 1f, 1f, S);
        Springs.to(stackHost, DynamicAnimation.SCALE_X, 0.94f, 1f, S);
        Springs.to(stackHost, DynamicAnimation.SCALE_Y, 0.94f, 1f, S);
        Springs.to(stackHost, DynamicAnimation.ALPHA, 0f, 1f, S);
    }

    // vy = release velocity in px/s when dragged away, or NaN when closed by a button
    private void closeCard(float vy) {
        if (!isOpen) return;
        isOpen = false;
        backCallback.setEnabled(false);

        flipSpring.animateToFinalPosition(0f); // turn back to the front on the way home
        if (fullH > slotH) revealTo(slotH); // a vertical card rolls back up into its slot
        Springs.to(cardHolder, DynamicAnimation.TRANSLATION_X, originX, 1f, S_EXIT);
        SpringAnimation move = Springs.toWithVelocity(
                cardHolder, DynamicAnimation.TRANSLATION_Y, originY, 1f, S_EXIT, vy);
        move.addEndListener(closeEnd);
        Springs.to(cardHolder, DynamicAnimation.SCALE_X, 1f, 1f, S_EXIT);
        Springs.to(cardHolder, DynamicAnimation.SCALE_Y, 1f, 1f, S_EXIT);
        Springs.to(flipCard, DynamicAnimation.TRANSLATION_Z, 0f, 1f, S_EXIT);
        Springs.to(dim, DynamicAnimation.ALPHA, 0f, 1f, S_EXIT);
        Springs.to(stackHost, DynamicAnimation.SCALE_X, 1f, 1f, S_EXIT);
        Springs.to(stackHost, DynamicAnimation.SCALE_Y, 1f, 1f, S_EXIT);
        Springs.to(stackHost, DynamicAnimation.ALPHA, 1f, 1f, S_EXIT);
    }

    // ---------------------------------------------------------------- vertical cards

    private void setReveal(float bottom) {
        flipCard.setClipBounds(new Rect(0, 0, cardW, Math.round(bottom)));
    }

    private SpringAnimation revealAnim() {
        if (revealSpring == null) {
            revealSpring = new SpringAnimation(revealValue);
            revealSpring.setSpring(new SpringForce(slotH).setDampingRatio(1f).setStiffness(S));
            revealSpring.addUpdateListener((anim, value, velocity) -> setReveal(value));
        }
        return revealSpring;
    }

    private void revealFrom(float from, float to) {
        SpringAnimation a = revealAnim();
        a.cancel();
        revealValue.setValue(from);
        setReveal(from);
        a.animateToFinalPosition(to);
    }

    private void revealTo(float to) {
        revealAnim().animateToFinalPosition(to);
    }

    private void clearReveal() {
        if (revealSpring != null) revealSpring.cancel();
        flipCard.setClipBounds(null);
    }

    private void finishClose() {
        clearReveal();
        sheetFlipping = false;
        sheet.setRotationY(0f);
        afterFlip = null;
        overlay.setVisibility(View.INVISIBLE);
        if (openedCardView != null) openedCardView.setVisibility(View.VISIBLE);
        openedCardView = null;
        currentFront = null;
        sheetOpen = false;
        sheetBusy = false;
        sheet.setVisibility(View.INVISIBLE);
        sheetContent.setAlpha(1f);
        sheetTrash.setAlpha(0f);
    }

    // ---------------------------------------------------------------- flipping + gestures

    // Shows the right face, and the bar / "i" button that belong to it
    private void applyFaces(float angle) {
        boolean back = angle > 90f;
        frontImage.setVisibility(back ? View.INVISIBLE : View.VISIBLE);
        infoButton.setVisibility(back ? View.INVISIBLE : View.VISIBLE);
        backFace.setVisibility(back ? View.VISIBLE : View.INVISIBLE);
        if (back != showingBack) {
            showingBack = back;
            setCardCorners(back); // square top corners where the bar is attached
            Springs.to(backBar, DynamicAnimation.ALPHA, back ? 1f : 0f, 1f, S);
            Springs.to(backBar, DynamicAnimation.TRANSLATION_Y,
                    back ? barBaseY : barBaseY + dp(8), 1f, S);
        }
    }

    // With the bar on top, the card's own top corners must be square
    private void setCardCorners(boolean squareTop) {
        ShapeAppearanceModel.Builder b = flipCard.getShapeAppearanceModel().toBuilder()
                .setAllCornerSizes(dp(16));
        if (squareTop) {
            b.setTopLeftCornerSize(0f);
            b.setTopRightCornerSize(0f);
        }
        flipCard.setShapeAppearanceModel(b.build());
    }

    // Apple's momentum projection: where the gesture would come to rest
    private float project(float velocity) {
        return (velocity / 1000f) * 0.998f / (1f - 0.998f);
    }

    // The further past the limit, the less the card follows
    private float rubber(float over, float dimension) {
        return (over * dimension * 0.55f) / (dimension + 0.55f * Math.abs(over));
    }

    private float constrainAngle(float raw) {
        if (raw < 0f) return -rubber(-raw, 45f);
        if (raw > 180f) return 180f + rubber(raw - 180f, 45f);
        return raw;
    }

    private boolean onCardTouch(View v, MotionEvent e) {
        if (!isOpen || sheetOpen) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tracker = VelocityTracker.obtain();
                tracker.addMovement(e);
                downX = e.getRawX();
                downY = e.getRawY();
                mode = 0;
                flipSpring.cancel(); // grab the card even if it is still turning
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (tracker == null) return true;
                tracker.addMovement(e);
                float dx = e.getRawX() - downX;
                float dy = e.getRawY() - downY;
                if (mode == 0) {
                    if (Math.hypot(dx, dy) < touchSlop) return true;
                    mode = Math.abs(dx) > Math.abs(dy) ? 1 : 2;
                    downX = e.getRawX(); // track 1:1 from here on
                    downY = e.getRawY();
                    dx = 0f;
                    dy = 0f;
                    startAngle = flipCard.getRotationY();
                    if (mode == 2) {
                        Springs.cancel(cardHolder, DynamicAnimation.TRANSLATION_Y);
                        startTransY = cardHolder.getTranslationY();
                    }
                }
                if (mode == 1) {
                    float angle = constrainAngle(startAngle + dx * 180f / flipCard.getWidth());
                    flipCard.setRotationY(angle);
                    applyFaces(angle);
                } else {
                    float offset = dy > 0 ? dy : -rubber(-dy, dp(120));
                    cardHolder.setTranslationY(startTransY + offset);
                    // a small tick when letting go would close the card
                    boolean armed = cardHolder.getTranslationY() - restY > dp(160);
                    if (armed != dismissArmed) {
                        dismissArmed = armed;
                        flipCard.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (tracker == null) return true;
                tracker.addMovement(e);
                tracker.computeCurrentVelocity(1000);
                float vx = tracker.getXVelocity();
                float vy = tracker.getYVelocity();
                tracker.recycle();
                tracker = null;
                dismissArmed = false;
                boolean cancelled = e.getActionMasked() == MotionEvent.ACTION_CANCEL;

                if (mode == 0) {
                    // Tap on an empty back: add the back photo
                    if (!cancelled && showingBack && !hasBack && currentFront != null) {
                        pendingFront = currentFront;
                        chooseBackSource();
                    }
                } else if (mode == 1) {
                    float angVel = vx * 180f / flipCard.getWidth(); // degrees per second
                    float projected = flipCard.getRotationY() + project(angVel);
                    float target = projected > 90f ? 180f : 0f;
                    flipSpring.setStartVelocity(angVel);
                    flipSpring.animateToFinalPosition(target);
                    flipCard.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                } else {
                    float projected = cardHolder.getTranslationY() + project(vy);
                    if (projected - restY > dp(160)) {
                        closeCard(vy);
                    } else {
                        Springs.toWithVelocity(cardHolder, DynamicAnimation.TRANSLATION_Y,
                                restY, 1f, S, vy);
                    }
                }
                mode = 0;
                return true;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- delete sheet + shredder

    private float sheetHiddenY() {
        return Math.max(sheet.getHeight(), dp(300)) + dp(8);
    }

    private void showSheet() {
        sheetOpen = true;
        sheetFlipping = false;
        sheet.setRotationY(0f);
        // hooks line up with the cuts the shredder will make in this card
        int cw = flipCard.getWidth();
        float cs = cardHolder.getScaleX();
        int pieceW = (int) Math.ceil(cw / 14f);
        sheetTeeth.configure(cardHolder.getTranslationX() + cw * (1f - cs) / 2f, pieceW * cs);
        sheetContent.setAlpha(1f);
        sheetTrash.setAlpha(0f);
        sheet.setVisibility(View.VISIBLE);
        Springs.jump(sheet, DynamicAnimation.TRANSLATION_Y, sheetHiddenY());
        Springs.to(sheet, DynamicAnimation.TRANSLATION_Y, 0f, 1f, S);
        flipCard.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void hideSheet() {
        sheetOpen = false;
        Springs.to(sheet, DynamicAnimation.TRANSLATION_Y, sheetHiddenY(), 1f, S)
                .addEndListener(sheetHideEnd);
    }

    // Delete was pressed on the sheet: the card first turns to its FRONT, then it is shredded
    private void confirmDelete() {
        if (!isOpen || currentFront == null) return;
        sheetBusy = true; // nothing else can be tapped while this runs
        flipCard.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        if (flipCard.getRotationY() < 2f) {
            shredAndDelete(); // already face up
            return;
        }
        sheetFlipping = true;
        afterFlip = this::shredAndDelete; // started by the flip spring's update listener
        flipSpring.animateToFinalPosition(0f);
    }

    private void shredAndDelete() {
        if (!isOpen || currentFront == null) return;
        final File target = currentFront;
        isOpen = false;
        sheetBusy = true;
        backCallback.setEnabled(false);
        flipSpring.cancel();
        flipCard.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);

        // 1. The sheet already is the shredder. If it turned with the card it is already
        //    showing the shredder face (it stays turned, the metal is symmetric); otherwise
        //    the buttons fade and the warning triangle appears
        if (sheetFlipping) {
            sheetFlipping = false;
            sheet.setRotationY(180f);
            sheetContent.setAlpha(0f);
            sheetMetal.setTriangleAlpha(255);
        } else {
            sheetContent.animate().alpha(0f).setDuration(150).start();
            ValueAnimator morph = ValueAnimator.ofFloat(0f, 1f);
            morph.setDuration(300);
            morph.addUpdateListener(m -> sheetMetal.setTriangleAlpha(
                    Math.round(255 * (float) m.getAnimatedValue())));
            morph.start();
        }
        Springs.to(backBar, DynamicAnimation.ALPHA, 0f, 1f, S);

        // 2. Take a picture of the whole card (with the bar, if the back is showing)
        int w = flipCard.getWidth();
        int h = flipCard.getHeight();
        int full = h + barH;
        View face = frontImage.getVisibility() == View.VISIBLE ? frontImage : backFace;
        Bitmap snap = Bitmap.createBitmap(w, full, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(snap);
        Path clip = new Path();
        clip.addRoundRect(new RectF(0, showingBack ? 0 : barH, w, full),
                dp(16), dp(16), Path.Direction.CW);
        canvas.clipPath(clip);
        canvas.save();
        canvas.translate(0, barH);
        face.draw(canvas);
        canvas.restore();
        if (showingBack) backBar.draw(canvas);

        // 3. A layer that holds the strips. It has the same size and position as the
        //    card holder and sits under the sheet, so the strips disappear into the
        //    shredder when they reach it.
        FrameLayout shred = new FrameLayout(this);
        shred.setClipChildren(false);
        FrameLayout.LayoutParams slp =
                new FrameLayout.LayoutParams(w, full, Gravity.TOP | Gravity.START);
        overlay.addView(shred, overlay.indexOfChild(cardHolder) + 1, slp);
        float scale = cardHolder.getScaleX();
        shred.setTranslationX(cardHolder.getTranslationX());
        shred.setTranslationY(cardHolder.getTranslationY());
        shred.setScaleX(scale);
        shred.setScaleY(scale);
        cardHolder.setVisibility(View.INVISIBLE);

        float cardTopOnScreen = shred.getTranslationY() + full * (1f - scale) / 2f;

        // 4. When the last strip has fallen: delete the files, bring the stack back
        final boolean[] done = {false};
        final ValueAnimator[] riseRef = new ValueAnimator[1];
        Runnable finish = () -> {
            done[0] = true;
            if (riseRef[0] != null) riseRef[0].cancel();
            CardStore.delete(target);
            overlay.removeView(shred);
            refresh();
            sheet.setTranslationX(0f);
            sheet.setRotationY(0f);
            sheetMetal.setTriangleAlpha(0);
            // the shredder has already left through the top of the screen: park it out of sight
            Springs.jump(sheet, DynamicAnimation.TRANSLATION_Y, sheetHiddenY());
            sheet.setVisibility(View.INVISIBLE);
            Springs.to(stackHost, DynamicAnimation.SCALE_X, 1f, 1f, S);
            Springs.to(stackHost, DynamicAnimation.SCALE_Y, 1f, 1f, S);
            Springs.to(stackHost, DynamicAnimation.ALPHA, 1f, 1f, S);
            Springs.to(dim, DynamicAnimation.ALPHA, 0f, 1f, S).addEndListener(closeEnd);
        };

        // 5. The card never moves. The part still above the shredder ("intact") stays one
        //    whole, uncut card. The part the shredder has already passed ("tails") is cut
        //    into strips, and shows up below the shredder, exactly where it was on the card.
        final int n = 14;
        final int stripW = (int) Math.ceil(w / (float) n);
        final int count = (int) Math.ceil(w / (float) stripW);
        final ImageView intactView = new ImageView(this);
        intactView.setImageBitmap(snap);
        shred.addView(intactView, new FrameLayout.LayoutParams(w, full, Gravity.TOP | Gravity.START));
        final ImageView[] tails = new ImageView[count];
        final int[] widths = new int[count];
        for (int i = 0; i < count; i++) {
            int x = i * stripW;
            int sw = Math.min(stripW, w - x);
            widths[i] = sw;
            Bitmap piece = Bitmap.createBitmap(snap, x, 0, sw, full);

            tails[i] = new ImageView(this);
            tails[i].setImageBitmap(piece);
            FrameLayout.LayoutParams lp =
                    new FrameLayout.LayoutParams(sw, full, Gravity.TOP | Gravity.START);
            lp.leftMargin = x;
            shred.addView(tails[i], lp);
            tails[i].setScaleX(0.86f); // the cuts: thin gaps between the strips
            tails[i].setClipBounds(new Rect(0, full, sw, full)); // nothing shredded yet
        }

        final boolean[] fell = {false};

        // 6. Called on every frame while the shredder rises.
        //    Above the shredder's top edge: the whole card. Below its bottom edge: strips.
        //    In between the shredder itself covers the card.
        final float sheetTop0 = overlay.getHeight() - sheet.getHeight();
        // the metal body ends above the comb of hooks; the strips come out under the body
        final float bodyBottom0 = overlay.getHeight() - insetBottom - sheetTeeth.getHeight();
        final float[] bottomLocal = {full};
        Runnable tick = () -> {
            float ty = sheet.getTranslationY();
            float sheetTopScreen = sheetTop0 + ty;
            float sheetBottomScreen = bodyBottom0 + ty;
            float topLocal = Math.max(0f, Math.min(full, (sheetTopScreen - cardTopOnScreen) / scale));
            float botLocal = Math.max(0f, Math.min(full, (sheetBottomScreen - cardTopOnScreen) / scale));
            bottomLocal[0] = botLocal;
            intactView.setClipBounds(new Rect(0, 0, w, (int) topLocal));
            if (fell[0]) return; // the strips are falling by themselves now

            // A card is hard plastic: the strips do not bend, curl or drift. Each one is just
            // a straight piece that shows from the hooks downwards.
            int top = (int) botLocal;
            for (int i = 0; i < count; i++) {
                tails[i].setClipBounds(new Rect(0, top, widths[i], full));
            }
        };

        // 7. Once the shredder has passed the whole card, every strip falls
        Runnable dropStrips = () -> {
            float fallDist = (overlay.getHeight() - cardTopOnScreen) / scale + full;
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < count; i++) order.add(i);
            Collections.shuffle(order);
            Random rnd = new Random();
            for (int i = 0; i < count; i++) {
                ViewPropertyAnimator a = tails[i].animate()
                        .translationYBy(fallDist)
                        .translationXBy((rnd.nextFloat() - 0.5f) * dp(4))
                        .rotation((rnd.nextFloat() - 0.5f) * 5f)
                        .setStartDelay(order.get(i) * 22L)
                        .setDuration(520)
                        .setInterpolator(new AccelerateInterpolator(1.6f));
                if (order.get(i) == count - 1) a.withEndAction(finish);
                a.start();
            }
        };

        // 8. Only the shredder moves, and it works like a real one: it does not glide.
        //    It creeps up to the card, then pulls it in one bite at a time. Each bite is a
        //    quick tug, then a short pause while the motor chews, with a click in your hand
        //    and a shudder that runs through the shredder, the card and the strips.
        //    When the card is through, it speeds up and leaves through the top of the screen.
        Springs.cancel(sheet, DynamicAnimation.TRANSLATION_Y);
        final float endY = -(overlay.getHeight() - insetBottom) - dp(8);
        final float ty0 = sheet.getTranslationY();
        final float sheetBottom0 = bodyBottom0;
        final float cardBottomOnScreen = cardTopOnScreen + full * scale;
        // tA: the shredder's top edge reaches the card. tB: its bottom edge leaves the card.
        final float tA = Math.min(ty0, cardBottomOnScreen - sheetTop0);
        final float tB = Math.min(tA - dp(40), cardTopOnScreen - sheetBottom0);
        final int steps = Math.max(6, Math.round((tA - tB) / (dp(1) * 62f)));
        final float d1 = Math.abs(ty0 - tA) > dp(8) ? 300f : 0f;   // creeping up to the card
        final float d2 = steps * 175f;                              // the bites
        final float d3 = 400f;                                      // leaving
        final float move = 0.42f;                                   // share of a bite spent pulling
        final Random jr = new Random();
        final int[] lastStep = {-1};

        final ValueAnimator rise = ValueAnimator.ofFloat(0f, d1 + d2 + d3);
        riseRef[0] = rise;
        rise.setStartDelay(120);
        rise.setDuration((long) (d1 + d2 + d3));
        rise.setInterpolator(new android.view.animation.LinearInterpolator());
        rise.addUpdateListener(a2 -> {
            if (done[0]) return;
            float t = (float) a2.getAnimatedValue();
            float ty;
            float shake = 0f;
            if (t < d1) {
                float u = t / d1;
                ty = ty0 + (tA - ty0) * u * u;
            } else if (t < d1 + d2) {
                float u = Math.min(0.9999f, (t - d1) / d2);
                float sx = u * steps;
                int i = (int) sx;
                float f = sx - i;
                // a quick pull that eases into the stop, then it holds
                float m = f < move ? 1f - (float) Math.pow(1f - f / move, 3) : 1f;
                ty = tA + (tB - tA) * ((i + m) / steps);
                if (i != lastStep[0]) {
                    lastStep[0] = i;
                    flipCard.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
                shake = f < move ? 1f : 0.2f + 0.3f * (1f - (f - move) / (1f - move));
            } else {
                float u = Math.min(1f, (t - d1 - d2) / d3);
                ty = tB + (endY - tB) * u * u;
            }
            sheet.setTranslationY(ty);

            // the shudder (only while the card is still being cut)
            if (!fell[0]) {
                float amp = dp(1) * 1.6f * shake;
                float jx = (jr.nextFloat() - 0.5f) * 2f * amp;
                sheet.setTranslationX(jx);
                intactView.setTranslationX(-jx * 0.7f);
                for (int i = 0; i < count; i++) {
                    tails[i].setTranslationX((jr.nextFloat() - 0.5f) * 2f * amp * 0.8f);
                }
            }

            tick.run();
            if (!fell[0] && bottomLocal[0] <= 0f) {
                fell[0] = true;
                sheet.setTranslationX(0f);
                intactView.setTranslationX(0f);
                for (int i = 0; i < count; i++) tails[i].setTranslationX(0f);
                dropStrips.run();
            }
        });
        rise.start();
    }
}