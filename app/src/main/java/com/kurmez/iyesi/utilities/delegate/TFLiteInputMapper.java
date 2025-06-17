package com.kurmez.iyesi.utilities.delegate;

import android.content.Context;
import android.graphics.RectF;
import android.util.Log;

import com.kurmez.iyesi.utilities.Ai.Ai;
import com.kurmez.iyesi.utilities.Ai.Detection;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Kamera görüntüsünü model input’una uygun boyut ve oranlara ölçekleyip,
 * merkezde olacak şekilde konumlar (letterbox mantığı).
 * Aynı zamanda, tespit edilen bounding box ve noktaları da modele uygun şekilde dönüştürür.
 */
public class TFLiteInputMapper {
    private int frameWidth;
    private int frameHeight;
    private int inputWidth;
    private int inputHeight;
    public Mat inputMat;
    Mat resized = new Mat();
    public float[][][][] inputTensor;
    //public Tensor inputTensor;
    public float[][][][] inputArray;
    TFLiteModelInspector inspector;
    Interpreter interpreter;
    Threading threading;
    private float scaleFactor;
    private int offsetX;
    private int offsetY;
    private static final String TAG = "Threading";

    public TFLiteInputMapper(Ai ai, Mat frame, Context context/*int frameWidth, int frameHeight, int inputWidth, int inputHeight*/) {
        try {
            if (frame == null) {
                Log.e(TAG, "TFLiteInputMapper: kaynak frame null!");
                return;
            }
            if (frame.empty()) {
                Log.e(TAG, "TFLiteInputMapper: kaynak frame.empty()!");
                return;
            }
        } catch (Exception e) {

            throw new RuntimeException(e);
        }
        Log.i(TAG, "TFLiteInputMapper initialized...");
        this.inputHeight = ai.getInputHeight();
        this.inputWidth = ai.getInputWidth();
        this.frameHeight = frame.height();
        this.frameWidth = frame.width();
       // Ölçek faktörünü hesapla
        this.scaleFactor = Math.min(
                (float)inputWidth  / frameWidth,
                (float)inputHeight / frameHeight
        );

        this.inputMat = map(frame);
        this.inputTensor = tensor(inputMat);
        //this.interpreter = ai.getVideoInterpereter();
        //threading = new Threading();
        //inspector = new TFLiteModelInspector(toByteBuffer(inputArray),threading.initGpuDelegate(context));
        Log.d(TAG,inspector.processVideoInput(inputTensor).toString());
        /*
        // Ölçeklenmiş görüntünün yeni boyutları
        int scaledW = Math.round(frameWidth  * scaleFactor);
        int scaledH = Math.round(frameHeight * scaleFactor);
        // Padding miktarlarını hesapla
        this.offsetX = (inputWidth  - scaledW) / 2;
        this.offsetY = (inputHeight - scaledH) / 2;
        this.inputMat = map(frame);
        interpreter = ai.getVideoInterpereter();
        threading = new Threading();
        inspector = new TFLiteModelInspector(toByteBuffer(inputArray),threading.initGpuDelegate(context));
        Log.d(TAG,inspector.processVideoInput(inputArray).toString());*/
        //processVideoInput()
        //interpreter.run(toByteBuffer(inputArray), objectOutput );

    }
    /**
     * frame’i modelin girdi boyutuna ölçekler ve kenarlardan pad ekler.
     */
    public Mat map(Mat frame) {
        try {
            /*
            // 1) Ölçekle
            Size newSize = new Size(
                    Math.round(this.frameWidth  * this.scaleFactor),
                    Math.round(this.frameHeight * this.scaleFactor)
            );
            */

            Size newSize = new Size(inputWidth,inputHeight);
            //resized = inputMat;
            safeResize(frame,this.resized,newSize);
            //Imgproc.resize(frame, resized, newSize);

            // 2) Pad ekle
            Mat output = new Mat();
            Core.copyMakeBorder(
                    resized,
                    output,
                    offsetY,                                    // top
                    inputHeight - resized.rows() - offsetY,     // bottom
                    offsetX,                                    // left
                    inputWidth  - resized.cols() - offsetX,     // right
                    Core.BORDER_CONSTANT,
                    new Scalar(0,0,0)                           // siyah pad
            );
            //inputArray = toByteBuffer(tensor(output));
            return output;  // artık tam (inputWidth x inputHeight)
        } catch (Exception e) {
            Log.e(TAG,e.toString());
            throw new RuntimeException(e);
        }
    }
    /**
     * src ve dst null ya da boş değilse resize işlemini yapar.
     * @param src  Kaynak Mat
     * @param dst  Çıktı Mat (mutlaka new Mat() ile oluşturulmuş olmalı)
     * @param size Hedef boyut
     * @return     true -> resize yapıldı, false -> hata atlandı
     */
    private boolean safeResize(Mat src, Mat dst, Size size) {
        if (src == null) {
            Log.e(TAG, "safeResize: src Mat null");
            return false;
        }
        if (src.empty()) {
            Log.e(TAG, "safeResize: src Mat empty");
            return false;
        }
        if (dst == null) {
            Log.e(TAG, "safeResize: dst Mat null");
            return false;
        }
        try {
            Imgproc.resize(src, dst, size);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "safeResize: resize sırasında hata", e);
            return false;
        }
    }
    public ByteBuffer toByteBuffer(float[][][][] arr) {
        int B = 1, H = inputHeight, W = inputWidth, C = 3;
        ByteBuffer buf = ByteBuffer.allocateDirect(4 * B * H * W * C)
                .order(ByteOrder.nativeOrder());
        for (int b = 0; b < B; b++) {
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    for (int c = 0; c < C; c++) {
                        buf.putFloat(arr[b][y][x][c]);
                    }
                }
            }
        }
        buf.rewind();
        return buf;
    }
    public float[][][][] tensor(Mat frame) {
        int frameW = frame.width();
        int frameH = frame.height();
/*
        // 1. Ölçek faktörünü hesapla
        float scale = Math.min((float) inputWidth / frameW, (float) inputHeight / frameH);
        int newW = Math.round(frameW * scale);
        int newH = Math.round(frameH * scale);

        // 2. Resize
        Mat resized = new Mat();
        Imgproc.resize(frame, resized, new Size(newW, newH));
*/
        // 3. Padding için boş mat oluştur ve ortala
        //Mat padded = Mat.zeros(inputHeight, inputWidth, frame.type());
        //int dx = (inputWidth-frameW)/2;//(inputWidth - newW) / 2;
        //int dy = (inputHeight-frameH)/2;//(inputHeight - newH) / 2;
        //resized.copyTo(padded.rowRange(dy, dy + newH).colRange(dx, dx + newW));

        // 4. float[1][H][W][3] tensor dizisi oluştur
        float[][][][] inputTensor = new float[1][frameH][frameW][3];
        for (int y = 0; y < frameH; y++) {
            for (int x = 0; x < frameW; x++) {
                double[] bgr = frame.get(y, x);
                // BGR → RGB ve normalize [0,1]
                inputTensor[0][y][x][0] = (float) (bgr[2] / 255.0);
                inputTensor[0][y][x][1] = (float) (bgr[1] / 255.0);
                inputTensor[0][y][x][2] = (float) (bgr[0] / 255.0);
            }
        }

        return inputTensor;
    }
    /** Ölçek ve paddingi frame vs input oranlarına göre hesaplar */
    private void calculate() {
/*         float frameRatio = (float) frameWidth / frameHeight;
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
        }*/
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
