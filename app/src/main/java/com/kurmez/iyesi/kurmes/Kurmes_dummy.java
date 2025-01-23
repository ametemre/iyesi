package com.kurmez.iyesi.kurmes;


import android.Manifest;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.kurmez.iyesi.R;

import org.opencv.android.CameraActivity;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.android.JavaCamera2View;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfRect;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class Kurmes_dummy extends CameraActivity implements CvCameraViewListener2, OnTouchListener {
    private static final String TAG = "KurmesActivity";
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    //private JavaCamera2View cameraView; // Using JavaCamera2View
    private TextView labelText;
    private TextView cameraStatusText;
    private Mat mRgba; // RGBA frame
    //imported---------------------------------
    private CameraBridgeViewBase mOpenCvCameraView;
    public CameraCalibrator mCalibrator;
    private OnCameraFrameRender mOnCameraFrameRender;
    private Menu mMenu;
    private int mWidth;
    private int mHeight;
    //imported---------------------------------
    private CascadeClassifier catFaceDetector;
    // TensorFlow Lite Interpreter
    private Interpreter tflite;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "called kurmes onCreate");
        super.onCreate(savedInstanceState);

        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully");
        } else {
            Log.e(TAG, "OpenCV initialization failed!");
            (Toast.makeText(this, "OpenCV initialization failed!", Toast.LENGTH_LONG)).show();
            return;
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_kurmes);


        mOpenCvCameraView = (CameraBridgeViewBase) findViewById(R.id.kurmes_camera_view);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);

        // Initialize UI components
        //cameraView = findViewById(R.id.kurmes_camera_view);
        labelText = findViewById(R.id.label_text);
        cameraStatusText = findViewById(R.id.camera_status_text);

        if (mOpenCvCameraView != null) {
            mOpenCvCameraView.setVisibility(CameraBridgeViewBase.VISIBLE);
            mOpenCvCameraView.setCvCameraViewListener(this);
        } else {
            Log.e(TAG, "Camera View is null! Check XML layout.");
            updateCameraStatus("Camera View Initialization Failed!");
        }

        // Request camera permissions
        checkAndRequestPermissions();
        // Haar Cascade yükleme
        try {
            InputStream is = getResources().openRawResource(R.raw.haarcascade_frontalcatface);
            File cascadeDir = getDir("cascade", Context.MODE_PRIVATE);
            File mCascadeFile = new File(cascadeDir, "haarcascade_frontalcatface.xml");
            FileOutputStream os = new FileOutputStream(mCascadeFile);

            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            is.close();
            os.close();

            catFaceDetector = new CascadeClassifier(mCascadeFile.getAbsolutePath());
            if (catFaceDetector.empty()) {
                catFaceDetector = null;
            }
            cascadeDir.delete();
        } catch (IOException e) {
            Log.e(TAG, "Haar Cascade yüklenemedi.", e);
        }
        // TensorFlow Lite modelini yükleme
        try {
            //tflite = new Interpreter(loadModelFile(this, "mobilenet_v2.tflite"));
            int[] inputShape = tflite.getInputTensor(0).shape(); // Örn: [1, 224, 224, 3]
            DataType inputType = tflite.getInputTensor(0).dataType(); // Örn: UINT8
            Log.d(TAG, "Input Shape: " + Arrays.toString(inputShape));
            Log.d(TAG, "Input Type: " + inputType);

            //DataType inputType = tflite.getInputTensor(0).dataType();
            Log.d(TAG, "Model Input Shape: " + Arrays.toString(inputShape));
            Log.d(TAG, "Model Input Type: " + inputType);
        } catch (Exception e) {
            Log.e(TAG, "TFLite model yüklenemedi", e);
        }
    }
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        Log.d(TAG, "onTouch invoked");
        mCalibrator.addCorners();
        return false;
    }
    @Override
    public void onCameraViewStarted(int width, int height) {
        mRgba = new Mat(height, width, CvType.CV_8UC4);
        Log.d(TAG, "Camera view started: " + width + "x" + height);
        updateCameraStatus("Camera Started.");
        if (mWidth != width || mHeight != height) {
            mWidth = width;
            mHeight = height;
            mCalibrator = new CameraCalibrator(mWidth, mHeight);
            if (CalibrationResult.tryLoad(this, mCalibrator.getCameraMatrix(), mCalibrator.getDistortionCoefficients())) {
                mCalibrator.setCalibrated();
            } else {
                if (mMenu != null && !mCalibrator.isCalibrated()) {
                    mMenu.findItem(R.id.preview_mode).setEnabled(false);
                }
            }
            mOnCameraFrameRender = new OnCameraFrameRender(new CalibrationFrameRender(mCalibrator));
        }
    }
    @Override
    public void onCameraViewStopped() {
        if (mRgba != null) {
            mRgba.release();
        }
        updateCameraStatus("Camera Stopped.");
    }
    @Override
    public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
        mRgba = inputFrame.rgba();
        Mat grayscale = new Mat();
        Log.d(TAG, "Processing camera frame...");
        Imgproc.cvtColor(mRgba, grayscale, Imgproc.COLOR_RGBA2GRAY);

