package com.kurmez.iyesi.utilities.delegate;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class TFLiteInputPreprocessor {
    private final TFLiteInputMapper mapper;
    ByteBuffer inBuf;
    ByteBuffer outBuf;
    // Ölçek + Padding: Model input boyutuna uygun bitmap oluştur
    public TFLiteInputPreprocessor(TFLiteInputMapper mapper) {
        this.mapper = mapper;

    }

    public static Bitmap scaleAndPadBitmap(Bitmap input, int dstW, int dstH) {
        if (input == null) return null;
        int srcW = input.getWidth(), srcH = input.getHeight();
        //TFLiteInputMapper mapper = new TFLiteInputMapper(srcW, srcH, dstW, dstH);
        Bitmap output = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888);
        //Bitmap scaled = Bitmap.createScaledBitmap(input, mapper.getScaledWidth(), mapper.getScaledHeight(), true);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.BLACK);
        //canvas.drawBitmap(scaled, mapper.getOffsetX(), mapper.getOffsetY(), null);
        //scaled.recycle();
        return output;
    }
    // Bitmap'i Tensor'a çevir (ör. float[1][H][W][3])

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
    // Gerekirse: Bitmap'i doğrudan ByteBuffer'a çevir

    // public static ByteBuffer bitmapToByteBuffer(Bitmap bmp) { ... }
    public float[][][][] map(Mat inputFrame) {
        int w      = mapper.getScaledWidth();
        int h      = mapper.getScaledHeight();
        int modelW = w + 2 * mapper.getOffsetX();
        int modelH = h + 2 * mapper.getOffsetY();

        // 1. Resize ve RGBA→RGB
        Mat resized = new Mat();
        Imgproc.resize(inputFrame, resized, new Size(w, h));
        Imgproc.cvtColor(resized, resized, Imgproc.COLOR_RGBA2RGB);

        // 2. Padding ile model boyutuna oturt
        Mat padded = Mat.zeros(modelH, modelW, CvType.CV_8UC3);
        resized.copyTo(
                padded.submat(
                        mapper.getOffsetY(),
                        mapper.getOffsetY() + h,
                        mapper.getOffsetX(),
                        mapper.getOffsetX() + w
                )
        );

        // 3. byte[] ile veriyi oku
        byte[] data = new byte[modelW * modelH * 3];
        padded.get(0, 0, data);

        // 4. float tensor'a çevir
        float[][][][] result = new float[1][modelH][modelW][3];
        for (int y = 0; y < modelH; y++) {
            for (int x = 0; x < modelW; x++) {
                int base = (y * modelW + x) * 3;
                // byte → unsigned int → float
                result[0][y][x][0] = (data[base]     & 0xFF) / 255.0f;
                result[0][y][x][1] = (data[base + 1] & 0xFF) / 255.0f;
                result[0][y][x][2] = (data[base + 2] & 0xFF) / 255.0f;
            }
        }

        // Temizlik
        resized.release();
        padded.release();

        return result;
    }
    public float[][][] convert2DTo3D(float[][] input) {
        float[][][] output = new float[input.length][1][input[0].length];
        for (int i = 0; i < input.length; i++) {
            System.arraycopy(input[i], 0, output[i][0], 0, input[i].length);
        }
        return output;
    }
    public void inputBuffer(int height, int width){
        int batch = 1, h = 224, w = 224, c = 3;
        if (height ==h || width ==w) {
            this.inBuf = ByteBuffer.allocateDirect(batch * h * w * c * 4).order(ByteOrder.nativeOrder());
            this.outBuf = ByteBuffer.allocateDirect(batch * 120 * 4).order(ByteOrder.nativeOrder());
        }
    }
    public static Bitmap resizeBitmap(Bitmap bmp, int maxSize) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        float scale = (float)maxSize / Math.max(w, h);
        return Bitmap.createScaledBitmap(bmp, (int)(w*scale), (int)(h*scale), true);
    }
    public static Bitmap cropBitmap(Bitmap src, RectF box) {
        /** BBox ile Bitmap crop */

        int left = Math.max(0, Math.round(box.left));
        int top = Math.max(0, Math.round(box.top));
        int right = Math.min(src.getWidth(), Math.round(box.right));
        int bottom = Math.min(src.getHeight(), Math.round(box.bottom));
        if (left >= right || top >= bottom) return null;
        return Bitmap.createBitmap(src, left, top, right-left, bottom-top);
    }
}
