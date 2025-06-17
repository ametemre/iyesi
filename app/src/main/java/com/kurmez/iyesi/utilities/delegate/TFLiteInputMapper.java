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
    private static final String TAG = "Mapper";

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
        //this.inputTensor = inputMat;
        //this.inputTensor = tensor(inputMat);
        this.inputBitmap = matToBitmap(inputMat);
        //this.interpreter = ai.getVideoInterpereter();
        //threading = new Threading();
        this.inputTensor = tensor(inputMat);
        //inspector = new TFLiteModelInspector(modelBuffer, gpuOptions);
        //Log.d(TAG,inspector.processVideoInput(inputTensor).toString());
        Log.d(TAG,inputTensor.toString());
    }


    public Mat map() {
        try {
            Size newSize = new Size(inputWidth,inputHeight);
            safeResize(inputFrame,resized,newSize);
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
    private Bitmap matToBitmap(Mat mat) {
        Bitmap bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(mat, bitmap);
        Log.d(TAG,"Bitmap values h:" + mat.height() +"w:" + mat.width());
        return bitmap;
    }    // ------------------------------------------------Yardımcı metod: Mat -> Bitmap dönüşümü
    public float[][][][] tensor(Mat frame) {
        // 1) Null/empty kontrolü
        if (frame == null || frame.empty()) {
            Log.e(TAG, "tensor: input frame null veya empty!");
            return new float[0][][][];  // ya da null dönebilirsiniz
        }

        // 2) frame'i resize etmek için geçici Mat
        Mat resized = new Mat();
        Size targetSize = new Size(inputWidth, inputHeight);
        if (!safeResize(frame, resized, targetSize)) {
            Log.e(TAG, "tensor: frame resize edilemedi");
            resized.release();
            return new float[0][][][];
        }

        // 3) Normalize edilmiş tensor dizisini oluştur
        int H = resized.height();
        int W = resized.width();
        float[][][][] inputTensor = new float[1][H][W][3];

        // 4) Piksel değerlerini BGR→RGB [0,1] aralığına çevir
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double[] px = resized.get(y, x);
                // OpenCV'de bazen 4 kanallı geliyorsa, alpha'yı atıyoruz
                if (px.length >= 3) {
                    inputTensor[0][y][x][0] = (float)(px[2] / 255.0);  // R
                    inputTensor[0][y][x][1] = (float)(px[1] / 255.0);  // G
                    inputTensor[0][y][x][2] = (float)(px[0] / 255.0);  // B
                } else {
                    // beklenmeyen kanal sayısı
                    inputTensor[0][y][x][0] = inputTensor[0][y][x][1] = inputTensor[0][y][x][2] = 0f;
                }
            }
        }

        // 5) Geçici Mat'i serbest bırak
        resized.release();
        return inputTensor;
    } //-------------------------------------------------Yardımcı metod: Mat -> float[][][][] dönüşümü
    private boolean safeResize(Mat src, Mat dst, Size size) {
        /**
         * src ve dst null ya da boş değilse resize işlemini yapar.
         * @param src  Kaynak Mat
         * @param dst  Çıktı Mat (mutlaka new Mat() ile oluşturulmuş olmalı)
         * @param size Hedef boyut
         * @return     true -> resize yapıldı, false -> hata atlandı
         */
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
    }//---------------------------------Yardımcı metod: size dönüşümü



    public float getScaleFactor() { return scaleFactor; }
    public int getOffsetX() { return offsetX; }
    public int getOffsetY() { return offsetY; }
    public int getScaledWidth() { return Math.round(frameWidth * scaleFactor); }
    public int getScaledHeight() { return Math.round(frameHeight * scaleFactor); }

}
