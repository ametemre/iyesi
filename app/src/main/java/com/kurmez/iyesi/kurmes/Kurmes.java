package com.kurmez.iyesi.kurmes;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.kurmez.iyesi.utilities.Ai.Detection;
//import com.kurmez.iyesi.utilities.Ai.OpenCV;
import com.kurmez.iyesi.utilities.Ai.OpenCV;
import com.kurmez.iyesi.utilities.Ai.SoundClassifier;
import com.kurmez.iyesi.utilities.Ai.VideoClassifier;
import com.kurmez.iyesi.utilities.delegate.TFLiteInputMapper;
import com.kurmez.iyesi.utilities.delegate.TFLiteInputPreprocessor;
import com.kurmez.iyesi.utilities.delegate.Threading;
import com.kurmez.iyesi.utilities.Helpers;
import com.kurmez.iyesi.utilities.RTPipeline;
import com.kurmez.iyesi.utilities.Terminator;
import com.kurmez.iyesi.utilities.helper.Actions;
import com.kurmez.iyesi.utilities.helper.Permissions;
import com.kurmez.iyesi.R;

import org.opencv.android.CameraBridgeViewBase;
import org.opencv.core.Mat;

import android.graphics.Bitmap;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.kurmez.iyesi.utilities.Ai.Ai;
import com.kurmez.iyesi.utilities.MiniFabs;

import org.opencv.android.CameraActivity;
import org.opencv.android.OpenCVLoader;

import org.tensorflow.lite.Interpreter;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.view.Menu;

import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.core.MatOfRect;
import org.tensorflow.lite.support.label.Category;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;


public class Kurmes extends CameraActivity implements CvCameraViewListener2 {
    private static final String TAG = "Kurmes";
    public Mat rgba;
    private final Object lock = new Object();
    private volatile Mat lastResult = null;
    public enum State {
        KEDI, KOPEK, KURT, KARGA,
        IDLE, FACE_DETECTION, OBJECT_DETECTION, TRACKING, CAPTURE,TEST
    }
    public State currentState = State.IDLE;

    private List<float[]> buffer = new ArrayList<>();
    private List<float[]> soundBuffer = new ArrayList<>();
    private List<float[][][]> videoBuffer = new ArrayList<>();
    private List<Bitmap> photoList = new ArrayList<>(); // List to store captured images

    // Sound labels for each species model (fill in actual labels)
    private static final String[] KEDI_SOUNDS  = {"meow", "purr"};
    private static final String[] KOPEK_SOUNDS = {"bark", "growl"};
    private static final String[] KURT_SOUNDS  = {"howl", "snarl"};
    private static final String[] KARGA_SOUNDS = {"caw", "squawk"};

    public void SetLabelText(String s){
        if (labelText != null) {
            labelText.setText(s);
        } else {
            Log.e("Kurmes", "labelText is not initialized yet.");
        }
    }
    //imported---------------------------------
    private FloatingActionButton fabMain, fabAction;
    private Detection detector;
    private Detection detectionRunner;
    private FirebaseAuth auth;
    public CameraCalibrator mCalibrator;
    private OnCameraFrameRender mOnCameraFrameRender;
    private Menu mMenu;
    private FirebaseAuth mAuth;
    private Terminator terminator = null;
    private TFLiteInputPreprocessor preprocessor;
    private CameraBridgeViewBase mOpenCvCameraView;
    private Interpreter interpreter;
    public Ai /*aiKedi, aiKopek, aiKurt, aiKarga, aiContent, */ai;
    //private OpenCV openCV;
    private RTPipeline pipeline;
    private MiniFabs miniFabs;
    private Actions actions;
    private VideoClassifier videoClassifier;
    private SoundClassifier soundClassifier;
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Threading threading = new Threading();
    //----------------------------------------------------------------------------------------------<<Creation
    //private boolean cleanUp = false;
    public boolean isPredicting = false;
    private boolean isRunning = false;
    private TextView labelText, statusText,cameraStatusText;
    public static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    private static final int REQUEST_IMAGE_CAPTURE = 1; // Request code for capturing a photo
    public static final int DETECTION_INPUT_SIZE = 640;  // 640→320
    private static final int SKIP_FRAMES = 5;             // her 2. frame’de bir çalıştır
    private final int SOUND_THRESHOLD = 5;
    private final int VIDEO_THRESHOLD = 5;
    private int mWidth, mHeight;
    private int frameCount = 0;
    public Mat rgb, gray,frame;
    private OpenCV      openCvUtil;
    MatOfRect rects;
    Mat overlaid;
    //----------------------------------------------------------------------------------------------<Creation
    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "called Kurmes onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_kurmes);
        // izinler
        new Permissions().requestAllPermissions(this);
        // Firebase Auth
        mAuth = FirebaseAuth.getInstance();
        // Keep screen on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // UI elements
        labelText        = findViewById(R.id.label_text);
        cameraStatusText = findViewById(R.id.camera_status_text);
        fabMain         = findViewById(R.id.fab_main);
        fabAction         = findViewById(R.id.fab_Sound);
        labelText.setText("init...");
        // 1) Ai ve pipeline başlat
        pipeline = new RTPipeline();
        //openCV = new OpenCV();
        pipeline.logGpuInfo();  // GPU bilgilerini loglamak istersen
        openCvUtil = new OpenCV();
        mOpenCvCameraView= findViewById(R.id.kurmes_camera_view);
        mOpenCvCameraView.setCvCameraViewListener(this);
        cameraState(true);
        // ----------------------------------------------------------------------------------------Instantiate MiniFabs helper and keep as field
        int[] miniFabIds = {
                R.id.fab_1, R.id.fab_2, R.id.fab_3,
                R.id.fab_4, R.id.fab_5, R.id.fab_6,
                R.id.fab_7, R.id.fab_8, R.id.fab_9
        };
        miniFabs = new MiniFabs(this, fabMain, fabAction, miniFabIds);
        miniFabs.setupDraggableFAB(miniFabs, fabMain);
        miniFabs.applyDefaultColors();

