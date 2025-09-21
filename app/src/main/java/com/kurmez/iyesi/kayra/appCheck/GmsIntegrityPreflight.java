package com.kurmez.iyesi.kayra.appCheck;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.security.ProviderInstaller;
import com.google.android.play.core.integrity.IntegrityManager;
import com.google.android.play.core.integrity.IntegrityManagerFactory;
import com.google.android.play.core.integrity.IntegrityServiceException;

/**
 * GmsIntegrityPreflight — GMS/Play/Integrity hazır mı?
 *
 * Bakım Noktaları:
 *  - MIN_GMS_VERSION / MIN_PLAY_VERSION eşiklerini güncelle.
 *  - TLS için Security Provider (ProviderInstaller) zorlamasını açık tut.
 *  - (Opsiyonel) Kurum sağlık endpoint’i: CF_HEALTH_URL (HEAD/GET 200).
 *
 * Endpointler (örnek):
 *  - public static final String CF_HEALTH_URL = "https://<region>-<project>.cloudfunctions.net/health";
 */
public final class GmsIntegrityPreflight {
    private GmsIntegrityPreflight() {}
    private static final String TAG = "GmsIntegrityPreflight";
    private static final String PKG_GMS = "com.google.android.gms";
    private static final String PKG_PLAY = "com.android.vending";

    // Bakım: sürüm eşikleri (örnek değerler)
    private static final long MIN_GMS_VERSION  = 240000000L; // 24.0.0+
    private static final long MIN_PLAY_VERSION = 380000000L; // 38.0.0+

    public interface Callback { void onResult(@NonNull Result r); }

    public static final class Result {
        public final boolean ok, gmsOk, playOk, netOk, secProvOk, integrityOk;
        public final long gmsVer, playVer;
        public final String hint;
        public Result(boolean ok, boolean gmsOk, boolean playOk, boolean netOk,
                      boolean secProvOk, boolean integrityOk, long gmsVer, long playVer, String hint){
            this.ok=ok; this.gmsOk=gmsOk; this.playOk=playOk; this.netOk=netOk;
            this.secProvOk=secProvOk; this.integrityOk=integrityOk; this.gmsVer=gmsVer; this.playVer=playVer; this.hint=hint;
        }
        @NonNull @Override public String toString(){
            return "ok="+ok+" gmsOk="+gmsOk+" playOk="+playOk+" netOk="+netOk+
                    " secProvOk="+secProvOk+" integrityOk="+integrityOk+
                    " gmsVer="+gmsVer+" playVer="+playVer+" hint="+hint;
        }
    }

    // ---- Public API ----
    public static void run(@NonNull Context c, @NonNull Callback cb){
        long t0 = enter("run","pkg="+c.getPackageName());

        boolean netOk     = isOnline(c);
        long gmsVer       = pkgVer(c, PKG_GMS);
        long playVer      = pkgVer(c, PKG_PLAY);
        boolean gmsAvail  = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(c) == ConnectionResult.SUCCESS;
        boolean gmsVerOk  = gmsVer  >= MIN_GMS_VERSION;
        boolean playVerOk = playVer >= MIN_PLAY_VERSION;
        boolean secProvOk = installSecurityProviderSafe(c);
        boolean integrityOk = integrityUsable(c);

        boolean allOk = netOk && gmsAvail && gmsVerOk && playVerOk && secProvOk && integrityOk;
        String hint = buildHint(netOk, gmsAvail, gmsVerOk, playVerOk, secProvOk, integrityOk);

        Result r = new Result(allOk, gmsAvail, playVerOk, netOk, secProvOk, integrityOk, gmsVer, playVer, hint);
        leave("run", t0, "result="+r);
        cb.onResult(r);
    }

    // ---- Detail checks ----
    private static boolean integrityUsable(Context c){
        long t = enter("integrityUsable", null);
        try {
            IntegrityManager m = IntegrityManagerFactory.create(c);
            boolean ok = (m != null);
            leave("integrityUsable", t, "ok="+ok);
            return ok;
        } catch (Throwable th){
            Log.w(TAG,"Integrity unavailable", th);
            leave("integrityUsable", t, "ok=false(th)");
            return false;
        }
    }

    private static boolean installSecurityProviderSafe(Context c){
        long t = enter("installSecurityProviderSafe", null);
        try {
            ProviderInstaller.installIfNeeded(c);
            leave("installSecurityProviderSafe", t, "ok=true");
            return true;
        } catch (Exception e){
            Log.w(TAG,"ProviderInstaller failed", e);
            leave("installSecurityProviderSafe", t, "ok=false");
            return false;
        }
    }

    private static long pkgVer(Context c, String pkg){
        long t = enter("pkgVer","pkg="+pkg);
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(pkg, 0);
            long v = (Build.VERSION.SDK_INT>=28)? pi.getLongVersionCode() : pi.versionCode;
            leave("pkgVer", t, "ver="+v);
            return v;
        } catch (PackageManager.NameNotFoundException e){
            leave("pkgVer", t, "ver=0(notfound)");
            return 0L;
        }
    }

    private static boolean isOnline(Context c){
        long t = enter("isOnline", null);
        boolean ok = false;
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null){
            if (Build.VERSION.SDK_INT >= 23){
                Network n = cm.getActiveNetwork();
                if (n != null){
                    NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                    ok = caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                            || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
                }
            } else {
                // Eski cihazlarda temel varsayım
                ok = true;
            }
        }
        leave("isOnline", t, "ok="+ok);
        return ok;
    }

    private static String buildHint(boolean net, boolean gmsAvail, boolean gmsVerOk, boolean playVerOk, boolean sec, boolean integ){
        long t = enter("buildHint","net="+net+",gms="+gmsAvail+",gmsVerOk="+gmsVerOk+",playVerOk="+playVerOk+",sec="+sec+",integ="+integ);
        String h;
        if (!net)         h = "Ağ yok: Wi-Fi/Mobil veriyi aç.";
        else if (!gmsAvail) h = "Google Play Services eksik/güncel değil.";
        else if (!gmsVerOk) h = "Play Services sürümü düşük: güncelle.";
        else if (!playVerOk)h = "Play Store sürümü düşük: güncelle.";
        else if (!sec)      h = "TLS Security Provider güncellenemedi.";
        else if (!integ)    h = "Play Integrity kullanılamıyor (cihaz/bölge?).";
        else                h = "OK";
        leave("buildHint", t, "hint="+h);
        return h;
    }

    // ---- Logging helpers ----
    private static long enter(String fn, String detail){
        long t = System.currentTimeMillis();
        Log.d(TAG, "→ " + fn + (detail==null? "":" | "+detail));
        return t;
    }
    private static void leave(String fn, long startMs, String detail){
        long dur = System.currentTimeMillis() - startMs;
        Log.d(TAG, "← " + fn + " | " + (detail==null? "":detail) + " ("+dur+"ms)");
    }

    // Örnek sağlık endpoint’i (isteğe bağlı): public static final String CF_HEALTH_URL=...
    // Buraya basit bir HEAD/GET 200 kontrolü ekleyebilirsin (timeout kısa tut).
}
