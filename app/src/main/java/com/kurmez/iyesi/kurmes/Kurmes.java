package com.kurmez.iyesi.kurmes;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.kurmez.iyesi.Founded;
import com.kurmez.iyesi.Login;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.Welcome;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.VelocityTracker;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.ScaleAnimation;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.opencv.android.CameraActivity;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.android.JavaCamera2View;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;
import org.tensorflow.lite.Interpreter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Kurmes extends CameraActivity implements CvCameraViewListener2, View.OnTouchListener {
    private static final int REQUEST_IMAGE_CAPTURE = 1; // Request code for capturing a photo
    private List<Bitmap> photoList = new ArrayList<>(); // List to store captured images
    private FloatingActionButton fabDraggable;
    private float dX, dY;
    private boolean isDragging = false;
    private boolean isPressed = false;
    private FirebaseAuth mAuth;
    private Handler handler = new Handler();
    private static final String TAG = "KurmesActivity";
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    //private JavaCamera2View cameraView; // Using JavaCamera2View
    private TextView labelText;
    private TextView cameraStatusText;
    private Mat mRgba; // RGBA frame
    //imported---------------------------------
    private int lastAction;
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
    private FrameLayout rootLayout;
    private VelocityTracker velocityTracker = null;
    private JavaCamera2View cameraView;
    private long pressStartTime;
    private boolean isLongPressTriggered = false;
    private final int LONG_PRESS_THRESHOLD = 2000; // 2 seconds
    private final int DRAG_THRESHOLD = 20; // Minimum movement to consider a drag
    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "called kurmes onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_kurmes);

        checkAndRequestPermissions();

        mOpenCvCameraView = (CameraBridgeViewBase) findViewById(R.id.kurmes_camera_view);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        labelText = findViewById(R.id.label_text);
        cameraStatusText = findViewById(R.id.camera_status_text);

        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully");
        } else {
            Log.e(TAG, "OpenCV initialization failed!");
            (Toast.makeText(this, "OpenCV initialization failed!", Toast.LENGTH_LONG)).show();
            return;
        }
        // Initialize UI components
        //cameraView = findViewById(R.id.kurmes_camera_view);
        //FloatingActionButton fabDraggable = findViewById(R.id.fab_draggable);
        mAuth = FirebaseAuth.getInstance();
        fabDraggable = findViewById(R.id.fab_draggable);
        rootLayout = findViewById(android.R.id.content);

        setupDraggableFAB();
        setupButtonActions();
