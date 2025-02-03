package com.kurmez.iyesi.kurmes;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.kurmez.iyesi.Founded;
import com.kurmez.iyesi.Login;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.Welcome;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
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

import androidx.annotation.Nullable;
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
import org.opencv.core.MatOfRect;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;
import org.tensorflow.lite.Interpreter;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
    private Mat mRgba, rgb, gray; // RGBA frame
    MatOfRect rects;
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
    private CascadeClassifier cascadeClassifier;
    // TensorFlow Lite Interpreter
    private Interpreter tflite;
    private FrameLayout rootLayout;
    private VelocityTracker velocityTracker = null;
    private JavaCamera2View cameraView;
    private long pressStartTime;
    private boolean isLongPressTriggered = false;
    private final int LONG_PRESS_THRESHOLD = 2000; // 2 seconds
    private final int DRAG_THRESHOLD = 20; // Minimum movement to consider a drag
    private FloatingActionButton fabMain;
    private FloatingActionButton[] miniFabs = new FloatingActionButton[9];
    private boolean isFabExpanded = false;

    private float[][] fabPositions = new float[9][2]; // Stores positions of sub FABs
    private float mainFabX, mainFabY; // Stores main FAB's position
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
        mAuth = FirebaseAuth.getInstance();
        fabDraggable = findViewById(R.id.fab_main);
        fabMain = fabDraggable;
        miniFabs[0] = findViewById(R.id.fab_1);
        miniFabs[1] = findViewById(R.id.fab_2);
        miniFabs[2] = findViewById(R.id.fab_3);
        miniFabs[3] = findViewById(R.id.fab_4);
        miniFabs[4] = findViewById(R.id.fab_5);
        miniFabs[5] = findViewById(R.id.fab_6);
        miniFabs[6] = findViewById(R.id.fab_7);
        miniFabs[7] = findViewById(R.id.fab_8);
        miniFabs[8] = findViewById(R.id.fab_9);
        rootLayout = findViewById(android.R.id.content);

        setupDraggableFAB();
        DatabaseReference modelsRef = FirebaseDatabase.getInstance().getReference("models");
        modelsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                for (DataSnapshot modelSnapshot : dataSnapshot.getChildren()) {
                    String modelName = modelSnapshot.child("name").getValue(String.class);
                    String modelUrl = modelSnapshot.child("url").getValue(String.class);
                    createFabButton(modelName, modelUrl);
                }
            }
            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e("Firebase", "Failed to load models", databaseError.toException());
            }
        });
    }
    @Override
    public boolean onTouch(View v, MotionEvent event) {

        Log.d(TAG, "onTouch invoked");
        mCalibrator.addCorners();
        return false;
    }
    @Override
    public void onCameraViewStarted(int width, int height) {
        rgb = new Mat();
        gray = new Mat();
        rects = new MatOfRect();
        //mRgba = new Mat(height, width, CvType.CV_8UC4);
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
        rgb.release();
        gray.release();
        rects.release();
        if (mRgba != null) {
            mRgba.release();
        }
        updateCameraStatus("Camera Stopped.");
    }
    @Override
    public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
        InputStream inputStream = getResources().openRawResource(R.raw.lbpcascade_frontalface);
        int a=0;
        if (a == 1){

        }else if (a == 2){

        }else {

        }
        File file = new File(getDir("cascade",MODE_PRIVATE),"lbpcascade_frontalface.xml");
        rgb = inputFrame.rgba();
        //mRgba = inputFrame.rgba();
        Mat grayscale = new Mat();
        Log.d(TAG, "Processing camera frame...");
        Imgproc.cvtColor(rgb, grayscale, Imgproc.COLOR_RGBA2GRAY);

        activateDetector(file,inputStream);
        loadDetector(inputFrame.gray(), rects);

        return rgb; // Return the raw RGBA frame
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


                    moveMiniFabs(newX - mainFabX, newY - mainFabY);

                    mainFabX = newX;
                    mainFabY = newY;

                    v.animate().x(moveX).y(moveY).setDuration(0).start();
                    return true;
                case MotionEvent.ACTION_UP:
                    velocityTracker.addMovement(event);
                    velocityTracker.computeCurrentVelocity(1000);
                    if (!isDragging) {
                        if ((System.currentTimeMillis() - pressStartTime) < LONG_PRESS_THRESHOLD) {
                            if (mAuth.getCurrentUser() != null) {
                                toggleFabMenu();
                            } else {
                                navigateToFoundedActivity();
                            }
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
        scaleDown.setDuration(1500);
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
    private void toggleFabMenu() {
        if (isFabExpanded) {
            collapseFabMenu();
        } else {
            expandFabMenu();
        }
        isFabExpanded = !isFabExpanded;
    }
    private void expandFabMenu() {
        float radius = 800; // Distance from center FAB
        for (int i = 0; i < miniFabs.length; i++) {
            float angle = (float) (i * (2 * Math.PI / miniFabs.length)/3);
            float x = (float) (radius * Math.cos(angle));
            float y = (float) (radius * Math.sin(angle));
            if (x<0){

            }
            if (y<0){

            }

            fabPositions[i][0] = x;
            fabPositions[i][1] = y;

            miniFabs[i].setVisibility(View.VISIBLE);
            fabDraggable.setVisibility(View.GONE);
            AnimatorSet animSet = new AnimatorSet();
            animSet.playTogether(
                    ObjectAnimator.ofFloat(miniFabs[i], "x", mainFabX, x),
                    ObjectAnimator.ofFloat(miniFabs[i], "y", mainFabY, y),
                    ObjectAnimator.ofFloat(miniFabs[i], "alpha", 0f, 1f)
            );
            animSet.setInterpolator(new DecelerateInterpolator());
            animSet.setDuration(800);
            animSet.start();
        }
    }
    private void collapseFabMenu() {
        for (FloatingActionButton fab : miniFabs) {
            int i = 0;
            AnimatorSet animSet = new AnimatorSet();
            animSet.playTogether(
                    ObjectAnimator.ofFloat(miniFabs[i], "x", miniFabs[i].getX(), mainFabX),
                    ObjectAnimator.ofFloat(miniFabs[i], "y", miniFabs[i].getY(), mainFabY),
                    ObjectAnimator.ofFloat(miniFabs[i], "alpha", 1f, 0f)
            );
            animSet.setInterpolator(new DecelerateInterpolator());
            animSet.setDuration(1200);
            animSet.start();

            fab.setVisibility(View.GONE);
            fabDraggable.setVisibility(View.VISIBLE);


            final int index = i;
            animSet.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    miniFabs[index].setVisibility(View.GONE);
                }
            });
        }
    }
    private void moveMiniFabs(float deltaX, float deltaY) {
        for (int i = 0; i < miniFabs.length; i++) {
            miniFabs[i].setX(fabPositions[i][0] + deltaX);
            miniFabs[i].setY(fabPositions[i][1] + deltaY);
        }
    }
    private void loadDetector(Mat gray,MatOfRect rects){
        cascadeClassifier.detectMultiScale(gray,rects,1.1,2);
        for (Rect rect : rects.toList()){

            Mat submat = rgb.submat(rect);

            Imgproc.blur(submat,submat,new Size(10,10));
            Imgproc.rectangle(rgb,rect,new Scalar(0,255,0),10);
        }
    }
    private void activateDetector(File file, InputStream inputStream){
        mOpenCvCameraView.enableView();
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(file);

            byte[] data = new byte[4096];
            int read_bytes;

            while((read_bytes = inputStream.read(data)) != -1){
                fileOutputStream.write(data,0,read_bytes);
            }
            cascadeClassifier = new CascadeClassifier(file.getAbsolutePath());
            if (cascadeClassifier.empty()) cascadeClassifier=null;

            inputStream.close();
            fileOutputStream.close();
            file.delete();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (isFabExpanded){
                    collapseFabMenu();
                }
                Log.d("TouchEvent", "Screen touched at: X=" + event.getRawX() + " Y=" + event.getRawY());
                break;
            case MotionEvent.ACTION_MOVE:
                Log.d("TouchEvent", "Finger moved: X=" + event.getRawX() + " Y=" + event.getRawY());
                break;
            case MotionEvent.ACTION_UP:
                Log.d("TouchEvent", "Finger lifted");
                break;
        }
        return super.dispatchTouchEvent(event); // Allow other views to handle the touch
    }
    private void downloadAndLoadModel(String modelUrl) {
        StorageReference modelRef = FirebaseStorage.getInstance().getReferenceFromUrl(modelUrl);
        File localFile = new File(getFilesDir(), "model.tflite");

        modelRef.getFile(localFile).addOnSuccessListener(taskSnapshot -> {
            Log.d("Model", "Download complete");
            loadTFLiteModel(localFile.getAbsolutePath());
        }).addOnProgressListener(taskSnapshot -> {
            long bytesTransferred = taskSnapshot.getBytesTransferred();
            long totalBytes = taskSnapshot.getTotalByteCount();
            updateDownloadProgress((int) ((bytesTransferred * 100) / totalBytes));
        }).addOnFailureListener(e -> Log.e("Model", "Download failed", e));
    }
    private void createFabButton(String modelName, String modelUrl) {
        FloatingActionButton fab = new FloatingActionButton(this);
        fab.setImageResource(R.drawable.holder); // Set a default icon
        fab.setOnClickListener(v -> downloadAndLoadModel(modelUrl));
        rootLayout.addView(fab);
    }
    private void loadTFLiteModel(String modelPath) {
        try {
            Interpreter.Options options = new Interpreter.Options();
            tflite = new Interpreter(new File(modelPath), options);
            Log.d("TFLite", "Model loaded successfully!");
        } catch (Exception e) {
            Log.e("TFLite", "Error loading model", e);
        }
    }
    private void updateDownloadProgress(int progress) {
        //fabButton.setProgress(progress);  // Assume a custom FAB with progress tracking
    }
    /*private String predictMood(Mat inputFrame) {
        ByteBuffer inputBuffer = convertMatToByteBuffer(inputFrame);
        float[][] output = new float[1][5]; // Assuming 5 classes
        tflite.run(inputBuffer, output);
        return getMoodLabel(output);
    }
    private void startAudioRecording() {
        AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, BUFFER_SIZE);
        recorder.startRecording();
    }
    private String analyzeAudio(byte[] audioData) {
        ByteBuffer inputBuffer = ByteBuffer.wrap(audioData);
        float[][] output = new float[1][3]; // Assuming 3 sound categories
        tflite.run(inputBuffer, output);
        return getSoundLabel(output);
    }
    class AnimalContext {
        String species;
        String mood;
        String need;
        String soundAnalysis;
    }
    AnimalContext currentAnimal = new AnimalContext();
    private String getFinalDecision() {
        if (currentAnimal.species.equals("Dog") && currentAnimal.mood.equals("Sad") &&
                currentAnimal.need.equals("Food") && currentAnimal.soundAnalysis.equals("Whining")) {
            return "The dog is hungry and sad. Offer food!";
        }
        return "No clear interpretation.";
    }*/

}


