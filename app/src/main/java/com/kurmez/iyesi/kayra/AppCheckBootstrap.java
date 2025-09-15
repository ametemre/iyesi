package com.kurmez.iyesi.kayra;

import android.content.Context;
import android.util.Log;

import com.google.firebase.appcheck.BuildConfig;

import com.google.firebase.appcheck.FirebaseAppCheck;

public final class AppCheckBootstrap {

    public static void installProvider(Context ctx) {
        FirebaseAppCheck appCheck = FirebaseAppCheck.getInstance();

        boolean isDebug = BuildConfig.DEBUG;
        GmsIntegrityPreflight.Result pf = GmsIntegrityPreflight.run(ctx);

        if (isDebug || !pf.ok) {
            // Debug/dev veya ortam Play Integrity’ye uygun değil → Debug App Check
            appCheck.installAppCheckProviderFactory(
                    com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory.getInstance());
            Log.w("AppCheck", "Using DebugAppCheckProviderFactory (" + (isDebug ? "debug" : pf.reason) + ")");
        } else {
            // Üretim/QA ve ortam uygun → Play Integrity
            appCheck.installAppCheckProviderFactory(
                    com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory.getInstance());
            Log.i("AppCheck", "Using PlayIntegrityAppCheckProviderFactory");
        }
    }
}