/*
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
        }*/
        // Drag functionality for the floating button
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
                //mOpenCvCameraView.setOnTouchListener(Kurmes.this);
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
    public boolean onPrepareOptionsMenu(Menu menu) {
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

                @SuppressLint("StaticFieldLeak")
                @Override
                protected void onPreExecute() {
                    calibrationProgress = new ProgressDialog(Kurmes.this);
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
                    (Toast.makeText(Kurmes.this, resultMessage, Toast.LENGTH_SHORT)).show();

                    if (mCalibrator.isCalibrated()) {
                        CalibrationResult.save(Kurmes.this,
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
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == RESULT_OK && data != null) {
            // Retrieve the captured image
            Bitmap imageBitmap = (Bitmap) data.getExtras().get("data");

            if (imageBitmap != null) {
                photoList.add(imageBitmap); // Add photo to the list
                navigateToFoundedActivity(); // Navigate to the next screen
            }
        } else if (requestCode == REQUEST_IMAGE_CAPTURE) {
            Toast.makeText(this, "Photo capture cancelled", Toast.LENGTH_SHORT).show();
        }
    }
    @Override
    protected List<? extends CameraBridgeViewBase> getCameraViewList() {
        return Collections.singletonList(mOpenCvCameraView);
    }//Essential For Camera
    private void openCamera() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
        } else {
            Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show();
        }
    }
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
    @SuppressLint("ClickableViewAccessibility")
    private void setupDraggableFAB() {
        fabDraggable.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dX = v.getX() - event.getRawX();
                    dY = v.getY() - event.getRawY();
                    isDragging = false;
                    isLongPressTriggered = false;
                    pressStartTime = System.currentTimeMillis();
                    if (velocityTracker == null) {
                        velocityTracker = VelocityTracker.obtain();
                    } else {
                        velocityTracker.clear();
                    }
                    velocityTracker.addMovement(event);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float moveX = event.getRawX() + dX;
                    float moveY = event.getRawY() + dY;
                    if (Math.abs(moveX - v.getX()) > DRAG_THRESHOLD || Math.abs(moveY - v.getY()) > DRAG_THRESHOLD) {
                        isDragging = true;
                    }
                    velocityTracker.addMovement(event);
                    velocityTracker.computeCurrentVelocity(1000); // Calculate speed in pixels per second
                    float newX = event.getRawX() + dX;
                    float newY = event.getRawY() + dY;
                    v.setX(newX);
                    v.setY(newY);
                    v.animate().x(moveX).y(moveY).setDuration(0).start();
                    return true;
                case MotionEvent.ACTION_UP:
                    velocityTracker.addMovement(event);
                    velocityTracker.computeCurrentVelocity(1000);
                    if (!isDragging) {
                        if ((System.currentTimeMillis() - pressStartTime) < LONG_PRESS_THRESHOLD) {
                            openCamera();
                        } else {
                            handleLongClick();
                        }
                    } else {
                        // Apply momentum-based gravity effect
                        float velocityY = velocityTracker.getYVelocity();
                        float velocityX = velocityTracker.getXVelocity();
                        animateMomentumGravity(v, velocityX, velocityY);
                    }
                    return true;
                default:
                    return false;
            }
        });
    }
    private void setupButtonActions() {
        fabDraggable.setOnClickListener(v -> {
            openCamera();
            //startActivity(new Intent(Kurmes.this, Founded.class));
        });
    }
    private void handleLongClick() {
        animateButtonPress();
        if (mAuth.getCurrentUser() != null) {
            startActivity(new Intent(Kurmes.this, Welcome.class));
            finish();
        } else {
            startActivity(new Intent(Kurmes.this, Login.class));
            finish();
        }
    }
    private void animateButtonPress() {
        fabDraggable.setEnabled(false);

        // Create shadow effect
        Animation scaleDown = new ScaleAnimation(
                1f, 0.9f, 1f, 0.9f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f);
        scaleDown.setDuration(500);
        scaleDown.setFillAfter(true);

        Animation fadeOut = new AlphaAnimation(1f, 0.6f);
        fadeOut.setDuration(2000);

        fabDraggable.startAnimation(scaleDown);
        fabDraggable.startAnimation(fadeOut);

        handler.postDelayed(() -> {
            fabDraggable.clearAnimation();
            fabDraggable.setEnabled(true);
            isPressed = false;
        }, 2000);
    }
    private void animateMomentumGravity(View v, float velocityX, float velocityY) {
        float screenHeight = rootLayout.getHeight();
        float screenWidth = rootLayout.getWidth();

        // Calculate projected landing position based on velocity
        float projectedX = v.getX() + (velocityX * 0.2f); // Multiply for "throw" effect
        float projectedY = v.getY() + (velocityY * 0.2f);

        // Ensure it doesn't go off-screen
        projectedX = Math.max(0, Math.min(projectedX, screenWidth - v.getWidth()));
        projectedY = Math.min(screenHeight - v.getHeight(), projectedY);

        // Animate movement with bounce effect
        ValueAnimator animatorX = ValueAnimator.ofFloat(v.getX(), projectedX);
        ValueAnimator animatorY = ValueAnimator.ofFloat(v.getY(), projectedY);

        animatorX.setInterpolator(new DecelerateInterpolator());
        animatorY.setInterpolator(new DecelerateInterpolator());

        animatorX.setDuration(500);
        animatorY.setDuration(500);

        animatorX.addUpdateListener(animation -> v.setX((float) animation.getAnimatedValue()));
        animatorY.addUpdateListener(animation -> v.setY((float) animation.getAnimatedValue()));

        animatorX.start();
        animatorY.start();
    }
    private void checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        } else {
            initializeCamera();
        }
    }//Essential For Camera
    private void navigateToFoundedActivity() {
        Intent intent = new Intent(this, Founded.class);
        intent.putParcelableArrayListExtra("photos", new ArrayList<>(photoList)); // Pass the photos
        startActivity(intent);
    }
    /*
    private void setupButtonActions() {
        fabDraggable.setOnClickListener(v -> {
            if (!isPressed) {
                isPressed = true;
                animateButtonPress();
                handler.postDelayed(() -> {
                    startActivity(new Intent(Kurmes.this, Founded.class));
                }, 2000);
            }
        });

        fabDraggable.setOnLongClickListener(v -> {
            if (mAuth.getCurrentUser() != null) {
                startActivity(new Intent(Kurmes.this, Welcome.class));
            } else {
                startActivity(new Intent(Kurmes.this, Login.class));
            }
            return true;
        });
    }
        private void animateButtonPress() {
                fabDraggable.setEnabled(false);
                fabDraggable.setColorFilter(Color.DKGRAY);

                Animation fadeOut = new AlphaAnimation(1f, 0.6f);
                fadeOut.setDuration(2000);
                fabDraggable.startAnimation(fadeOut);

                handler.postDelayed(() -> {
                    fabDraggable.clearColorFilter();
                    fabDraggable.setEnabled(true);
                    isPressed = false;
                }, 2000);
            }
            private void setupDraggableFAB() {
                fabDraggable.setOnTouchListener((v, event) -> {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            dX = v.getX() - event.getRawX();
                            dY = v.getY() - event.getRawY();
                            isDragging = false;
                            return true;

                        case MotionEvent.ACTION_MOVE:
                            v.animate()
                                    .x(event.getRawX() + dX)
                                    .y(event.getRawY() + dY)
                                    .setDuration(0)
                                    .start();
                            isDragging = true;
                            return true;

                        case MotionEvent.ACTION_UP:
                            if (!isDragging) {
                                v.performClick();
                            }
                            return true;

                        default:
                            return false;
                    }
                });
            }
    private void setupButtonActions() {
        fabDraggable.setOnClickListener(v -> {
            if (!isPressed) {
                isPressed = true;
                animateButtonPress();
                handler.postDelayed(() -> {
                    startActivity(new Intent(Kurmes.this, Founded.class));
                }, 2000);
            }
        });

        fabDraggable.setOnLongClickListener(v -> {
            if (mAuth.getCurrentUser() != null) {
                startActivity(new Intent(Kurmes.this, Welcome.class));
            } else {
                startActivity(new Intent(Kurmes.this, Login.class));
            }
            return true;
        });
    }

    private void animateButtonPress() {
        fabDraggable.setEnabled(false);

        // Create shadow effect
        Animation scaleDown = new ScaleAnimation(
                1f, 0.9f, 1f, 0.9f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f);
        scaleDown.setDuration(500);
        scaleDown.setFillAfter(true);

        Animation fadeOut = new AlphaAnimation(1f, 0.6f);
        fadeOut.setDuration(2000);

        fabDraggable.startAnimation(scaleDown);
        fabDraggable.startAnimation(fadeOut);

        handler.postDelayed(() -> {
            fabDraggable.clearAnimation();
            fabDraggable.setEnabled(true);
            isPressed = false;
        }, 2000);
    }
    private void setupDraggableFAB() {
        fabDraggable.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dX = v.getX() - event.getRawX();
                    dY = v.getY() - event.getRawY();
                    isDragging = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    v.animate()
                            .x(event.getRawX() + dX)
                            .y(event.getRawY() + dY)
                            .setDuration(0)
                            .start();
                    isDragging = true;
                    return true;

                case MotionEvent.ACTION_UP:
                    if (!isDragging) {
                        v.performClick();
                    }
                    return true;

                default:
                    return false;
            }
        });
    }*/
}