/*
        if (catFaceDetector != null) {
            MatOfRect catFaces = new MatOfRect();
            catFaceDetector.detectMultiScale(grayscale, catFaces, 1.1, 2, 0,
                    new Size(100, 100), new Size());

            for (Rect rect : catFaces.toArray()) {
                Imgproc.rectangle(mRgba, rect.tl(), rect.br(), new Scalar(0, 255, 0, 255), 2);
                // Tahmin için kırpılmış yüz verisi hazırlanır
                Mat faceROI = grayscale.submat(rect);
                byte[][][][] input = preprocessFace(faceROI);
                float[][] output = new float[1][3]; // Çıkış boyutunu modelinize göre ayarlayın
                tflite.run(input, output);

                String label = interpretPrediction(output[0]);
                Imgproc.putText(mRgba, label, rect.tl(), Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, new Scalar(255, 255, 255), 2);

                //float[][] output = new float[1][3]; // Çıkış boyutunu modelinize göre ayarlayın
                tflite.run(input, output);

                //String label = interpretPrediction(output[0]);
                Imgproc.putText(mRgba, label, rect.tl(), Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, new Scalar(255, 255, 255), 2);
                //byte[] output = new byte[1];
                tflite.run(input, output);

                //float[] normalizedOutput = convertOutput(output); // Eğer dönüşüm gerekiyorsa

                tflite.run(input, output);

                // Tahmini etikete çevir ve ekrana yazdır
                //String label = interpretPrediction(normalizedOutput);
                Imgproc.putText(mRgba, label, rect.tl(), Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, new Scalar(255, 255, 255), 2);
            }
        }
*/ //AI generated Code for image recognition (gives overload to gpu & crashes)
        //return mOnCameraFrameRender.render(inputFrame);
        return mRgba; // Return the raw RGBA frame
    } //Essential For Camera
    @Override
    protected void onResume() {
        super.onResume();
        if (OpenCVLoader.initDebug()) {
            Log.d(TAG, "OpenCV loaded successfully.");
            if (mOpenCvCameraView != null) {
                mOpenCvCameraView.enableView();
                updateCameraStatus("Camera View Resumed.");
                mOpenCvCameraView.setOnTouchListener(Kurmes_dummy.this);
            }
        } else {
            Log.e(TAG, "OpenCV loading failed on resume.");
            updateCameraStatus("OpenCV Initialization Failed.");
        }
    }
    @Override
    protected void onPause() {
        super.onPause();
        if (mOpenCvCameraView != null) {
            mOpenCvCameraView.disableView();
            updateCameraStatus("Camera View Paused.");
        }
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mOpenCvCameraView != null) {
            mOpenCvCameraView.disableView();
            updateCameraStatus("Camera View Destroyed.");
        }
    }
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.calibration, menu);
        mMenu = menu;
        return true;
    }
    @Override
    public boolean onPrepareOptionsMenu (Menu menu) {
        super.onPrepareOptionsMenu(menu);
        menu.findItem(R.id.preview_mode).setEnabled(true);
        if (mCalibrator != null && !mCalibrator.isCalibrated()) {
            menu.findItem(R.id.preview_mode).setEnabled(false);
        }
        return true;
    }
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.calibration) {
            mOnCameraFrameRender =
                    new OnCameraFrameRender(new CalibrationFrameRender(mCalibrator));
            item.setChecked(true);
            return true;
        } else if (item.getItemId() == R.id.undistortion) {
            mOnCameraFrameRender =
                    new OnCameraFrameRender(new UndistortionFrameRender(mCalibrator));
            item.setChecked(true);
            return true;
        } else if (item.getItemId() == R.id.comparison) {
            mOnCameraFrameRender =
                    new OnCameraFrameRender(new ComparisonFrameRender(mCalibrator, mWidth, mHeight, getResources()));
            item.setChecked(true);
            return true;
        } else if (item.getItemId() == R.id.calibrate) {
            final Resources res = getResources();
            if (mCalibrator.getCornersBufferSize() < 2) {
                (Toast.makeText(this, res.getString(R.string.more_samples), Toast.LENGTH_SHORT)).show();
                return true;
            }

            mOnCameraFrameRender = new OnCameraFrameRender(new PreviewFrameRender());
            new AsyncTask<Void, Void, Void>() {
                private ProgressDialog calibrationProgress;

                @Override
                protected void onPreExecute() {
                    calibrationProgress = new ProgressDialog(Kurmes_dummy.this);
                    calibrationProgress.setTitle(res.getString(R.string.calibrating));
                    calibrationProgress.setMessage(res.getString(R.string.please_wait));
                    calibrationProgress.setCancelable(false);
                    calibrationProgress.setIndeterminate(true);
                    calibrationProgress.show();
                }

                @Override
                protected Void doInBackground(Void... arg0) {
                    mCalibrator.calibrate();
                    return null;
                }

                @Override
                protected void onPostExecute(Void result) {
                    calibrationProgress.dismiss();
                    mCalibrator.clearCorners();
                    mOnCameraFrameRender = new OnCameraFrameRender(new CalibrationFrameRender(mCalibrator));
                    String resultMessage = (mCalibrator.isCalibrated()) ?
                            res.getString(R.string.calibration_successful)  + " " + mCalibrator.getAvgReprojectionError() :
                            res.getString(R.string.calibration_unsuccessful);
                    (Toast.makeText(Kurmes_dummy.this, resultMessage, Toast.LENGTH_SHORT)).show();

                    if (mCalibrator.isCalibrated()) {
                        CalibrationResult.save(Kurmes_dummy.this,
                                mCalibrator.getCameraMatrix(), mCalibrator.getDistortionCoefficients());
                    }
                }
            }.execute();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }
    @Override
    protected List<? extends CameraBridgeViewBase> getCameraViewList() {
        return Collections.singletonList(mOpenCvCameraView);
    }//Essential For Camera
    private void checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        } else {
            initializeCamera();
        }
    }//Essential For Camera
    private void initializeCamera() {
        boolean success = OpenCVLoader.initDebug();
        if (success) {
            Log.d(TAG, "OpenCV initialized successfully.");
            if (mOpenCvCameraView != null) {
                mOpenCvCameraView.enableView();
                updateCameraStatus("Camera Enabled.");
            }
        } else {
            Log.e(TAG, "OpenCV initialization failed.");
            Toast.makeText(this, "OpenCV initialization failed.", Toast.LENGTH_SHORT).show();
        }
    }//Essential For Camera
    private void updateCameraStatus(String status) {
        if (cameraStatusText != null) {
            cameraStatusText.setText("Camera Status: " + status);
        }
        Log.d(TAG, status);
    }//Essential For Camera
    /*    private byte[][][][] preprocessFace(Mat face) {
            // Yüzü boyutlandır
            Mat resizedFace = new Mat();
            Imgproc.resize(face, resizedFace, new Size(224, 224));

            // 0-255 arasında değerler oluştur ve UINT8 formatına çevir
            resizedFace.convertTo(resizedFace, CvType.CV_8U);

            // 4D tensor yapısı oluştur
            byte[][][][] input = new byte[1][224][224][1]; // Tek renk kanalı için

            for (int i = 0; i < 224; i++) {
                for (int j = 0; j < 224; j++) {
                    input[0][i][j][0] = (byte) resizedFace.get(i, j)[0];
                }
            }
            return input;
        } //AI generated Code for image recognition (gives overload to gpu & crashes)
        private String interpretPrediction(float[] output) {
            // Tahmini sınıfa çevir
            String[] labels = {"Mutlu", "Üzgün", "Şaşkın"};
            int maxIndex = 0;
            for (int i = 1; i < output.length; i++) {
                if (output[i] > output[maxIndex]) {
                    maxIndex = i;
                }
            }
            return labels[maxIndex];
        }//AI generated Code for image recognition (gives overload to gpu & crashes)*/ //AI generated Code for image recognition (gives overload to gpu & crashes)
}