        actions = new Actions(miniFabs, this, this);
        labelText.setText("fabs...");
        // ----------------------------------------------------------------------------------------Instantiate MiniFabs helper and keep as field

        for (FloatingActionButton fab : miniFabs.getFabs()) {
            fab.setOnClickListener(v -> {
                actions.onFabSelected(fab);
                // Highlight selection
            });
        }

        labelText.setText("ready...");
        // fabAction: başlat/durdur
        fabAction.setOnClickListener(v -> {
            labelText.setText("standby...");
//            new Thread(() -> {
                if (!isRunning) {
                    // Kullanıcı model seçmeden başlatmak isterse
                    if (miniFabs.getSelectedFab() == null) {
                        runOnUiThread(() -> Helpers.showToastSafe(this,"Önce bir seçenek seçin"));
                        //runOnUiThread(() -> Toast.makeText(this, "Önce bir seçenek seçin", Toast.LENGTH_SHORT).show());
                        return;
                    }
                    executor.execute(() -> {
                        // Model yüklemesi ve fallback güvenliği
                        try {
                            ai = actions.performSelectedAction(miniFabs.getSelectedFab());
                            if (ai == null) {//-----------Dilkkat !
                                runOnUiThread(() -> Helpers.showToastSafe(this,"Model yükleme başarısız"));
                                //runOnUiThread(() -> Toast.makeText(this, "Model yükleme başarısız", Toast.LENGTH_SHORT).show());
                                return;
                            }
                            soundClassifier = new SoundClassifier(
                                    getApplicationContext(),
                                    ai.getPath(),
                                    0.3f,
                                    result -> runOnUiThread(() -> labelText.setText(result))
                            );
                            videoClassifier = new VideoClassifier(
                                    getApplicationContext(),
                                    ai,
                                    ai.getPath(),
                                    0.25f,  // probability threshold
                                    result -> runOnUiThread(() -> labelText.setText(result))
                            );
                            interpreter = ai.getVideoInterpreter();

                            try {

                                detectionRunner = new Detection(ai, interpreter, this, 0, 0, 0, 0, 0, 0, miniFabs.getSelectedFab().toString());

                            } catch (Exception e) {
                                Log.w("Detection Init Error", "Detection nesnesi oluşturulamadı", e);
                                runOnUiThread(() -> Helpers.showToastSafe(this,"Algılama nesnesi başlatılamadı"));
                                //runOnUiThread(() -> Toast.makeText(this, "Algılama nesnesi başlatılamadı", Toast.LENGTH_SHORT).show());
                                return;
                            }
                            //cameraState(true);
                            isRunning = true;
                            isPredicting = true;
                            runOnUiThread(() ->Helpers.showToastSafe(this,"AI Başlatıldı"));
                            runOnUiThread(() -> SetLabelText("Processing..."));

                            //runOnUiThread(() -> Toast.makeText(this, "AI Başlatıldı", Toast.LENGTH_SHORT).show());

                        } catch (Exception e) {
                            Log.e("AI Init Error", "Model başlatma hatası", e);
                            runOnUiThread(() -> Helpers.showToastSafe(this,"Model başlatma hatası: "));
                            //runOnUiThread(() -> Toast.makeText(this, "Model başlatma hatası: " + e.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    });
                } else {
                    // 1. Önce AI tahminlerini kapat
                    isPredicting = false;/*
                    // 2. Executor’ü kapat ve bitmesini bekle
                    if (!executor.isShutdown()) {
                        executor.shutdown();
                        try {
                            if (!executor.awaitTermination(1000, TimeUnit.MILLISECONDS)) {
                                executor.shutdownNow();
                            }
                        } catch (InterruptedException e) {
                            executor.shutdownNow();
                        }
                    }*/
                    // 3. En son kamera kısmını kapat
                    //cameraState(false);
                    if (terminator != null) terminator.shutdown();
                    if (ai != null) {
                        ai.close();
                        ai = null;
                    }
                    isRunning = false;
                    Helpers.showToastSafe(this, "Durduruldu");
                }
//            }).start();
        });

    }

    @Override
    public void onCameraViewStarted(int width, int height) {
        rgb = new Mat();
        gray = new Mat();
        rects = new MatOfRect();
        openCvUtil.onCameraViewStarted(width, height);
        //labelText.setText("Camera Started");
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
    }
    @Override
    public void onCameraViewStopped() {
        openCvUtil.onCameraViewStopped();

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
        // 1) RGBA frame’i yalnızca bir kez oku
        Mat frame = inputFrame.rgba();

        // 2) Eğer algılama modundaysa, background thread’e gönder
        if (isPredicting && detectionRunner != null) {
            // A) Frame’in bir kopyasını al
            Mat backgroundFrame = frame.clone();

            executor.execute(() -> {
                try {
                    // TODO: GC / reuse yönetimi (ModelIn, det, backgroundFrame)
                    // TODO: ViewModel / servis ile frame’leri ve buffer’ları temizleme/yeniden kullanma
                    //   1) Ön işlem
                    Mat modelIn = detectionRunner.process(backgroundFrame);
                    //   2) Inference
                    List<float[]> raw = detectionRunner.runInference(modelIn);
                    //   3) Detection + ROI gölgeleme
                    Mat det = detectionRunner.drawDetections(backgroundFrame, raw);
                    Mat overlaid = openCvUtil.overlay(det);

                    //   4) Sonucu kaydet

                    // paylaşılan lastResult’a yaz
                    synchronized (lock) {
                        if (lastResult != null && !lastResult.empty()) {
                            lastResult.release();
                        }
                        lastResult = openCvUtil.overlay(frame);
                    }

                    //   5) Kaynakları serbest bırak
                    modelIn.release();
                    det.release();
                    backgroundFrame.release();
                } catch (Exception e) {
                    Log.e(TAG, "Background processing error", e);
                }
            });

            // 2.c) hemen önceki sonucu ya da ham frame’i döndür
            synchronized (lock) {
                if (lastResult != null && !lastResult.empty()) {
                    return lastResult;
                } else {
                    // ilk çalışmada henüz işlenmiş yoksa ROI + çerçeve uygula
                    return openCvUtil.overlay(frame);
                }
            }
        }

        // 3) AI devrede değilse sadece ROI + çerçeve çiz
        return openCvUtil.overlay(frame);
    }


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
        Log.d(TAG, "OpenCV unLoaded successfully.");
        updateCameraStatus("Camera View Paused.");
    }
    @Override
    protected void onDestroy() {
        // TFLite ve OpenCV kaynaklarını güvenle kapat
/*        if (aiKedi != null) {
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
        }*/


        // Executor'ü düzgün kapat
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(800, TimeUnit.MILLISECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Diğer kaynakları serbest bırak
        if (mOpenCvCameraView != null) {
            mOpenCvCameraView.disableView();
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
    //---------------------------------------------------------------------------------------------->Creation
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
    //--------------------------Creation
    public void updateDetectionList(List<Category> detectedCategories) {
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
            noResultView.setTextColor(Color.CYAN);
            noResultView.setPadding(10, 10, 10, 10);
            detectedSoundsLayout.addView(noResultView);
        }
    }
    public boolean cameraState(Boolean state){
        if (state){
            if (mOpenCvCameraView != null) {
                mOpenCvCameraView.enableView();
                mOpenCvCameraView.setVisibility(View.VISIBLE);
                currentState = State.CAPTURE;
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
    public Mat setFrame(Mat frame){
        this.frame=frame;
        return frame;
    }
}