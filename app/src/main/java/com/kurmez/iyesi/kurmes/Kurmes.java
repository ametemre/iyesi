package com.kurmez.iyesi.kurmes;
import static com.kurmez.iyesi.kurmes.helper.TFLiteModelInspector.loadModelFile;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.kurmez.iyesi.sahiplendirme.Founded;
import com.kurmez.iyesi.Login;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.sahiplendirme.Welcome;

import android.graphics.Bitmap;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
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
import android.view.animation.ScaleAnimation;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.opencv.android.CameraActivity;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import org.opencv.core.MatOfRect;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.label.Category;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;

import java.util.List;

import java.util.Collections;

import com.kurmez.iyesi.kurmes.Ai.Ai;
import com.kurmez.iyesi.sokak.SokakActivity;
import com.kurmez.iyesi.utilities.MiniFabs;


public class Kurmes extends CameraActivity implements CvCameraViewListener2 {
    private static final String TAG = "Kurmes";
    private MiniFabs miniFabs;   // saha değişkeni
    private CameraBridgeViewBase mOpenCvCameraView;
    private Mat rgb, gray;

    private Ai aiKedi, aiKopek, aiKurt, aiKarga, aiContent;

    public enum State {
        KEDI, KOPEK, KURT, KARGA,
        IDLE, FACE_DETECTION, OBJECT_DETECTION, TRACKING, CAPTURE
    }
    private State currentState = State.IDLE;

    private final List<float[]> soundBuffer = new ArrayList<>();
    private final List<float[][][]> videoBuffer = new ArrayList<float[][][]>();
    private final int SOUND_THRESHOLD = 5;
    private final int VIDEO_THRESHOLD = 5;

    // Sound labels for each species model (fill in actual labels)
    private static final String[] KEDI_SOUNDS  = {"meow", "purr"};
    private static final String[] KOPEK_SOUNDS = {"bark", "growl"};
    private static final String[] KURT_SOUNDS  = {"howl", "snarl"};
    private static final String[] KARGA_SOUNDS = {"caw", "squawk"};
    private static final int REQUEST_IMAGE_CAPTURE = 1; // Request code for capturing a photo
    private List<Bitmap> photoList = new ArrayList<>(); // List to store captured images
    private FloatingActionButton fabDraggable, fabSound;
    private float dX, dY;
    private boolean isDragging = false;
    private FirebaseAuth mAuth;
    private Handler handler = new Handler();
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    //private JavaCamera2View cameraView; // Using JavaCamera2View
    protected TextView labelText;
    public void SetLabelText(String s){
        if (labelText != null) {
            labelText.setText(s);
        } else {
            Log.e("Kurmes", "labelText is not initialized yet.");
        }
    }
    private TextView cameraStatusText;
    MatOfRect rects;
    //imported---------------------------------
    //private DetectorFunction activeDetectorFunction = () -> videoYapayZeka(getResources().openRawResource(R.raw.lbpcascade_frontalface), null);
    public CameraCalibrator mCalibrator;
    private FloatingActionButton selectedFab = null; // Track the selected FAB
    private OnCameraFrameRender mOnCameraFrameRender;
    private Menu mMenu;
    private int mWidth;
    private int mHeight;
    private FrameLayout rootLayout;
    private VelocityTracker velocityTracker = null;
    private long pressStartTime;
    private final int LONG_PRESS_THRESHOLD = 2000; // 2 seconds
    private final int DRAG_THRESHOLD = 20; // Minimum movement to consider a drag
    //-------------------------------------------------------------------------------------------Fab
    private float mainFabX, mainFabY; // Stores main FAB's position
    //--------------------------Creation
    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "called Kurmes onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_kurmes);

        // Firebase Auth
        mAuth = FirebaseAuth.getInstance();

        // OpenCV camera initialization
        mOpenCvCameraView = findViewById(R.id.kurmes_camera_view);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);
        mOpenCvCameraView.enableView();
