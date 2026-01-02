package com.kurmez.iyesi.kurmes.seed;

import android.util.Log;

import com.kurmez.iyesi.kurmes.utilities.helper.net.CFClient;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorCompletionService;

import okhttp3.Response;

/**
 * SeedService
 * Şehir bazlı sahte/örnek Soul kayıtlarını Cloud Function'a yükler.
 * - CFClient: com.kurmez.iyesi.kurmes.utilities.helper.net.CFClient
 * - Gerekli CFClient imzası: post(String url, String rawJson, String idToken, String appCheckToken)
 * - Tokenlar: CFClient.getTokens(TokensCallback, ErrorCallback)
 */
public class SeedService {
    private static final String TAG = "SeedService";

    private final CFClient cf;
    private final String createPath; // Örn: https://<region>-<project>.cloudfunctions.net/souls/create

    public SeedService(CFClient cf, String createPath) {
        this.cf = cf;
        this.createPath = createPath;
    }

    /**
     * Tek bir şehir için payload listesini gönderir.
     * @return başarılı (2xx) yanıt sayısı
     */
    public int seedSouls(String city, List<JSONObject> payloads, String idToken, String appCheckToken) {
        int ok = 0;
        for (int i = 0; i < payloads.size(); i++) {
            JSONObject p = payloads.get(i);
            try (Response r = cf.post(createPath, p.toString(), idToken, appCheckToken)) {
                if (r.isSuccessful()) {
                    ok++;
                } else if (r.code() == 429 || r.code() == 500 || r.code() == 502 || r.code() == 503 || r.code() == 504) {
                    // Basit retry
                    try { Thread.sleep(800); } catch (InterruptedException ignored) {}
                    try (Response r2 = cf.post(createPath, p.toString(), idToken, appCheckToken)) {
                        if (r2.isSuccessful()) {
                            ok++;
                        } else {
                            Log.w(TAG, city + " retry fail: " + r.code() + "→" + r2.code());
                        }
                    }
                } else {
                    Log.w(TAG, city + " fail: " + r.code() + " " + r.message());
                }
            } catch (Exception e) {
                Log.e(TAG, "seed error (" + city + "): " + e.getMessage());
            }

            if ((i + 1) % 50 == 0) {
                Log.i(TAG, "[" + city + "] " + (i + 1) + "/" + payloads.size() + " gönderildi...");
            }
        }
        Log.i(TAG, "[" + city + "] tamamlandı. Başarılı: " + ok + "/" + payloads.size());
        return ok;
    }

    public interface SeedListener {
        void onCityDone(String city, int ok, int total);
        void onAllDone();
        void onError(Exception e);
    }

    /**
     * Birden fazla şehir için paralel seed akışı.
     *
     * @param cities         İşlenecek şehir listesi
     * @param soulsPerCity   Her şehir için üretilecek soul adedi
     * @param withImages     Görseller üret/dahil et bayrağı
     * @param concurrency    Aynı anda kaç şehir işlensin
     * @param listener       İlerleme geribildirimi
     */
    public void seedCities(List<String> cities,
                           int soulsPerCity,
                           boolean withImages,
                           int concurrency,
                           SeedListener listener) {

        // Tokenları al (Firebase ID Token + App Check)
        CFClient.getTokens((idToken, appToken) -> {
            ExecutorService ex = Executors.newFixedThreadPool(Math.max(1, concurrency));
            CompletionService<String> cs = new ExecutorCompletionService<>(ex);

            for (String city : cities) {
                cs.submit(() -> {
                    try {
                        double[] center = SeedUtils.citySeedPoint(city);
                        long now = System.currentTimeMillis();

                        // Şehir için sahte soul verileri üret
                        List<JSONObject> souls = SeedUtils.generateSoulsForCity(
                                city, center[0], center[1], soulsPerCity, withImages, now
                        );

                        // Gönder
                        int ok = seedSouls(city, souls, idToken, appToken);

                        if (listener != null) listener.onCityDone(city, ok, souls.size());
                    } catch (Exception e) {
                        Log.e(TAG, "city task error (" + city + "): " + e.getMessage());
                        if (listener != null) listener.onError(e);
                    }
                    return city;
                });
            }

            // Tüm işler bitene kadar bekle
            for (int i = 0; i < cities.size(); i++) {
                try {
                    cs.take().get(); // tamamlanan bir işi bekle
                } catch (Exception ignored) { }
            }

            ex.shutdown();
            if (listener != null) listener.onAllDone();

        }, e -> {
            Log.e(TAG, "token error: " + e.getMessage());
            if (listener != null) {
                listener.onError(e instanceof Exception ? (Exception) e : new Exception(e));
            }
        });
    }
}
