package com.kurmez.iyesi.kayra;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

public final class GmsIntegrityPreflight {

    public static final class Result {
        public final boolean ok;             // Integrity çalıştırılabilir mi?
        public final String reason;          // Neden/nasıl değil?
        public Result(boolean ok, String reason) { this.ok = ok; this.reason = reason; }
    }

    public static Result run(Context ctx) {
        try {
            final PackageManager pm = ctx.getPackageManager();
            final String pkg = ctx.getPackageName();

            // 1) Installer
            String installer = pm.getInstallerPackageName(pkg);
            boolean fromPlay = "com.android.vending".equals(installer);

            // 2) Play Store & GMS var mı ve enabled mı?
            boolean playStoreOk = isEnabled(pm, "com.android.vending");
            boolean gmsOk       = isEnabled(pm, "com.google.android.gms");

            // 3) GoogleApiAvailability ile genel servis kontrolü
            int gmsStatus = com.google.android.gms.common.GoogleApiAvailability
                    .getInstance().isGooglePlayServicesAvailable(ctx);
            boolean gmsAvailable = (gmsStatus == com.google.android.gms.common.ConnectionResult.SUCCESS);

            if (!playStoreOk) return new Result(false, "play_store_disabled");
            if (!gmsOk)       return new Result(false, "gms_disabled");
            if (!gmsAvailable)return new Result(false, "gms_unavailable");
            if (!fromPlay)    return new Result(false, "not_installed_from_play"); // Sideload vs.

            return new Result(true, "ok");
        } catch (Throwable t) {
            return new Result(false, "exception:" + t.getClass().getSimpleName());
        }
    }

    private static boolean isEnabled(PackageManager pm, String packageName) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return info != null && info.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}

