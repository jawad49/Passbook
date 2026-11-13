package com.vault.passbook;

import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CardAdapter extends RecyclerView.Adapter<CardAdapter.Holder> {

    public interface OnCardClick {
        void onClick(File file, View cardView);
    }

    private final List<File> files = new ArrayList<>();
    private final OnCardClick listener;
    private RecyclerView recycler;
    private final Set<File> pendingStagger = new HashSet<>(); // cards that fade in one by one
    private final Set<File> pendingDrop = new HashSet<>();    // a card that was just added

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView rv) {
        recycler = rv;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView rv) {
        recycler = null;
    }

    public CardAdapter(OnCardClick listener) {
        this.listener = listener;
    }

    public void setFiles(List<File> newFiles) {
        setFiles(newFiles, false);
    }

    // animate = true: a new card drops in, and the first appearance of the list staggers
    public void setFiles(List<File> newFiles, boolean animate) {
        List<File> previous = new ArrayList<>(files);
        files.clear();
        files.addAll(newFiles);
        Collections.reverse(files); // the newest card is at the top, like the Wallet app
        if (animate) {
            if (previous.isEmpty()) {
                markStagger();
            } else if (files.size() == previous.size() + 1) {
                for (File f : files) {
                    if (!previous.contains(f)) pendingDrop.add(f);
                }
            }
        }
        notifyDataSetChanged();
        forgetLeftovers();
    }

    // Plays the staggered entrance again (used when the app unlocks)
    public void replayReveal() {
        markStagger();
        notifyDataSetChanged();
        forgetLeftovers();
    }

    private void markStagger() {
        pendingStagger.clear();
        for (int i = 0; i < Math.min(files.size(), Motion.STAGGER_MAX); i++) {
            pendingStagger.add(files.get(i));
        }
    }

    // Cards that were not on screen in the first layout must not animate later, when scrolled in
    private void forgetLeftovers() {
        if (recycler == null) return;
        recycler.postDelayed(() -> {
            pendingStagger.clear();
            pendingDrop.clear();
        }, 600);
    }

    // Every card is shown whole, in its own shape. A horizontal card fills the width.
    // A vertical card keeps its real shape but is narrower, so it does not become huge.
    private void applyShape(Holder h) {
        float density = h.itemView.getResources().getDisplayMetrics().density;
        int fullW = h.itemView.getResources().getDisplayMetrics().widthPixels - Math.round(32 * density);
        boolean vertical = h.ratio < 1f;

        int wantedWidth;
        String wantedRatio;
        if (vertical) {
            int maxHeight = Math.round(fullW * 1.2f);
            wantedWidth = Math.min(fullW, Math.round(maxHeight * h.ratio));
            wantedRatio = h.ratio + ":1";
        } else {
            wantedWidth = ViewGroup.LayoutParams.MATCH_PARENT;
            wantedRatio = "1.586:1";
        }

        FrameLayout.LayoutParams clp = (FrameLayout.LayoutParams) h.card.getLayoutParams();
        if (clp.width != wantedWidth || clp.gravity != Gravity.CENTER_HORIZONTAL) {
            clp.width = wantedWidth;
            clp.gravity = Gravity.CENTER_HORIZONTAL;
            h.card.setLayoutParams(clp);
        }

        ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) h.image.getLayoutParams();
        if (!wantedRatio.equals(lp.dimensionRatio)) {
            lp.dimensionRatio = wantedRatio;
            h.image.setLayoutParams(lp);
        }
        h.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_card, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        // a recycled item must not keep the offset / lift of an earlier drag
        h.itemView.removeCallbacks(h.revealRunnable);
        Springs.jump(h.itemView, DynamicAnimation.ALPHA, 1f);
        Springs.jump(h.itemView, DynamicAnimation.TRANSLATION_Y, 0f);
        h.itemView.setTranslationX(0f);
        h.itemView.setTranslationZ(0f);
        h.itemView.setScaleX(1f);
        h.itemView.setScaleY(1f);
        File file = files.get(position);
        int width = h.itemView.getResources().getDisplayMetrics().widthPixels;
        Bitmap bmp = CardStore.load(file, width);
        h.image.setImageBitmap(bmp);

        h.file = file;
        h.bmp = bmp;
        h.ratio = bmp != null ? (float) bmp.getWidth() / bmp.getHeight() : 1.586f;
        applyShape(h);

        // Entrance: cards fade up one after another, a newly added card drops in from above
        final boolean drop = pendingDrop.remove(file);
        final boolean stagger = pendingStagger.remove(file);
        if ((drop || stagger) && !Motion.reduced()) {
            float density = h.itemView.getResources().getDisplayMetrics().density;
            h.itemView.setAlpha(0f);
            h.itemView.setTranslationY(drop ? -40 * density : 28 * density);
            h.revealRunnable = () -> {
                Springs.to(h.itemView, DynamicAnimation.ALPHA, 1f,
                        Motion.DAMPING_SMOOTH, Motion.STIFF_ENTER);
                Springs.to(h.itemView, DynamicAnimation.TRANSLATION_Y, 0f,
                        drop ? Motion.DAMPING_BOUNCY : Motion.DAMPING_SMOOTH, Motion.STIFF_ENTER);
                if (drop) h.itemView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            };
            h.itemView.postDelayed(h.revealRunnable, drop ? 120L : position * Motion.STAGGER_MS);
        }

        // Reset anything a previous use of this view might have left behind
        h.card.setVisibility(View.VISIBLE);
        Springs.jump(h.card, DynamicAnimation.SCALE_X, 1f);
        Springs.jump(h.card, DynamicAnimation.SCALE_Y, 1f);

        // Press feedback happens on touch-DOWN, instantly
        h.card.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                Springs.to(v, DynamicAnimation.SCALE_X, 0.97f, 1f, Motion.STIFF_PRESS);
                Springs.to(v, DynamicAnimation.SCALE_Y, 0.97f, 1f, Motion.STIFF_PRESS);
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                Springs.to(v, DynamicAnimation.SCALE_X, 1f, 1f, Motion.STIFF_PRESS);
                Springs.to(v, DynamicAnimation.SCALE_Y, 1f, 1f, Motion.STIFF_PRESS);
            }
            return false; // let the click still go through
        });

        h.card.setOnClickListener(v -> listener.onClick(file, h.card));
    }

    @Override
    public int getItemCount() {
        return files.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final View card;
        File file;          // the card shown right now
        Bitmap bmp;
        float ratio = 1.586f; // width / height of the photo (below 1 = vertical card)
        Runnable revealRunnable = () -> { };

        Holder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.cardImage);
            card = itemView.findViewById(R.id.cardView);
        }
    }
}