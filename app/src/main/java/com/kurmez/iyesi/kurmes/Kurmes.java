package com.kurmez.iyesi.kurmes;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.kurmez.iyesi.utilities.Ai.Detection;
import com.kurmez.iyesi.utilities.Ai.Threading;
import com.kurmez.iyesi.utilities.Terminator;
import com.kurmez.iyesi.utilities.helper.Actions;
import com.kurmez.iyesi.utilities.helper.Permissions;
import com.kurmez.iyesi.R;

import org.opencv.android.CameraBridgeViewBase;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
// ve kendi Detection/Ai class’ınızın import’ları

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.opencv.android.CameraActivity;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.MatOfRect;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.label.Category;

import java.nio.BufferUnderflowException;
import java.util.ArrayList;

import java.util.List;

import java.util.Collections;
import java.util.concurrent.RejectedExecutionException;

import com.kurmez.iyesi.utilities.Ai.Ai;
import com.kurmez.iyesi.utilities.MiniFabs;


public class Kurmes extends CameraActivity implements CvCameraViewListener2 {
    private static final String TAG = "Kurmes";
    private CameraBridgeViewBase mOpenCvCameraView;
    private Mat rgb, gray;
    // Kurmes.java içinde class başında
    public static final int DETECTION_INPUT_SIZE = 640;  // 640→320
    private static final int SKIP_FRAMES = 5;             // her 2. frame’de bir çalıştır
    private int frameCount = 0;
    private Interpreter interpreter;
    public Ai aiKedi, aiKopek, aiKurt, aiKarga, aiContent, ai;
    private MiniFabs miniFabs;
    private Actions actions;
    public enum State {
        KEDI, KOPEK, KURT, KARGA,
        IDLE, FACE_DETECTION, OBJECT_DETECTION, TRACKING, CAPTURE,TEST
    }
    public State currentState = State.IDLE;
    Threading threading = new Threading();
    private final List<float[]> soundBuffer = new ArrayList<>();
    private final List<float[][][]> videoBuffer = new ArrayList<float[][][]>();
    private final int SOUND_THRESHOLD = 5;
    private final int VIDEO_THRESHOLD = 5;
    private Detection detectionRunner;
    // Sound labels for each species model (fill in actual labels)
    private static final String[] KEDI_SOUNDS  = {"meow", "purr"};
    private static final String[] KOPEK_SOUNDS = {"bark", "growl"};
    private static final String[] KURT_SOUNDS  = {"howl", "snarl"};
    private static final String[] KARGA_SOUNDS = {"caw", "squawk"};
    private static final int REQUEST_IMAGE_CAPTURE = 1; // Request code for capturing a photo
    private List<Bitmap> photoList = new ArrayList<>(); // List to store captured images
    private FloatingActionButton fabDraggable, fabSound;
    private Terminator terminator = null;
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
    //private boolean cleanUp = false;
    public boolean isPredicting = false;
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

