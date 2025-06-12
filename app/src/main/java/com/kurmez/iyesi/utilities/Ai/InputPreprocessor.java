package com.kurmez.iyesi.utilities.Ai;

import android.graphics.Bitmap;

import org.opencv.core.Mat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class InputPreprocessor {
    private final Ai ai;

    public InputPreprocessor(Ai ai) {
        this.ai = ai;
    }

    public Bitmap matToScaledInputBitmap(Mat frame) {
        // TODO: gerçek implementasyonu ekleyin
        return Bitmap.createBitmap(ai.getInputWidth(), ai.getInputHeight(), Bitmap.Config.ARGB_8888);
    }

    public ByteBuffer bitmapToByteBuffer(Bitmap bmp) {
        int width = bmp.getWidth();
        int height = bmp.getHeight();
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * width * height * 3).order(ByteOrder.nativeOrder());
        int[] pixels = new int[width * height];
        bmp.getPixels(pixels, 0, width, 0, 0, width, height);

        for (int pixel : pixels) {
            buffer.putFloat(((pixel >> 16) & 0xFF) / 255f);
            buffer.putFloat(((pixel >> 8) & 0xFF) / 255f);
            buffer.putFloat((pixel & 0xFF) / 255f);
        }
        buffer.rewind();
        return buffer;
    }

    public float[][][][] bitmapToInputTensor(Bitmap bmp) {
        // Gerekli implementasyon
        return new float[1][ai.getInputHeight()][ai.getInputWidth()][3];
    }
}
