package com.kurmez.iyesi.utilities.delegate;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import com.kurmez.iyesi.utilities.Ai.Ai;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.tensorflow.lite.Interpreter;

/**
 * Kamera görüntüsünü model input’una uygun boyut ve oranlara ölçekleyip,
 * merkezde olacak şekilde konumlar (letterbox mantığı).
 * Aynı zamanda, tespit edilen bounding box ve noktaları da modele uygun şekilde dönüştürür.
 */
public class TFLiteInputMapper {
    public Bitmap inputBitmap;
    private int frameWidth;
    private int frameHeight;
    private int inputWidth;
    private int inputHeight;
    public Mat inputMat;
    public Mat inputFrame;
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
        this.inputFrame = frame;
        //this.inspector = new TFLiteModelInspector(toByteBuffer(inputArray),threading.initGpuDelegate(context));
       // Ölçek faktörünü hesapla
        this.scaleFactor = Math.min(
                (float)inputWidth  / frameWidth,
                (float)inputHeight / frameHeight
        );

        this.inputMat = map();
        this.inputTensor = tensor(inputMat);
        this.inputBitmap = matToBitmap(map());
        //this.interpreter = ai.getVideoInterpereter();
        //threading = new Threading();

        Log.d(TAG,inspector.processVideoInput(inputTensor).toString());
    }
    // Yardımcı metod: Mat -> Bitmap dönüşümü
    private Bitmap matToBitmap(Mat mat) {
        Bitmap bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(mat, bitmap);
        Log.d(TAG,"Bitmap values h:" + mat.height() +"w:" + mat.width());
        return bitmap;
    }
    public Mat map() {
        try {
            Size newSize = new Size(inputWidth,inputHeight);
            safeResize(inputFrame,this.resized,newSize);
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
    public float[][][][] tensor(Mat frame) {
        int frameW = frame.width();
        int frameH = frame.height();
        // float[1][H][W][3] tensor dizisi oluştur
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
