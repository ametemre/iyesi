package com.kurmez.iyesi.utilities.Ai.delegate;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

import org.tensorflow.lite.Interpreter;

public class TFLiteInputPreprocessor {
    private TFLiteInputMapper mapper;
    private Interpreter interpreter;
    // Ölçek + Padding: Model input boyutuna uygun bitmap oluştur
    public TFLiteInputPreprocessor(TFLiteInputMapper mapper, Interpreter interpreter) {
        this.interpreter = interpreter;
        this.mapper = mapper;

    }

    public static Bitmap scaleAndPadBitmap(Bitmap input, int dstW, int dstH) {
        if (input == null) return null;
        Bitmap output = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.BLACK);
        return output;
    }// ---------------Bitmap'i Tensor'a çevir (ör. float[1][H][W][3])
    public static float[][][][] bitmapToInputTensor(Bitmap bmp) {
        if (bmp == null) return null;
        int w = bmp.getWidth(), h = bmp.getHeight();
        float[][][][] tensor = new float[1][h][w][3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = bmp.getPixel(x, y);
                tensor[0][y][x][0] = ((p >> 16) & 0xFF) / 255f;
                tensor[0][y][x][1] = ((p >> 8) & 0xFF) / 255f;
                tensor[0][y][x][2] = (p & 0xFF) / 255f;
            }
        }
        return tensor;
    }
}
