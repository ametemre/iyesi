package com.kurmez.iyesi.utilities;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.MotionEvent;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;

/**
 * Helper class to manage a set of mini FABs.
 * Provides expand, collapse, draggable behaviors,
 * directional expansion arcs, outside-touch collapse,
 * and selection highlighting.
 */
public class MiniFabs {
    private final Activity activity;
    private final FloatingActionButton mainFab;
    private final FloatingActionButton soundFab;
    private final FloatingActionButton[] miniFabs;
    private final float[][] fabPositions;
    private final View rootView;
    private boolean isExpanded = false;
    private FloatingActionButton selectedFab = null;

    /**
     * Initializes mini FABs and hides them.
     */
    public MiniFabs(Activity activity,
                    FloatingActionButton mainFab,
                    FloatingActionButton soundFab,
                    int[] miniFabIds) {
        this.activity = activity;
        this.mainFab = mainFab;
        this.soundFab = soundFab;
        this.miniFabs = new FloatingActionButton[miniFabIds.length];
        this.fabPositions = new float[miniFabIds.length][2];
        this.rootView = activity.findViewById(android.R.id.content);
        for (int i = 0; i < miniFabIds.length; i++) {
            FloatingActionButton fab = activity.findViewById(miniFabIds[i]);
            fab.setVisibility(View.GONE);
            miniFabs[i] = fab;
        }
        soundFab.setVisibility(View.GONE);
    }

    /**
     * Toggles expand/collapse state.
     */
    public void toggle() {
        if (isExpanded) collapse(); else expand();
        isExpanded = !isExpanded;
    }

    /**
     * Expands mini FABs along dynamic arcs based on mainFab position.
     */
    public void expand() {
        // 1) Merkez
        float centerX = mainFab.getX() + mainFab.getWidth()  / 2f;
        float centerY = mainFab.getY() + mainFab.getHeight() / 2f;

        // 2) Sabit yarıçap ve FAB sayısı
        float radius = 600f;
        int count   = miniFabs.length;

        // 3) Ekran boyutları & FAB boyutları
        int screenW = rootView.getWidth(), screenH = rootView.getHeight();
        float fabW  = mainFab.getWidth(), fabH = mainFab.getHeight();

        // 4) Bölge tespiti: yarım/çeyrek daire
        float leftBound  = screenW  / 3f, rightBound = 2f * screenW / 3f;
        double startAngle, sweep;
        if (centerX < leftBound) {
            startAngle = -Math.PI/2;  sweep = Math.PI;
        } else if (centerX > rightBound) {
            startAngle =  Math.PI/2;  sweep = Math.PI;
        } else {
            if (centerY < screenH/2f) {
                startAngle = (centerX < screenW/2f) ? 0 : Math.PI/2;
            } else {
                startAngle = (centerX < screenW/2f) ? 3*Math.PI/2 : Math.PI;
            }
            sweep = Math.PI/2;
        }

        // 5) Açıları “traşlamak” için başlangıç ve bitiş açıları
        double minA = startAngle;
        double maxA = startAngle + sweep;
        // adım açısı: orijinal yay uzunluğunun küçük bir parçası
        double step = (sweep / (count-1)) * 0.5;

        // 5a) Başlangıcı kırp
        while (minA < maxA) {
            float x0 = centerX + radius * (float)Math.cos(minA) - fabW/2f;
            float y0 = centerY + radius * (float)Math.sin(minA) - fabH/2f;
            if (x0 >= 0 && x0 + fabW <= screenW && y0 >= 0 && y0 + fabH <= screenH) break;
            minA += step;
        }
        // 5b) Bitişi kırp
        while (maxA > minA) {
            float xN = centerX + radius * (float)Math.cos(maxA) - fabW/2f;
            float yN = centerY + radius * (float)Math.sin(maxA) - fabH/2f;
            if (xN >= 0 && xN + fabW <= screenW && yN >= 0 && yN + fabH <= screenH) break;
            maxA -= step;
        }

        // 6) Yeni yay uzunluğu
        double newSweep = maxA - minA;

        // 7) Animasyon: traflanmış açı aralığında eşit böl
        for (int i = 0; i < count; i++) {
            double angle = minA + i * (newSweep / (count - 1));
            float x = centerX + radius * (float)Math.cos(angle) - fabW/2f;
            float y = centerY + radius * (float)Math.sin(angle) - fabH/2f;

            FloatingActionButton fab = miniFabs[i];
            fab.setVisibility(View.VISIBLE);
            AnimatorSet anim = new AnimatorSet();
            anim.playTogether(
                    ObjectAnimator.ofFloat(fab, "x",     mainFab.getX(), x),
                    ObjectAnimator.ofFloat(fab, "y",     mainFab.getY(), y),
                    ObjectAnimator.ofFloat(fab, "alpha", 0f,              1f)
            );
            anim.setInterpolator(new FastOutSlowInInterpolator());
            anim.setDuration(400);
            anim.start();
        }

        // 8) soundFab ve mainFab
        soundFab.setX(mainFab.getX());
        soundFab.setY(mainFab.getY());
        soundFab.setVisibility(View.VISIBLE);
        ObjectAnimator.ofFloat(soundFab, "alpha", 0f, 1f)
                .setDuration(400)
                .start();
        mainFab.setVisibility(View.GONE);
    }