/*
        // Load TFLite models per species
        try {
            aiKedi  = new Ai(this, "kedi_sound.tflite",  "kedi_video.tflite");
            aiKopek = new Ai(this, "kopek_sound.tflite", "kopek_video.tflite");
            aiKurt  = new Ai(this, "kurt_sound.tflite",  "kurt_video.tflite");
            aiKarga = new Ai(this, "karga_sound.tflite", "karga_video.tflite");
            aiContent = new Ai(this, "karga_sound.tflite", "karga_video.tflite");
        } catch (IOException e) {
            Log.e(TAG, "Failed to load TFLite models", e);
        }
*/
        // Keep screen on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // UI elements
        labelText        = findViewById(R.id.label_text);
        cameraStatusText = findViewById(R.id.camera_status_text);
        fabDraggable     = findViewById(R.id.fab_main);
        fabSound         = findViewById(R.id.fab_Sound);
        rootLayout       = findViewById(android.R.id.content);

        // Instantiate MiniFabs helper and keep as field
        int[] miniFabIds = {
                R.id.fab_1, R.id.fab_2, R.id.fab_3,
                R.id.fab_4, R.id.fab_5, R.id.fab_6,
                R.id.fab_7, R.id.fab_8, R.id.fab_9
        };
        miniFabs = new MiniFabs(
                this,
                fabDraggable,
                fabSound,
                miniFabIds
        );

        // Apply initial teal/default colors
        miniFabs.applyDefaultColors();

        // Collapse on outside touch (camera view or root)
        View.OnTouchListener outsideListener = (v, ev) -> {
            return miniFabs.handleOutsideTouch(ev);
        };
        mOpenCvCameraView.setOnTouchListener(outsideListener);
        rootLayout.setOnTouchListener(outsideListener);

        // Set up draggable & expand/collapse behavior
        setupDraggableFAB(miniFabs);

        // Wire each miniFAB to call selectFab() + your onFabClick logic
        for (FloatingActionButton fab : miniFabs.getFabs()) {
            fab.setOnClickListener(v -> {
                // Highlight selection
                miniFabs.selectFab((FloatingActionButton)v);
                // Your existing FAB-action logic:
                onFabClick(v, (FloatingActionButton)v);
            });
        }

        // Sound FAB click (if needed)
        fabSound.setOnClickListener(v -> {
            Intent intent = new Intent(Kurmes.this, SokakActivity.class);
            startActivity(intent);
            // ... your existing recording start/stop ...
        });

        // Permissions
        requestAudioPermissions();
        requestStoragePermission();
        checkAudioPermission();
        checkAndRequestPermissions();
    }
    public Action onFabClick(View view ,FloatingActionButton clickedFab) {
        if (selectedFab == clickedFab) {
            // If clicking the same FAB, deselect it and set it back to Teal
            clickedFab.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#008080"))); // Teal
            miniFabs.resetIconColor(clickedFab); // Restore icon color
            selectedFab = null;
        } else {
            // Deselect previous FAB if there was one
            if (selectedFab != null) {
                selectedFab.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#008080"))); // Teal
                miniFabs.resetIconColor(selectedFab);
            }

            // Select new FAB and set to Red
            clickedFab.setBackgroundTintList(ColorStateList.valueOf(Color.RED)); // Red
            miniFabs.applyWhiteColorFilter(clickedFab); // Change icon to White
            selectedFab = clickedFab;
        }
        clickedFab.invalidate(); // Force UI refresh
        clickedFab.requestLayout(); // Ensure layout updates
        Log.d("FAB", "onFabClick called");
        Action action = null;
        if (view.getId() == R.id.fab_1) {
            action = this::actionOne;
            currentState = State.KEDI;
        } else if (view.getId() == R.id.fab_2) {
            action = this::actionTwo;
            currentState = State.KOPEK;
        } else if (view.getId() == R.id.fab_3) {
            action = this::actionThree;
            currentState = State.KURT;
        } else if (view.getId() == R.id.fab_4) {
            action = this::actionFour;
        } else if (view.getId() == R.id.fab_5) {
            action = this::actionFive;
        } else if (view.getId() == R.id.fab_6) {
            action = this::actionSix;
        } else if (view.getId() == R.id.fab_7) {
            action = this::actionSeven;
        } else if (view.getId() == R.id.fab_8) {
            action = this::actionEight;
        } else if (view.getId() == R.id.fab_9) {
            action = this::actionNine;
        }
        // Execute the function if not null
        if (action != null) {
            action.execute();
        } else {
            Log.w("FAB", "Unknown FAB clicked!");
        }
        return action;
    }
    //--------------------------Creation
    public void updateDetectedSounds(List<Category> detectedCategories) {
        LinearLayout detectedSoundsLayout = findViewById(R.id.detected_sounds_list);

        //detectedSoundsLayout.removeAllViews(); // Clear previous results

        for (Category category : detectedCategories) {
            float confidence = category.getScore();
            if (confidence > 0.79) { // Only show confidence > 79%
                TextView textView = new TextView(this);
                textView.setText(category.getDisplayName() + "---" + category.getLabel() + " - " + String.format("%.2f", confidence * 100) + "%");
                textView.setTextSize(16);
                textView.setTextColor(Color.WHITE);
                textView.setPadding(10, 10, 10, 10);

                detectedSoundsLayout.addView(textView);
            }
        }

        // If no high-confidence results, show "No strong detection"
        if (detectedSoundsLayout.getChildCount() == 0) {
            TextView noResultView = new TextView(this);
            noResultView.setText("No strong detections");
            noResultView.setTextSize(16);
            noResultView.setTextColor(Color.GRAY);
            noResultView.setPadding(10, 10, 10, 10);
            detectedSoundsLayout.addView(noResultView);
        }
    }
    //--------------------------Sound
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
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        Log.d("AvailableProcessors", "Number of available threads: " + availableProcessors);

    }
    @Override
    public void onCameraViewStopped() {
        if (rgb != null) {
            rgb.release();
            gray.release();
            rects.release();
            updateCameraStatus("Camera Stopped.");
        }
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        Log.d("AvailableProcessors", "Number of available threads: " + availableProcessors);

    }                                                         //done
    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {

        rgb = inputFrame.rgba();
        gray =inputFrame.gray();

        switch (currentState) {
            case KEDI:
                handleSpecies(rgb, aiKedi);
                break;
            case KOPEK:
                handleSpecies(rgb, aiKopek);
                break;
            case KURT:
                handleSpecies(rgb, aiKurt);
                break;
            case KARGA:
                handleSpecies(rgb, aiKarga);
                break;
            case FACE_DETECTION:
                // Perform face detection
                //videoYapayZeka(getResources().openRawResource(R.raw.lbpcascade_frontalface), null);
                break;
            case OBJECT_DETECTION:
                // Perform object detection
                // ...
                break;
            case CAPTURE:
                capturePhoto(rgb);// Perform ImgCapture
                navigateToFoundedActivity();
                // ...
                break;
            case TRACKING:
                // Perform tracking
                // ...
                break;
            case IDLE:
                // Do nothing
                break;
            default:
                break;
        }

        Log.d(TAG, "Processing camera frame...");
        updateCameraStatus(currentState.toString() + "birde" + photoList.size());

        return rgb; // Return the raw RGBA frame
    } //Essential For Camera
    @Override
    protected void onResume() {
        super.onResume();
        if (OpenCVLoader.initDebug()) {
            Log.d(TAG, "OpenCV loaded successfully.");
            if (mOpenCvCameraView != null) {
                updateCameraStatus("Camera View Resumed.");
                cameraState(true);
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
            cameraState(false);
        }

    }
    @Override
    protected void onDestroy() {
        // TFLite ve OpenCV kaynaklarını güvenle kapat
        if (aiKedi != null) {
            aiKedi.close();
            aiKedi = null;
        }

        if (aiKopek != null) {
            aiKopek.close();
            aiKopek = null;
        }

        if (aiKurt != null) {
            aiKurt.close();
            aiKurt = null;
        }

        if (aiKarga != null) {
            aiKarga.close();
            aiKarga = null;
        }

        if (mOpenCvCameraView != null) {
            mOpenCvCameraView.disableView();
            mOpenCvCameraView = null;
        }

        super.onDestroy();
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
        }

        else if (item.getItemId() == R.id.undistortion) {
            mOnCameraFrameRender =
                    new OnCameraFrameRender(new UndistortionFrameRender(mCalibrator));
            item.setChecked(true);
            return true;
        }

        else if (item.getItemId() == R.id.comparison) {
            mOnCameraFrameRender =
                    new OnCameraFrameRender(new ComparisonFrameRender(mCalibrator, mWidth, mHeight, getResources()));
            item.setChecked(true);
            return true;
        }

        else if (item.getItemId() == R.id.calibrate) {
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
        }
        else {
            return super.onOptionsItemSelected(item);
        }
    }
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // Eğer miniFabs boş değil ve dokunmayı işlediyse, burada false yerine true dönün:
        if (miniFabs != null && miniFabs.handleOutsideTouch(ev)) {
            return true;   // Event burada tüketildi
        }
        // Aksi takdirde normal akışı devam ettir
        return super.dispatchTouchEvent(ev);
    }
    @Override
    protected List<? extends CameraBridgeViewBase> getCameraViewList() {
        return Collections.singletonList(mOpenCvCameraView);
    }//Essential For Camera
    private boolean cameraState(Boolean state){
        if (state){
            if (mOpenCvCameraView != null) {
                mOpenCvCameraView.enableView();
                mOpenCvCameraView.setVisibility(View.VISIBLE);

                updateCameraStatus("Camera Enabled.");
            }
        }
        else {
            if (mOpenCvCameraView != null) {
                if (mOpenCvCameraView.isEnabled()) {
                    mOpenCvCameraView.disableView();
                    mOpenCvCameraView.setVisibility(View.GONE);

                    currentState = State.IDLE;
                    updateCameraStatus("Camera Paused.");
                }
            }
        }
        return state;
    }
    private void updateCameraStatus(String status) {
        runOnUiThread(() -> {
            if (cameraStatusText != null) {
                cameraStatusText.setText("Camera Status: " + status);
            }
            Log.d(TAG, status);
        });
    }//Essential For Camera
    @SuppressLint("ClickableViewAccessibility")
    private void setupDraggableFAB(MiniFabs miniFabs) {
        fabDraggable.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // Başlangıç pozisyonlarını ve zaman damgasını ayarla
                    dX = v.getX() - event.getRawX();
                    dY = v.getY() - event.getRawY();
                    mainFabX = v.getX();
                    mainFabY = v.getY();
                    isDragging = false;
                    pressStartTime = System.currentTimeMillis();
                    // VelocityTracker hazırla
                    if (velocityTracker == null) {
                        velocityTracker = VelocityTracker.obtain();
                    } else {
                        velocityTracker.clear();
                    }
                    velocityTracker.addMovement(event);
                    SetLabelText("Ready !");
                    return true;

                case MotionEvent.ACTION_MOVE:
                    // Yeni pozisyonu hesapla
                    float newX = event.getRawX() + dX;
                    float newY = event.getRawY() + dY;
                    // Sürükleme eşiğini kontrol et
                    if (Math.abs(newX - v.getX()) > DRAG_THRESHOLD ||
                            Math.abs(newY - v.getY()) > DRAG_THRESHOLD) {
                        isDragging = true;
                    }
                    // Hız takibi
                    velocityTracker.addMovement(event);
                    velocityTracker.computeCurrentVelocity(1000);
                    // FAB ve miniFAB’ları taşı
                    v.setX(newX);
                    v.setY(newY);
                    miniFabs.move(newX - mainFabX, newY - mainFabY);
                    mainFabX = newX;
                    mainFabY = newY;
                    return true;

                case MotionEvent.ACTION_UP:
                    velocityTracker.addMovement(event);
                    velocityTracker.computeCurrentVelocity(1000);
                    if (!isDragging) {
                        long pressDuration = System.currentTimeMillis() - pressStartTime;
                        if (pressDuration < LONG_PRESS_THRESHOLD) {
                            // Kısa tıklama: miniFAB menüsünü toggle et
                            miniFabs.toggle();
                        } else {
                            // Uzun basış
                            handleLongClick();
                        }
                    } else {
                        // Sürükleme sonrası momentumlu animasyon
                        float vx = velocityTracker.getXVelocity();
                        float vy = velocityTracker.getYVelocity();
                        miniFabs.animateMomentumGravity(v, vx, vy,rootLayout);
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
        } else {
            startActivity(new Intent(Kurmes.this, Login.class));
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
        }, 2000);
    }
    private void checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        } else {
            cameraState(true);
        }
    }//Essential For Camera
    private void requestStoragePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 200);
        }
    }
    private void requestAudioPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 200);
        }
    }
    private void checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 101);
        }
    }
    public static Bitmap resizeBitmap(Bitmap bitmap, int maxSize) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        float scale = (float) maxSize / Math.max(width, height);

        return Bitmap.createScaledBitmap(bitmap, (int) (width * scale), (int) (height * scale), true);
    }
    private void capturePhoto(Mat rgb) {
        if (rgb == null) {
            Log.e(TAG, "Error: rgb is null!");
        } else {
            Log.e(TAG, "rgb.cols(): " + rgb.cols() + ", rgb.rows(): " + rgb.rows());
        }

        if (rgb != null && !rgb.empty() && rgb.cols() > 0 && rgb.rows() > 0) {
            // Convert Mat to Bitmap
            requestStoragePermission();
            Bitmap bitmap = Bitmap.createBitmap(rgb.cols(), rgb.rows(), Bitmap.Config.ARGB_8888);
            org.opencv.android.Utils.matToBitmap(rgb, bitmap);

            Bitmap resizedBitmap = resizeBitmap(bitmap, 225);
            photoList.add(resizedBitmap);

            // Save the image to storage
            String filename = "Kurmes_Capture_" + System.currentTimeMillis() + ".jpg";
            File file = new File(getExternalFilesDir(null), filename);

            try (FileOutputStream out = new FileOutputStream(file)) {
                resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
                out.flush();
                Toast.makeText(this, "Photo saved: " + file.getAbsolutePath(), Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                Log.e(TAG, "Failed to save photo", e);
                Toast.makeText(this, "Failed to save photo", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "No valid image to capture", Toast.LENGTH_SHORT).show();
        }
    }
    private void navigateToFoundedActivity() {
        Intent intent = new Intent(this, Founded.class);
        intent.putParcelableArrayListExtra("photos", new ArrayList<>(photoList)); // Pass the photos
        startActivity(intent);
    }
    private void actionOne() {
        cameraState(true);
        CatSpeciesRecognition();
        Log.d("Action", "Action One Executed!");
    }
    private void actionTwo() {
        SetLabelText("denedik");
        DogSpeciesRecognition();
        Log.d("Action", "Action Two Executed!");
    }
    private void actionThree() {
        WolfSpeciesRecognition();
        Log.d("Action", "Action Three Executed!");
    }
    private void actionFour() {
        CrowSpeciesRecognition();
        Log.d("Action", "Action Four Executed!");
    }
    private void actionFive() {
        HawkSpeciesRecognition();
        Log.d("Action", "Action Five Executed!");
    }
    private void actionSix() {
        EagleSpeciesRecognition();
        Log.d("Action", "Action Six Executed!");
    }
    private void actionSeven() {
        KeklikSpeciesRecognition();
        Log.d("Action", "Action Seven Executed!");
    }
    private void actionEight() {
        PidgeonSpeciesRecognition();
        Log.d("Action", "Action Eight Executed!");
    }
    private void actionNine() {
        Log.d("Action", "Action Nine Executed!");
    }
    private void DogSpeciesRecognition(){
        //TFLiteModelInspector.main(null);
    }
    private void CatSpeciesRecognition(){}
    private void WolfSpeciesRecognition(){
        Interpreter tflite;
        try {
            MappedByteBuffer modelBuffer = loadModelFile(getAssets(), "yolov8n.tflite");
            tflite = new Interpreter(modelBuffer);
        } catch (IOException e) {
            Log.e(TAG, "Model yüklenirken hata", e);
        }

    }
    private void CrowSpeciesRecognition(){}
    private void HawkSpeciesRecognition(){}
    private void EagleSpeciesRecognition(){}
    private void KeklikSpeciesRecognition(){}
    private void PidgeonSpeciesRecognition(){}
    private void handleSpecies(Mat frame, Ai ai) {
        if (ai == null) {
            Log.w(TAG, "AI model is null—skipping inference for state " + currentState);
            return;
        }
        // video inference
        float[][][][] imgTensor = preprocessImage(frame);
        ai.predictVideo(imgTensor, videoOut -> {
            synchronized (videoBuffer) {
                videoBuffer.add(videoOut);
            }
        });

        // sound inference
        float[][] audioTensor = captureAudioFeatures();
        ai.predictSound(audioTensor, soundOut -> {
            synchronized (soundBuffer) {
                soundBuffer.add(soundOut);
            }
        });

        // combine when thresholds reached
        if (videoBuffer.size() >= VIDEO_THRESHOLD && soundBuffer.size() >= SOUND_THRESHOLD) {
            runOnUiThread(() -> matchPercepts(currentState, videoBuffer, soundBuffer));
            videoBuffer.clear();
            soundBuffer.clear();
        }
    }
    private void matchPercepts(State state, List<float[][][]> vidBuf, List<float[]> sndBuf) {
        switch (state) {
            case KEDI:
                float[] catSound = sndBuf.get(sndBuf.size() - 1);
                int idxCat = argmax(catSound);
                String catLabel = KEDI_SOUNDS[idxCat];
                Log.i(TAG, "Detected cat sound: " + catLabel);
                break;
            case KOPEK:
                float[] dogSound = sndBuf.get(sndBuf.size() - 1);
                int idxDog = argmax(dogSound);
                String dogLabel = KOPEK_SOUNDS[idxDog];
                Log.i(TAG, "Detected dog sound: " + dogLabel);
                break;
            case KURT:
                float[] wolfSound = sndBuf.get(sndBuf.size() - 1);
                int idxWolf = argmax(wolfSound);
                String wolfLabel = KURT_SOUNDS[idxWolf];
                Log.i(TAG, "Detected wolf sound: " + wolfLabel);
                break;
            case KARGA:
                float[] crowSound = sndBuf.get(sndBuf.size() - 1);
                int idxCrow = argmax(crowSound);
                String crowLabel = KARGA_SOUNDS[idxCrow];
                Log.i(TAG, "Detected crow sound: " + crowLabel);
                break;
            default:
                break;
        }
    }
    private int argmax(float[] array) {
        int maxIdx = 0;
        float maxVal = array[0];
        for (int i = 1; i < array.length; i++) {
            if (array[i] > maxVal) {
                maxVal = array[i];
                maxIdx = i;
            }
        }
        return maxIdx;
    }
    private float[][][][] preprocessImage(Mat frame) {
        // TODO: implement frame → tensor conversion
        return new float[1][224][224][3];
    }
    private float[][] captureAudioFeatures() {
        // TODO: implement audio capture → feature vector
        return new float[1][40];
    }
    interface Action {
        void execute();
    }
}