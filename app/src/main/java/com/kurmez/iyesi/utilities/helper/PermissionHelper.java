// PermissionHelper.java
package com.kurmez.iyesi.utilities.helper;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class PermissionHelper {
    private ComponentActivity activity;
    private ActivityResultLauncher<String[]> permissionLauncher;
    private Callback callback;

    /** No-arg constructor; call initializePermissions() before requesting. */
    public PermissionHelper() { }

    /** Activity’yi atar. */
    public void setActivity(@NonNull ComponentActivity activity) {
        this.activity = activity;
    }

    /** Callback’i atar. */
    public void setCallback(@NonNull Callback callback) {
        this.callback = callback;
    }
    /**
     * Must be called once (e.g. in Activity.onCreate).
     * After this, you can call requestAllPermissions(), requestCamera(), etc., without args.
     */
    /** Launcher’ı kaydeder; parametresiz. */
    public void initialize() {
        if (activity == null || callback == null) {
            throw new IllegalStateException(
                    "PermissionHelper: önce setActivity() ve setCallback() çağrılmalı."
            );
        }
        permissionLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean all = true;
                    for (Boolean g : result.values()) {
                        if (!g) { all = false; break; }
                    }
                    if (all) callback.onGranted(); else callback.onDenied();
                }
        );
    }
    public void initializePermissions(
            @NonNull ComponentActivity activity,
            @NonNull Callback callback
    ) {
        this.activity = activity;
        this.callback = callback;
        this.permissionLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean allGranted = true;
                    for (Boolean granted : result.values()) {
                        if (!granted) {
                            allGranted = false;
                            break;
                        }
                    }
                    if (allGranted) callback.onGranted();
                    else callback.onDenied();
                }
        );
    }

    /** Tüm izinleri tek seferde ister. */
    public void requestAllPermissions() {
        ensureInitialized();
        List<String> perms = new ArrayList<>();

        // Kamera, mikrofon
        perms.add(Manifest.permission.CAMERA);
        perms.add(Manifest.permission.RECORD_AUDIO);

        // Konum
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);

        // Depolama/medya
        if (Build.VERSION.SDK_INT <= 32) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        } else {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES);
            perms.add(Manifest.permission.READ_MEDIA_VIDEO);
            perms.add(Manifest.permission.READ_MEDIA_AUDIO);
        }

        // Bluetooth
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        } else {
            perms.add(Manifest.permission.BLUETOOTH);
            perms.add(Manifest.permission.BLUETOOTH_ADMIN);
        }

        permissionLauncher.launch(perms.toArray(new String[0]));
    }
    private void ensureInitialized() {
        if (permissionLauncher == null) {
            throw new IllegalStateException(
                    "PermissionHelper: initialize() çağrılmadan request...() kullanılamaz."
            );
        }
    }
    /** Just camera. */
    public void requestCamera() {
        ensureActivity();
        permissionLauncher.launch(new String[]{ Manifest.permission.CAMERA });
    }

    /** Just audio +, if you need storage alongside audio. */
    public void requestAudio() {
        ensureActivity();
        permissionLauncher.launch(new String[]{ Manifest.permission.RECORD_AUDIO });
    }

    /** Just location. */
    public void requestLocation() {
        ensureActivity();
        permissionLauncher.launch(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        });
    }

    /** Just storage / media perms. */
    public void requestStorage() {
        ensureActivity();
        if (Build.VERSION.SDK_INT <= 32) {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            });
        } else {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO,
            });
        }
    }

    /** Just Bluetooth. */
    public void requestBluetooth() {
        ensureActivity();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
            });
        } else {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN
            });
        }
    }

    /** Helper to ensure initializePermissions was called. */
    private void ensureActivity() {
        if (activity == null || permissionLauncher == null || callback == null) {
            throw new IllegalStateException(
                    "PermissionHelper not initialized—call initializePermissions() first."
            );
        }
    }

    /** Callback for grant/denial. */
    public interface Callback {
        void onGranted();
        void onDenied();
    }
}