    /**
     * Collapses mini FABs back to main FAB.
     */
    public void collapse() {
        float centerX = mainFab.getX();
        float centerY = mainFab.getY();

        for (FloatingActionButton fab : miniFabs) {
            AnimatorSet anim = new AnimatorSet();
            anim.playTogether(
                    ObjectAnimator.ofFloat(fab, "x", fab.getX(), centerX),
                    ObjectAnimator.ofFloat(fab, "y", fab.getY(), centerY),
                    ObjectAnimator.ofFloat(fab, "alpha", 1f, 0f)
            );
            anim.setInterpolator(new FastOutSlowInInterpolator());
            anim.setDuration(300);
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    fab.setVisibility(View.GONE);
                }
            });
            anim.start();
        }
        // Hide soundFab
        ObjectAnimator soundAnim = ObjectAnimator.ofFloat(soundFab, "alpha", 1f, 0f);
        soundAnim.setDuration(300);
        soundAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                soundFab.setVisibility(View.GONE);
            }
        });
        soundAnim.start();
        mainFab.setVisibility(View.VISIBLE);
    }

    /**
     * Moves mini FABs when mainFab is dragged.
     */
    public void move(float deltaX, float deltaY) {
        for (int i = 0; i < miniFabs.length; i++) {
            miniFabs[i].setX(fabPositions[i][0] + deltaX);
            miniFabs[i].setY(fabPositions[i][1] + deltaY);
        }
    }

    /**
     * Returns expansion state.
     */
    public boolean isExpanded() {
        return isExpanded;
    }

    /**
     * Collapses on outside touch; returns true if consumed.
     */
    public boolean handleOutsideTouch(MotionEvent ev) {
        if (ev.getAction() != MotionEvent.ACTION_DOWN || !isExpanded) return false;
        int x = (int)ev.getRawX(), y = (int)ev.getRawY();
        if (isInsideView(mainFab, x, y) || isInsideView(soundFab, x, y)) return false;
        for (FloatingActionButton fab : miniFabs) if (isInsideView(fab, x, y)) return false;
        collapse(); isExpanded = false; return true;
    }

    /**
     * Utility to check if (x,y) inside view bounds.
     */
    private boolean isInsideView(View v, int x, int y) {
        int[] loc = new int[2]; v.getLocationOnScreen(loc);
        return x >= loc[0] && x <= loc[0] + v.getWidth()
                && y >= loc[1] && y <= loc[1] + v.getHeight();
    }

    /**
     * Resets all miniFABs to teal background & original icon color.
     */
    public void applyDefaultColors() {
        for (FloatingActionButton fab : miniFabs) {
            fab.setBackgroundTintList(
                    ColorStateList.valueOf(Color.parseColor("#FF40C4FF"))
            );
            Drawable icon = fab.getDrawable();
            if (icon != null) {
                Drawable mutated = icon.mutate();
                mutated.clearColorFilter();
                fab.setImageDrawable(mutated);
            }
        }
    }

    /**
     * Highlights the selected FAB with red background & white icon.
     */
    public void selectFab(FloatingActionButton fab) {
        applyDefaultColors();
        selectedFab = fab;
        fab.setBackgroundTintList(ColorStateList.valueOf(Color.RED));
        Drawable icon = fab.getDrawable();
        if (icon != null) {
            Drawable wIcon = icon.mutate();
            wIcon.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP);
            fab.setImageDrawable(wIcon);
        }
    }

    /**
     * Exposes miniFAB array.
     */
    public FloatingActionButton[] getFabs() {
        return miniFabs;
    }
}