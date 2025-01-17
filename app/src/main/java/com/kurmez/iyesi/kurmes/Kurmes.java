package com.kurmez.iyesi.kurmes;
import com.kurmez.iyesi.R;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfRect;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;
import org.tensorflow.lite.Interpreter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Collections;
import java.util.List;

public class Kurmes extends AppCompatActivity implements CvCameraViewListener2 {

    private static final String TAG = "KurmesActivity";
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;

    private TextView labelText;
    private TextView cameraStatusText;

    private Mat mRgba;

    private CameraBridgeViewBase mOpenCvCameraView;
    private CascadeClassifier catFaceDetector;
    private Interpreter tflite;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "called kurmes onCreate");

        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "OpenCV initialization failed!");
            Toast.makeText(this, "OpenCV initialization failed!", Toast.LENGTH_LONG).show();
            return;
        }

        setContentView(R.layout.activity_kurmes);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mOpenCvCameraView = findViewById(R.id.kurmes_camera_view);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);

        labelText = findViewById(R.id.label_text);
        cameraStatusText = findViewById(R.id.camera_status_text);

        checkAndRequestPermissions();
        loadHaarCascade();
        loadTFLiteModel();
    }

    private void updateCameraStatus(String status) {
        if (cameraStatusText != null) {
            cameraStatusText.setText("Camera Status: " + status);
        }
        Log.d(TAG, status);
    }

    private void checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        } else {
            initializeCamera();
        }
    }

    private void initializeCamera() {
        mOpenCvCameraView.enableView();
        updateCameraStatus("Camera Enabled.");
    }

    private void loadHaarCascade() {
        try (InputStream is = getResources().openRawResource(R.raw.haarcascade_frontalcatface)) {
            File cascadeDir = getDir("cascade", Context.MODE_PRIVATE);
            File mCascadeFile = new File(cascadeDir, "haarcascade_frontalcatface.xml");
            try (FileOutputStream os = new FileOutputStream(mCascadeFile)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
            }

            catFaceDetector = new CascadeClassifier(mCascadeFile.getAbsolutePath());
            if (catFaceDetector.empty()) {
                catFaceDetector = null;
            }
            cascadeDir.delete();
        } catch (IOException e) {
            Log.e(TAG, "Haar Cascade yüklenemedi.", e);
        }
    }

    private void loadTFLiteModel() {
        try {
            tflite = new Interpreter(loadModelFile(this, "mobilenet_v2.tflite"));
        } catch (IOException e) {
            Log.e(TAG, "TFLite model yüklenemedi", e);
        }
    }

    private MappedByteBuffer loadModelFile(Context context, String modelPath) throws IOException {
        try (InputStream is = context.getAssets().open(modelPath)) {
            File tempFile = File.createTempFile("temp", null, context.getCacheDir());
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }
            try (FileInputStream fis = new FileInputStream(tempFile)) {
                FileChannel fileChannel = fis.getChannel();
                return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size());
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initializeCamera();
        } else {
            Toast.makeText(this, "Camera permission is required!", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
        mRgba = new Mat(height, width, CvType.CV_8UC4);
        updateCameraStatus("Camera Started.");
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
        Imgproc.cvtColor(mRgba, grayscale, Imgproc.COLOR_RGBA2GRAY);

        if (catFaceDetector != null) {
            MatOfRect catFaces = new MatOfRect();
            catFaceDetector.detectMultiScale(grayscale, catFaces, 1.1, 2, 0, new Size(100, 100), new Size());

            for (Rect rect : catFaces.toArray()) {
                Imgproc.rectangle(mRgba, rect.tl(), rect.br(), new Scalar(0, 255, 0, 255), 2);
                Mat faceROI = grayscale.submat(rect);
                byte[][][][] input = preprocessFace(faceROI);
                float[][] output = new float[1][3];
                tflite.run(input, output);

                String label = interpretPrediction(output[0]);
                Imgproc.putText(mRgba, label, rect.tl(), Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, new Scalar(255, 255, 255), 2);
            }
        }
        return mRgba;
    }

    private byte[][][][] preprocessFace(Mat face) {
        Mat resizedFace = new Mat();
        Imgproc.resize(face, resizedFace, new Size(224, 224));
        resizedFace.convertTo(resizedFace, CvType.CV_8U);

        byte[][][][] input = new byte[1][224][224][1];
        for (int i = 0; i < 224; i++) {
            for (int j = 0; j < 224; j++) {
                input[0][i][j][0] = (byte) resizedFace.get(i, j)[0];
            }
        }
        return input;
    }

    private String interpretPrediction(float[] output) {
        String[] labels = {"Mutlu", "Üzgün", "Şaşkın"};
        int maxIndex = 0;
        for (int i = 1; i < output.length; i++) {
            if (output[i] > output[maxIndex]) {
                maxIndex = i;
            }
        }
        return labels[maxIndex];
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mOpenCvCameraView != null) {
            mOpenCvCameraView.enableView();
            updateCameraStatus("Camera View Resumed.");
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
    public boolean onTouchEvent(MotionEvent event) {
        return super.onTouchEvent(event);
    }

    @Override
    public List<? extends CameraBridgeViewBase> getCameraViewList() {
        return Collections.singletonList(mOpenCvCameraView);
    }
}
