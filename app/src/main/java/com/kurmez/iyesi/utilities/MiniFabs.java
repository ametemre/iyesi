package com.kurmez.iyesi.utilities;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.view.VelocityTracker;
import android.view.View;
import android.view.MotionEvent;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.maps.android.data.geojson.GeoJsonLayer;
import com.kurmez.iyesi.Login;
import com.kurmez.iyesi.kurmes.Kurmes;
import com.kurmez.iyesi.sahiplendirme.Welcome;
import com.kurmez.iyesi.sokak.Harita;

import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.ScaleAnimation;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Spinner;

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
    private FloatingActionButton selectedFab = null; // Track the selected FAB
    private VelocityTracker velocityTracker = null;
    private FloatingActionButton fabDraggable, fabSound;
    private float dX, dY;
    private float mainFabX, mainFabY; // Stores main FAB's position
    private long pressStartTime;
    private boolean isDragging = false;
    private final int LONG_PRESS_THRESHOLD = 2000; // 2 seconds
    private final int DRAG_THRESHOLD = 20; // Minimum movement to consider a drag
    private FrameLayout rootLayout;
    private Spinner spinner1, spinner2, spinner3, spinner4, spinner5;
    private ImageButton clear1, clear2, clear3, clear4, clear5;
    private ImageButton toggle1, toggle2, toggle3, toggle4, toggle5;
    private GeoJsonLayer layerCountry, layerProvince, layerDistrict;
    private final String[] levels = {"ADM5", "ADM4", "ADM3", "ADM2", "ADM1", "ADM0", "OSM"};
    // Harita işlemlerini devredecek Harita nesnesi
    private Harita harita;
    // SokakActivity içine, class-level’da:
    private boolean isFabOpen = false;
    private Animation fabOpenAnim, fabCloseAnim, rotateForwardAnim, rotateBackwardAnim;
    private Handler handler = new Handler();
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
        this.rootLayout = (FrameLayout) this.rootView;
        for (int i = 0; i < miniFabIds.length; i++) {
            FloatingActionButton fab = activity.findViewById(miniFabIds[i]);
            fab.setVisibility(View.GONE);
            miniFabs[i] = fab;
        }
        soundFab.setVisibility(View.GONE);
    }
    public void toggle() {
        if (isExpanded) collapse(); else expand();
        isExpanded = !isExpanded;
    }
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
    public void move(float deltaX, float deltaY) {
        for (int i = 0; i < miniFabs.length; i++) {
            miniFabs[i].setX(fabPositions[i][0] + deltaX);
            miniFabs[i].setY(fabPositions[i][1] + deltaY);
        }
    }

    public boolean isExpanded() {
        return isExpanded;
    }

    public boolean handleOutsideTouch(MotionEvent ev) {
        if (ev.getAction() != MotionEvent.ACTION_DOWN || !isExpanded) return false;
        int x = (int)ev.getRawX(), y = (int)ev.getRawY();
        if (isInsideView(mainFab, x, y) || isInsideView(soundFab, x, y)) return false;
        for (FloatingActionButton fab : miniFabs) if (isInsideView(fab, x, y)) return false;
        collapse(); isExpanded = false; return true;
    }
    private boolean isInsideView(View v, int x, int y) {
        int[] loc = new int[2]; v.getLocationOnScreen(loc);
        return x >= loc[0] && x <= loc[0] + v.getWidth()
                && y >= loc[1] && y <= loc[1] + v.getHeight();
    }
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
    public FloatingActionButton selectFab(FloatingActionButton fab) {
        //applyDefaultColors();
        selectedFab = fab;
        fab.setBackgroundTintList(ColorStateList.valueOf(Color.RED));
        Drawable icon = fab.getDrawable();
        if (icon != null) {
            Drawable wIcon = icon.mutate();
            wIcon.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP);
            fab.setImageDrawable(wIcon);
        }
        return selectedFab;
    }
    public void resetIconColor(FloatingActionButton fab) {
        fab.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FF40C4FF"))); // Teal
        Drawable drawable = fab.getDrawable();
        if (drawable != null) {
            drawable = drawable.mutate();
            drawable.clearColorFilter(); // Remove any color filters
            fab.setImageDrawable(drawable);
        }
    }
    public void applyWhiteColorFilter(FloatingActionButton fab) {
        Drawable drawable = fab.getDrawable();
        if (drawable != null) {
            drawable = drawable.mutate(); // Make sure we modify only this instance
            drawable.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP); // Apply White Filter
            fab.setImageDrawable(drawable);
        }
    }
    public void animateMomentumGravity(View v, float velocityX, float velocityY, FrameLayout rootLayout) {
        float screenHeight, screenWidth;
        if (rootLayout != null) {
            screenHeight = rootLayout.getHeight();
            screenWidth  = rootLayout.getWidth();
        } else {
            // rootLayout null ise, ana view’in boyutlarını kullan
            screenHeight = v.getHeight();
            screenWidth  = v.getWidth();
        }
        // Calculate projected landing position based on velocity
        float projectedX = v.getX() + (velocityX * 0.2f); // Multiply for "throw" effect
        float projectedY = v.getY() + (velocityY * 0.2f);

        // Ensure it doesn't go off-screen
        projectedX = Math.max(0, Math.min(projectedX, screenWidth - v.getWidth()));
        projectedY = Math.min(screenHeight - v.getHeight(), projectedY);

        // Animate movement with bounce effect
        ValueAnimator animatorX = ValueAnimator.ofFloat(v.getX(), projectedX);
        ValueAnimator animatorY = ValueAnimator.ofFloat(v.getY(), projectedY);

        animatorX.setInterpolator(new DecelerateInterpolator());
        animatorY.setInterpolator(new DecelerateInterpolator());

        animatorX.setDuration(500);
        animatorY.setDuration(500);

        animatorX.addUpdateListener(animation -> v.setX((float) animation.getAnimatedValue()));
        animatorY.addUpdateListener(animation -> v.setY((float) animation.getAnimatedValue()));

        animatorX.start();
        animatorY.start();
    }
    public FloatingActionButton[] getFabs() {
        return miniFabs;
    }

    @SuppressLint("ClickableViewAccessibility")
    public void setupDraggableFAB(MiniFabs miniFabs, FloatingActionButton fabDraggable) {
        fabDraggable.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // Başlangıç pozisyonlarını ve zaman damgasını ayarla
                    dX = v.getX() - event.getRawX();
                    dY = v.getY() - event.getRawY();
                    mainFabX = v.getX();
                    mainFabY = v.getY();
                    isDragging = false;
                    pressStartTime = System.currentTimeMillis();
                    // VelocityTracker hazırla
                    if (velocityTracker == null) {
                        velocityTracker = VelocityTracker.obtain();
                    } else {
                        velocityTracker.clear();
                    }
                    velocityTracker.addMovement(event);
                    //SetLabelText("Ready !");
                    return true;

                case MotionEvent.ACTION_MOVE:
                    // Yeni pozisyonu hesapla
                    float newX = event.getRawX() + dX;
                    float newY = event.getRawY() + dY;
                    // Sürükleme eşiğini kontrol et
                    if (Math.abs(newX - v.getX()) > DRAG_THRESHOLD ||
                            Math.abs(newY - v.getY()) > DRAG_THRESHOLD) {
                        isDragging = true;
                    }
                    // Hız takibi
                    velocityTracker.addMovement(event);
                    velocityTracker.computeCurrentVelocity(1000);
                    // FAB ve miniFAB’ları taşı
                    v.setX(newX);
                    v.setY(newY);
                    miniFabs.move(newX - mainFabX, newY - mainFabY);
                    mainFabX = newX;
                    mainFabY = newY;
                    return true;

                case MotionEvent.ACTION_UP:
                    velocityTracker.addMovement(event);
                    velocityTracker.computeCurrentVelocity(1000);
                    if (!isDragging) {
                        long pressDuration = System.currentTimeMillis() - pressStartTime;
                        if (pressDuration < LONG_PRESS_THRESHOLD) {
                            // Kısa tıklama: miniFAB menüsünü toggle et
                            miniFabs.toggle();
                        } else {
                            // Uzun basış
                            handleLongClick();
                        }
                    } else {
                        // Sürükleme sonrası momentumlu animasyon
                        float vx = velocityTracker.getXVelocity();
                        float vy = velocityTracker.getYVelocity();
                        miniFabs.animateMomentumGravity(v, vx, vy,this.rootLayout);
                    }
                    return true;

                default:
                    return false;
            }
        });
    }

    private void handleLongClick() {/*
        animateButtonPress();
        if (mAuth.getCurrentUser() != null) {
            startActivity(new Intent(Kurmes.this, Welcome.class));
        } else {
            startActivity(new Intent(Kurmes.this, Login.class));
        }*/
    }
    /**
     * @return Şu anda seçili olan FloatingActionButton,
     *         eğer hiç seçim yapılmadıysa null döner.
     */
    public FloatingActionButton getSelectedFab() {
        return selectedFab;
    }
    private void animateButtonPress(FloatingActionButton fabDraggable) {
        fabDraggable.setEnabled(false);

        // Create shadow effect
        Animation scaleDown = new ScaleAnimation(
                1f, 0.9f, 1f, 0.9f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f);
        scaleDown.setDuration(500);
        scaleDown.setFillAfter(true);

        Animation fadeOut = new AlphaAnimation(1f, 0.6f);
        fadeOut.setDuration(2000);

        fabDraggable.startAnimation(scaleDown);
        fabDraggable.startAnimation(fadeOut);

        handler.postDelayed(() -> {
            fabDraggable.clearAnimation();
            fabDraggable.setEnabled(true);
        }, 2000);
    }
}