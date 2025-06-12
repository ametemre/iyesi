package com.kurmez.iyesi.utilities.Ai;

import java.util.ArrayList;
import java.util.List;

public class PostProcessor {
    private final Ai ai;

    public PostProcessor(Ai ai) {
        this.ai = ai;
    }

    public List<Detection> decodeAndNms(float[][][] rawOutput, float threshold) {
        List<Detection> detections = new ArrayList<>();
        // Burada gerçek decode ve NMS işlemlerini yap.
        // Şimdilik boş döndürüyoruz.
        return detections;
    }
}
