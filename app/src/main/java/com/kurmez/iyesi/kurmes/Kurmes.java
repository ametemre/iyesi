package com.kurmez.iyesi.kurmes;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.kurmez.iyesi.utilities.helper.Actions;
import com.kurmez.iyesi.utilities.helper.Permissions;
import com.kurmez.iyesi.sahiplendirme.Founded;
import com.kurmez.iyesi.Login;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.sahiplendirme.Welcome;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import android.graphics.Bitmap;
// ve kendi Detection/Ai class’ınızın import’ları

import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.Intent;
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

import org.opencv.android.CameraActivity;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.MatOfRect;
import org.tensorflow.lite.support.label.Category;

import java.nio.BufferUnderflowException;
import java.util.ArrayList;

import java.util.List;

import java.util.Collections;

import com.kurmez.iyesi.utilities.Ai.Ai;
import com.kurmez.iyesi.sokak.SokakActivity;
import com.kurmez.iyesi.utilities.MiniFabs;


public class Kurmes extends CameraActivity implements CvCameraViewListener2 {
    private static final String TAG = "Kurmes";
    private CameraBridgeViewBase mOpenCvCameraView;
    private Mat rgb, gray;
    // Kurmes.java içinde class başında
    public static final int DETECTION_INPUT_SIZE = 640;  // 640→320
    private static final int SKIP_FRAMES = 5;             // her 2. frame’de bir çalıştır
    private int frameCount = 0;

    public Ai aiKedi, aiKopek, aiKurt, aiKarga, aiContent;
    private MiniFabs miniFabs;
    private Actions actions;
    public enum State {
        KEDI, KOPEK, KURT, KARGA,
        IDLE, FACE_DETECTION, OBJECT_DETECTION, TRACKING, CAPTURE,TEST
    }
    public State currentState = State.IDLE;

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

    private FirebaseAuth mAuth;
    private Handler handler = new Handler();
    public static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
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
    private OnCameraFrameRender mOnCameraFrameRender;
    private Menu mMenu;
    private int mWidth;
    private int mHeight;
    private FrameLayout rootLayout;
    //--------------------------Creation
    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "called Kurmes onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_kurmes);
        Permissions permissions = new Permissions();


        // Firebase Auth
        mAuth = FirebaseAuth.getInstance();

        // OpenCV camera initialization
        mOpenCvCameraView = findViewById(R.id.kurmes_camera_view);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);
        mOpenCvCameraView.enableView();

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


        MiniFabs miniFabs = new MiniFabs(
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
        miniFabs.setupDraggableFAB(miniFabs, fabDraggable);
        Actions actions = new Actions(miniFabs, this, this /* or getApplicationContext() */ );
        // Wire each miniFAB to call selectFab() + your onFabClick logic
        for (FloatingActionButton fab : miniFabs.getFabs()) {
            fab.setOnClickListener(v -> {

                actions.onFabSelected(fab);
                // Highlight selection
            });
        }

        // Sound FAB click (if needed)
        fabSound.setOnClickListener(v -> {
            if (miniFabs.getSelectedFab() != null) {
                actions.performSelectedAction(miniFabs.getSelectedFab());
            } else {
                Toast.makeText(this, "Önce bir miniFAB seçin", Toast.LENGTH_SHORT).show();
            }
        });


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
        //Mat rgba = inputFrame.rgba();
        rgb = inputFrame.rgba();
        gray =inputFrame.gray();
        Mat frame;
        try {
            // Normal yol: doğrudan RGBA al
            frame = inputFrame.rgba();
        } catch (BufferUnderflowException e) {
            Log.w(TAG, "rgba() buffer underflow, gray→RGBA dönüşümü yapılıyor", e);
            // Fallback: gray’dan alın ve RGBA’ya çevir
            Mat gray = inputFrame.gray();
            frame = new Mat();
            Imgproc.cvtColor(gray, frame, Imgproc.COLOR_GRAY2RGBA);
        }
        switch (currentState) {
            case KEDI:
                //handleSpecies(rgb, aiKedi);
                break;
            case KOPEK:
                //handleSpecies(rgb, aiKopek);
                break;
            case KURT:
                //handleSpecies(rgb, aiKurt);
                break;
            case KARGA:
                //handleSpecies(rgb, aiKarga);
                break;
            case FACE_DETECTION:
                // Perform face detection
                //videoYapayZeka(getResources().openRawResource(R.raw.lbpcascade_frontalface), null);
                break;
            case OBJECT_DETECTION:
                if (aiContent != null) {
                    //handleObjectDetection(frame);
                }
                //handleObjectDetection(rgba);
                // Perform object detection
                // ...
                break;
            case CAPTURE:
                //capturePhoto(rgb);// Perform ImgCapture
                //navigateToFoundedActivity();
                // ...
                break;
            case TRACKING:
                // Perform tracking
                // ...
                break;
            case TEST:
                // Perform tracking
                // ...
                break;
            case IDLE:
                // Do nothing
                break;
            default:
                break;
        }

        //Log.d(TAG, "Processing camera frame...");
        //updateCameraStatus(currentState.toString() + "birde" + photoList.size());

        return frame; // Return the raw RGBA frame
    }               //Essential For Camera



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
    private void updateCameraStatus(String status) {
        runOnUiThread(() -> {
            if (cameraStatusText != null) {
                cameraStatusText.setText("Camera Status: " + status);
            }
            Log.d(TAG, status);
        });
    }//Essential For Camera
    public boolean cameraState(Boolean state){
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


}