        // Permissions
        requestAudioPermissions();
        requestStoragePermission();
        checkAudioPermission();
        checkAndRequestPermissions();

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
            if (!isPredicting) {
                if (miniFabs.getSelectedFab() != null) {
                    ai = actions.performSelectedAction(miniFabs.getSelectedFab());
                    if (ai != null) {
                        interpreter = ai.getVideoInterpreter();
                        detectionRunner = new Detection(
                                ai, interpreter, this,
                                0, 0f, 0f, 0f, 0f, 0f, miniFabs.getSelectedFab().toString()
                        );
                        terminator =new Terminator(ai.getExecutor(),ai.getVideoInterpreter(),ai.getSoundInterpreter(),ai.getGpuDelegate(),videoBuffer,soundBuffer,this);
                        isPredicting = true;
                    }
                } else {
                    Toast.makeText(this, "Önce bir miniFAB seçin", Toast.LENGTH_SHORT).show();
                }
                try {

                } catch (Exception e) {
                    Log.w("FAB", "Terminator is not created");
                    isPredicting = true;
                    throw new RuntimeException(e);
                }
                startPrediction();
            }
            else if (terminator != null){
                terminator.onStopButtonClicked(v);
                isPredicting = false;
                ai = null;
                Toast.makeText(this, "Operasyonlar durduruldu", Toast.LENGTH_SHORT).show();
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
    public void startPrediction(){

    }


    @Override
    public void onCameraViewStarted(int width, int height) {
        rgb = new Mat();
        gray = new Mat();
        rects = new MatOfRect();
        //mRgba = new Mat(height, width, CvType.CV_8UC4);
        Log.i(TAG, "Camera view started: " + width + "x" + height);
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

        threading.availableCPU();

    }
    @Override
    public void onCameraViewStopped() {
        if (rgb != null) {
            rgb.release();
            gray.release();
            rects.release();
            updateCameraStatus("Camera Stopped.");
        }
        threading.availableCPU();
        threading.availableGPU();
        threading.availableGPUThreads();

    }                                                         //done
    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        //Mat rgba = inputFrame.rgba();
        rgb = inputFrame.rgba();
        gray =inputFrame.gray();
        Mat frame = new Mat();;
        try {
            // Normal yol: doğrudan RGBA al
            frame = inputFrame.rgba();
        } catch (BufferUnderflowException e) {
            Log.w(TAG, "rgba() buffer underflow, gray→RGBA dönüşümü yapılıyor", e);
            // Fallback: gray’dan alın ve RGBA’ya çevir
            Mat gray = inputFrame.gray();
            Imgproc.cvtColor(gray, frame, Imgproc.COLOR_GRAY2RGBA);
        }
        // —— YAKLAŞIM A: Asenkron çizim (Detection.java içindeki handleObjectDetection)
        // Bu metot kendi içinde SKIP_FRAMES kontrolü yapar, predictVideo çağırır
        // ve runOnUiThread içinde çizimi gerçekleştirir.
        try {
            // Önce null kontrolleriyle atlamayı sağlıyoruz
            if (rgb == null || ai == null) {
                Log.w(TAG, "Detection atlandı: rgb veya ai null");
            } else {
                new Thread(() -> {
                    detectionRunner.handleRT(rgb, ai);
                }).start();
            }
        } catch (RejectedExecutionException e) {
            // ThreadPool kapanıyorsa veya queue dolduysa atla
            Log.w(TAG, "Detection görevi reddedildi, atlanıyor", e);
        } catch (BufferUnderflowException e) {
            // Fallback dönüşümünde problem olduysa atla
            Log.w(TAG, "Buffer underflow oluştu, atlanıyor", e);
        } catch (NullPointerException e) {
            // Başka bir null durumunu güvenli atlamak için
            Log.w(TAG, "Beklenmeyen NPE, atlanıyor", e);
        } catch (Exception e) {
            // Diğer tüm hatalar burada yakalanır ve atlanır
            Log.e(TAG, "Detection sırasında beklenmedik hata, atlanıyor", e);
        }

        /* —— YAKLAŞIM B: Tam senkron parse + çizim
        // 1) Bitmap’e çevir
        Bitmap bmp = Bitmap.createBitmap(frame.cols(), frame.rows(),
                                         Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(frame, bmp);

        // 2) Ölçekle
        Bitmap resized = Detection.resizeBitmap(bmp, INPUT_SIZE);

        // 3) ByteBuffer’a yaz
        int pixelCount = INPUT_SIZE * INPUT_SIZE;
        ByteBuffer bb = ByteBuffer.allocateDirect(4 * pixelCount * 3)
                                  .order(ByteOrder.nativeOrder());
        int[] pixels = new int[pixelCount];
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
        for (int i = 0; i < pixelCount; i++) {
            int p = pixels[i];
            bb.putFloat(((p >> 16) & 0xFF) / 255f);
            bb.putFloat(((p >>  8) & 0xFF) / 255f);
            bb.putFloat(( p        & 0xFF) / 255f);
        }
        bb.rewind();

        // 4) Ham çıktıyı al
        float[][][] rawOut = new float[1][84][8400];
        interpreter.run(bb, rawOut);

        // 5) Detection listesi oluştur
        List<Detection> dets = detectionRunner.parseDetections(rawOut);

        // 6) Mat üzeri çizim
        for (Detection d : dets) {
            Point tl = new Point(d.x1, d.y1);
            Point br = new Point(d.x2, d.y2);
            Imgproc.rectangle(frame, tl, br, new Scalar(0,255,0), 2);
            Imgproc.putText(frame, d.label, new Point(d.x1, d.y1 - 10),
                            Imgproc.FONT_HERSHEY_SIMPLEX, 0.5,
                            new Scalar(255,255,255), 2);
        }
        */

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
}