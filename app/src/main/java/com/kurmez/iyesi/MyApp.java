package com.kurmez.iyesi;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.security.ProviderInstaller;
import com.google.firebase.FirebaseApp;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory;

public class MyApp extends Application {
    private static final String TAG = "MyApp";

    @Override
    public void onCreate() {
        super.onCreate();
        FirebaseApp.initializeApp(this);
        FirebaseAppCheck firebaseAppCheck = FirebaseAppCheck.getInstance();
        // This will always succeed in dev, and suppress the broker‐error:
        firebaseAppCheck.installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance());
    }

    /**
     * Eğer cihazda Google Play Services uygunsa ProviderInstaller'ı başlatır,
     * değilse atlayıp DEVELOPER_ERROR log'larını önler.
     */
    private void safeInstallProviderIfNeeded(Context ctx) {
        GoogleApiAvailability api = GoogleApiAvailability.getInstance();
        int status = api.isGooglePlayServicesAvailable(ctx);
        if (status == ConnectionResult.SUCCESS) {
            ProviderInstaller.installIfNeededAsync(ctx, new ProviderInstaller.ProviderInstallListener() {
                @Override
                public void onProviderInstalled() {
                    Log.d(TAG, "Provider başarıyla yüklendi");
                }

                @Override
                public void onProviderInstallFailed(int errorCode, Intent recoveryIntent) {
                    Log.w(TAG, "Provider yüklemesi başarısız, code: " + errorCode);
                }
            });
        } else {
            Log.w(TAG, "Google Play Services mevcut değil (code: " + status + "), ProviderInstaller atlandı");
        }
    }
}
