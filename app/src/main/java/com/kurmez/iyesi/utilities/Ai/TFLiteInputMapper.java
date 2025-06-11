package com.kurmez.iyesi.utilities.Ai;

import android.graphics.RectF;

public class TFLiteInputMapper {
    private final int frameWidth;
    private final int frameHeight;
    private final int inputWidth;
    private final int inputHeight;

    // Hesaplanan değerler
    private float scaleFactor;
    private int offsetX;
    private int offsetY;

    /**
     * @param frameWidth  Kamera frame genişliği (px)
     * @param frameHeight Kamera frame yüksekliği (px)
     * @param inputWidth  Modelin beklediği input genişliği (px)
     * @param inputHeight Modelin beklediği input yüksekliği (px)
     */
    public TFLiteInputMapper(int frameWidth, int frameHeight, int inputWidth, int inputHeight) {
        this.frameWidth  = frameWidth;
        this.frameHeight = frameHeight;
        this.inputWidth  = inputWidth;
        this.inputHeight = inputHeight;
        calculate();  // Ölçek ve paddingi hesapla
    }

    // Frame ile model input aspect ratio'larına göre ölçek ve padding hesaplaması
    private void calculate() {
        float frameRatio = (float) frameWidth / frameHeight;
        float inputRatio = (float) inputWidth / inputHeight;

        if (frameRatio > inputRatio) {
            // Frame daha geniş => model yüksekliğine göre ölçekle
            scaleFactor = (float) inputHeight / frameHeight;
            int scaledWidth = Math.round(frameWidth * scaleFactor);
            offsetX = (inputWidth - scaledWidth) / 2;
            offsetY = 0;
        } else {
            // Frame daha yüksek veya eşit => model genişliğine göre ölçekle
            scaleFactor = (float) inputWidth / frameWidth;
            int scaledHeight = Math.round(frameHeight * scaleFactor);
            offsetX = 0;
            offsetY = (inputHeight - scaledHeight) / 2;
        }
    }

    /** Ölçek katsayısı (frame→model) */
    public float getScaleFactor() {
        return scaleFactor;
    }

    /** X yönündeki padding (px) */
    public int getOffsetX() {
        return offsetX;
    }

    /** Y yönündeki padding (px) */
    public int getOffsetY() {
        return offsetY;
    }

    /** Yeniden boyutlandırılmış genişlik (px) */
    public int getScaledWidth() {
        return Math.round(frameWidth * scaleFactor);
    }

    /** Yeniden boyutlandırılmış yükseklik (px) */
    public int getScaledHeight() {
        return Math.round(frameHeight * scaleFactor);
    }
    /**
     * Frame üzerindeki bir dikdörtgeni model input’a çevirir.
     * @param rect Frame üzerindeki RectF (px)
     * @return model input üzerindeki RectF
     */
    public RectF mapRect(RectF rect) {
        float[] topLeft     = mapPoint(rect.left, rect.top);
        float[] bottomRight = mapPoint(rect.right, rect.bottom);
        return new RectF(topLeft[0], topLeft[1], bottomRight[0], bottomRight[1]);
    }
    /**
     * Frame koordinatındaki bir noktayı model input koordinatına çevirir.
     * @param x Frame üzerindeki x (px)
     * @param y Frame üzerindeki y (px)
     * @return [tx, ty] model input üzerindeki nokta koordinatı
     */
    public float[] mapPoint(float x, float y) {
        float tx = x * scaleFactor + offsetX;
        float ty = y * scaleFactor + offsetY;
        return new float[]{tx, ty};
    }
}
