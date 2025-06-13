package com.kurmez.iyesi.kurmes;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.kurmez.iyesi.utilities.Ai.Detection;
import com.kurmez.iyesi.utilities.Ai.TFLiteInputMapper;
import com.kurmez.iyesi.utilities.Ai.TFLiteInputPreprocessor;
import com.kurmez.iyesi.utilities.Ai.Threading;
import com.kurmez.iyesi.utilities.Terminator;
import com.kurmez.iyesi.utilities.helper.Actions;
import com.kurmez.iyesi.utilities.helper.Permissions;
import com.kurmez.iyesi.R;

import org.opencv.android.CameraBridgeViewBase;
import org.opencv.core.Mat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
// ve kendi Detection/Ai class’ınızın import’ları

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.kurmez.iyesi.utilities.Ai.Ai;
import com.kurmez.iyesi.utilities.MiniFabs;

import org.opencv.android.CameraActivity;
import org.opencv.android.OpenCVLoader;

import org.tensorflow.lite.Interpreter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.view.Menu;

import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.core.MatOfRect;

import java.util.ArrayList;


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
    private Mat frame;
    public enum State {
        KEDI, KOPEK, KURT, KARGA,
        IDLE, FACE_DETECTION, OBJECT_DETECTION, TRACKING, CAPTURE,TEST
    }
    ExecutorService executor = Executors.newSingleThreadExecutor();
    public State currentState = State.IDLE;
    private int mWidth, mHeight;
    private final List<float[]> soundBuffer = new ArrayList<>();
    private final List<float[][][]> videoBuffer = new ArrayList<>();
    Threading threading = new Threading();

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
    private Terminator terminator = null;
    private TFLiteInputPreprocessor preprocessor;
    private TFLiteInputMapper mapper;
    private FirebaseAuth mAuth;
    private Handler handler = new Handler();
    public static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    //private JavaCamera2View cameraView; // Using JavaCamera2View
    private TextView labelText, statusText;
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

    private FloatingActionButton fabMain, fabAction;
    private FrameLayout rootLayout;


    private Detection detector;


    private Handler uiHandler = new Handler();
    private boolean isRunning = false;

    private FirebaseAuth auth;
    //private DetectorFunction activeDetectorFunction = () -> videoYapayZeka(getResources().openRawResource(R.raw.lbpcascade_frontalface), null);
    public CameraCalibrator mCalibrator;
    private OnCameraFrameRender mOnCameraFrameRender;
    private Menu mMenu;

    //----------------------------------------------------------------------------------------------<<Creation

    //----------------------------------------------------------------------------------------------<Creation
    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "called Kurmes onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_kurmes);
        Permissions permissions = new Permissions();

        // izinler
        new Permissions().requestAllPermissions(this);
        // Firebase Auth
        mAuth = FirebaseAuth.getInstance();
        // Keep screen on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // UI elements
        labelText        = findViewById(R.id.label_text);
        cameraStatusText = findViewById(R.id.camera_status_text);
        fabMain     = findViewById(R.id.fab_main);
        fabAction         = findViewById(R.id.fab_Sound);
        rootLayout       = findViewById(android.R.id.content);
        mOpenCvCameraView= findViewById(R.id.kurmes_camera_view);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);
        // Instantiate MiniFabs helper and keep as field
        int[] miniFabIds = {
                R.id.fab_1, R.id.fab_2, R.id.fab_3,
                R.id.fab_4, R.id.fab_5, R.id.fab_6,
                R.id.fab_7, R.id.fab_8, R.id.fab_9
        };


        miniFabs = new MiniFabs(this, fabMain, fabAction, miniFabIds);
        miniFabs.applyDefaultColors();
        rootLayout.setOnTouchListener((v,e)-> miniFabs.handleOutsideTouch(e));
        miniFabs.setupDraggableFAB(miniFabs, fabMain);
        actions = new Actions(miniFabs, this, this);
        // Collapse on outside touch (camera view or root)
        View.OnTouchListener outsideListener = (v, ev) -> {
            return miniFabs.handleOutsideTouch(ev);
        };

        rootLayout.setOnTouchListener(outsideListener);

        // Set up draggable & expand/collapse behavior
        miniFabs.setupDraggableFAB(miniFabs, fabMain);
        Actions actions = new Actions(miniFabs, this, this /* or getApplicationContext() */ );
        // Wire each miniFAB to call selectFab() + your onFabClick logic
        for (FloatingActionButton fab : miniFabs.getFabs()) {
            fab.setOnClickListener(v -> {
                actions.onFabSelected(fab);
                // Highlight selection
            });
        }
        // fabAction: başlat/durdur
        fabAction.setOnClickListener(v-> {
            if (!isRunning) {
                if (miniFabs.getSelectedFab()==null) {
                    Toast.makeText(this,"Önce bir seçenek seçin",Toast.LENGTH_SHORT).show();
                    return;
                }
                mOpenCvCameraView.enableView();
                ai = actions.performSelectedAction(miniFabs.getSelectedFab());
                if (ai!=null) {
                    interpreter = ai.getVideoInterpreter();

                mapper = new TFLiteInputMapper(mWidth, mHeight, ai.getInputWidth(), ai.getInputHeight());
                preprocessor = new TFLiteInputPreprocessor(mapper);
                detector = new Detection(ai, interpreter, this,
                        0,0,0,0,0,0,
                        miniFabs.getSelectedFab().toString());
                terminator = new Terminator(
                        ai.getExecutor(), ai.getVideoInterpreter(),
                        ai.getSoundInterpreter(), ai.getGpuDelegate(),
                        videoBuffer, soundBuffer, this
                );
                isRunning = true;
                }
            } else {
                terminator.onStopButtonClicked(v);
                ai.close(); ai=null;
                isRunning = false;
                mOpenCvCameraView.disableView();
                Toast.makeText(this,"Durduruldu",Toast.LENGTH_SHORT).show();
            }
        });/*
        // Sound FAB click (if needed)
        fabSound.setOnClickListener(v -> {
            if (!isPredicting) {
                if (miniFabs.getSelectedFab() != null) {
                    ai = actions.performSelectedAction(miniFabs.getSelectedFab());
                    if (ai != null) {
                        // OpenCV camera initialization


                        mOpenCvCameraView.enableView();
                        mOpenCvCameraView.setOnTouchListener(outsideListener);
                        interpreter = ai.getVideoInterpreter();
                        mapper = new TFLiteInputMapper(mWidth, mHeight, ai.getInputWidth(), ai.getInputHeight());
                        preprocessor = new TFLiteInputPreprocessor(mapper);
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
            }
            else if (terminator != null){
                terminator.onStopButtonClicked(v);
                isPredicting = false;
                ai = null;
                Toast.makeText(this, "Operasyonlar durduruldu", Toast.LENGTH_SHORT).show();
            }
        });*/
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
        rgb = new Mat();
        gray = new Mat();
        rects = new MatOfRect();
        threading = new Threading();
        threading.availableCPU();
        threading.availableGPUThreads();
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

        threading.availableCPU();
        threading.availableGPUThreads();
        if (ai!=null) {
            TFLiteInputMapper mapper = new TFLiteInputMapper(
                    width, height,
                    ai.getInputWidth(), ai.getInputHeight()
            );
            preprocessor = new TFLiteInputPreprocessor(mapper);
        }
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
        Mat rgba = inputFrame.rgba();
        // Eğer ai null ise (henüz başlatılmadıysa) hiç bir şey yapma
        if (ai == null || !isPredicting) {
            return rgba;
        }
        if (!executor.isShutdown()) {
            executor.submit(() -> {
                try {
                    /*
                    ai.handleRT(
                            rgba,
                            mat -> preprocessor.map(mat),
                            output -> {
                                // Burada ai kesinlikle null değil
                                List<Detection> dets = ai.parseDetections(output);
                                runOnUiThread(() ->
                                        ai.drawDetections(rgba, dets)
                                );
                            }
                    );
                    */
                    detector.handleRT(rgba,ai);
                } catch (Exception e) {
                    Log.e(TAG, "Inference error in frame", e);
                }
            });
        }
                /*
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
*/
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