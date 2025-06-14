package com.kurmez.iyesi.utilities.Ai;

import android.graphics.RectF;

/**
 * Kamera görüntüsünü model input’una uygun boyut ve oranlara ölçekleyip,
 * merkezde olacak şekilde konumlar (letterbox mantığı).
 * Aynı zamanda, tespit edilen bounding box ve noktaları da modele uygun şekilde dönüştürür.
 */
public class TFLiteInputMapper {
    private final int frameWidth;
    private final int frameHeight;
    private final int inputWidth;
    private final int inputHeight;

    private float scaleFactor;
    private int offsetX;
    private int offsetY;

    public TFLiteInputMapper(int frameWidth, int frameHeight, int inputWidth, int inputHeight) {
        this.frameWidth  = frameWidth;
        this.frameHeight = frameHeight;
        this.inputWidth  = inputWidth;
        this.inputHeight = inputHeight;
        calculate();
    }

    /** Ölçek ve paddingi frame vs input oranlarına göre hesaplar */
    private void calculate() {
        float frameRatio = (float) frameWidth / frameHeight;
        float inputRatio = (float) inputWidth / inputHeight;
        if (frameRatio > inputRatio) {
            // Frame daha geniş → model yüksekliğine göre ölçekle
            scaleFactor = (float) inputHeight / frameHeight;
            int scaledWidth = Math.round(frameWidth * scaleFactor);
            offsetX = (inputWidth - scaledWidth) / 2;
            offsetY = 0;
        } else {
            // Frame daha yüksek/eşit → model genişliğine göre ölçekle
            scaleFactor = (float) inputWidth / frameWidth;
            int scaledHeight = Math.round(frameHeight * scaleFactor);
            offsetX = 0;
            offsetY = (inputHeight - scaledHeight) / 2;
        }
    }

    /** Ölçek katsayısı (frame→model) */
    public float getScaleFactor() { return scaleFactor; }

    /** X yönünde padding (px) */
    public int getOffsetX() { return offsetX; }

    /** Y yönünde padding (px) */
    public int getOffsetY() { return offsetY; }

    /** Yeniden boyutlandırılmış genişlik (px) */
    public int getScaledWidth() { return Math.round(frameWidth * scaleFactor); }

    /** Yeniden boyutlandırılmış yükseklik (px) */
    public int getScaledHeight() { return Math.round(frameHeight * scaleFactor); }

    /** Verilen RectF’i modele uygun şekilde çevirir (sol-üst, sağ-alt) */
    public RectF mapRect(RectF rect) {
        float[] topLeft     = mapPoint(rect.left, rect.top);
        float[] bottomRight = mapPoint(rect.right, rect.bottom);
        return new RectF(topLeft[0], topLeft[1], bottomRight[0], bottomRight[1]);
    }

    /** Verilen bir noktayı modele uygun şekilde dönüştürür */
    public float[] mapPoint(float x, float y) {
        float tx = x * scaleFactor + offsetX;
        float ty = y * scaleFactor + offsetY;
        return new float[]{tx, ty};
    }
}
