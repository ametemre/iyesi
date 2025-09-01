package com.kurmez.iyesi.kurmes;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.kurmez.iyesi.umay.sahiplendirme.Founded;
import com.kurmez.iyesi.kurmes.utilities.Ai.Detection;
//import com.kurmez.iyesi.utilities.Ai.OpenCV;
import com.kurmez.iyesi.kurmes.utilities.Ai.OpenCV;
import com.kurmez.iyesi.kurmes.utilities.Ai.SoundClassifier;
import com.kurmez.iyesi.kurmes.utilities.Ai.VideoClassifier;
import com.kurmez.iyesi.kurmes.utilities.Ai.delegate.TFLiteInputPreprocessor;
import com.kurmez.iyesi.kurmes.utilities.Ai.threading.ThreadService;
import com.kurmez.iyesi.kurmes.utilities.helper.ResourceMonitor;
import com.kurmez.iyesi.kurmes.utilities.Helpers;
import com.kurmez.iyesi.kurmes.utilities.RTPipeline;
import com.kurmez.iyesi.kurmes.utilities.Ai.threading.Terminator;
import com.kurmez.iyesi.kurmes.utilities.helper.Actions;
import com.kurmez.iyesi.kurmes.utilities.helper.PermissionHelper;
import com.kurmez.iyesi.R;

import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.Utils;
import org.opencv.core.Mat;

import android.content.Intent;
import android.graphics.Bitmap;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.kurmez.iyesi.kurmes.utilities.Ai.Ai;
import com.kurmez.iyesi.kurmes.utilities.MiniFabs;
import com.kurmez.iyesi.umay.sokak.SokakActivity;

import org.opencv.android.CameraActivity;
import org.opencv.android.OpenCVLoader;

import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.tensorflow.lite.Interpreter;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.view.Menu;

import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.core.MatOfRect;

import java.util.ArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;


public class Kurmes extends CameraActivity implements CvCameraViewListener2 {
    private static final String TAG = "Kurmes";
    public Mat rgba,drawn;
    private final Object lock = new Object();
    private volatile Mat lastResult = null;
    public enum State {
        KEDI, KOPEK, KURT, KARGA,
        IDLE, FACE_DETECTION, OBJECT_DETECTION, TRACKING, CAPTURE,TEST
    }
    public State currentState = State.IDLE;
    // Sınıf alanına ekleyin
    private ImageView processedView;
    private List<float[]> buffer = new ArrayList<>();
    private List<float[]> soundBuffer = new ArrayList<>();
    private List<float[][][]> videoBuffer = new ArrayList<>();
    private List<Bitmap> photoList = new ArrayList<>(); // List to store captured images
    //----------------------------------------------------------------------------------------------Threading
    int cpuCores = Runtime.getRuntime().availableProcessors();


    /*
    ThreadPoolExecutor cpuExecutor = new ThreadPoolExecutor(
            Math.max(1, cpuCores - 1),    // core havuz boyutu = toplam çekirdek-1
            Math.max(1, cpuCores - 1),    // max havuz boyutu = toplam çekirdek-1
            1, TimeUnit.SECONDS,          // keep-alive süresi
            new ArrayBlockingQueue<>(2),  // küçük kuyruk, sıktığında yeni thread açar
            new ThreadPoolExecutor.DiscardOldestPolicy()
    );
    */


    // Üst sınıf veya Activity içinde
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1,
            Runtime.getRuntime().availableProcessors(),
            1, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4),
            new ThreadPoolExecutor.DiscardOldestPolicy()
    );
    ExecutorService gpuExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GpuThread");
        t.setPriority(Thread.MAX_PRIORITY);
        return t;
    });
    //----------------------------------------------------------------------------------------------Threading
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
    public CameraBridgeViewBase mOpenCvCameraView;
    private Interpreter interpreter;
    public Ai /*aiKedi, aiKopek, aiKurt, aiKarga, aiContent, */ai;
    //private OpenCV openCV;
    private RTPipeline pipeline;
    private MiniFabs miniFabs;
    private Actions actions;
    private VideoClassifier videoClassifier;
    private SoundClassifier soundClassifier;
    ThreadService threadService = new ThreadService();
    //----------------------------------------------------------------------------------------------<<Creation
    //private boolean cleanUp = false;
    public boolean isPredicting = false;
    private boolean isRunning = false;
    private TextView labelText, statusText,cameraStatusText;
    private LinearLayout linearLayout;
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
    private ResourceMonitor monitor;
    private PermissionHelper permissionHelper;
    // callback’i dışarıda tanımladık:
    private final PermissionHelper.Callback permissionCallback = new PermissionHelper.Callback() {
        @Override public void onGranted() {
            // tüm izinler verildi
        }
        @Override public void onDenied() {
            // izinlerden en az biri reddedildi
        }
    };
    MatOfRect rects;
    Mat overlaid;
    //----------------------------------------------------------------------------------------------<Creation
    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "called Kurmes onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_kurmes);
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            // API 30 altı → Kurmes’i pas geç, SokakActivity’e yönlendir
            startActivity(new Intent(this, SokakActivity.class /* paket adını sizde farklıysa güncelleyin*/));
            finish();
            return;
        }
        /*
        // izinler
        // 1) helper’ı oluştur, 2) activity ve callback ata,
        permissionHelper = new PermissionHelper();
        permissionHelper.setActivity(this);
        permissionHelper.setCallback(permissionCallback);
        permissionHelper.initialize();
        permissionHelper.requestAllPermissions();
        permissionHelper.setCallback(permissionCallback);
        // 3) parametresiz initialize:
        permissionHelper.initialize();

        // artık dilediğiniz yerde:
        permissionHelper.requestAllPermissions();
        */
        // Firebase Auth
        mAuth = FirebaseAuth.getInstance();
        // Keep screen on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        processedView = findViewById(R.id.processed_view);
        TextView tv = findViewById(R.id.resource_monitor);
        monitor = new ResourceMonitor(this, tv);
        //processedView.setScaleX(0.2f);
        //processedView.setScaleY(0.2f);
        //processedView.getBaselineAlignBottom();
        //processedView.setLeftTopRightBottom();
        // UI elements
        labelText        = findViewById(R.id.label_text);
        cameraStatusText = findViewById(R.id.camera_status_text);
        fabMain          = findViewById(R.id.fab_main);
        fabAction        = findViewById(R.id.fab_Sound);
        linearLayout     = findViewById(R.id.detected_sounds_list);
        SetLabelText("init...");
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
        /* Boolean isLong = */miniFabs.setupDraggableFAB(this,miniFabs, fabMain);/*
        if (isLong){
            Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
                startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
            } else {
                Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show();
            }
        }*/
        miniFabs.applyDefaultColors();
        // onCreate içinde…
        miniFabs.setOnSnapshotListener(bitmap -> {
            // 3. Adım: Alınan Bitmap’i intent ile Founded aktiviteye gönder
            // Önce cache’e yaz veya doğrudan byte array’e dönüştür:
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
            byte[] imageData = baos.toByteArray();

            Intent intent = new Intent(Kurmes.this, Founded.class);
            intent.putExtra("snapshot", imageData);
            intent.putExtra("predictedSpecies", "yourModelResultString");  //buraya model çıktısını getir
            startActivity(intent);
        });


        actions = new Actions(miniFabs, this, this);
        SetLabelText("fabs...");
        // ----------------------------------------------------------------------------------------Instantiate MiniFabs helper and keep as field

        for (FloatingActionButton fab : miniFabs.getFabs()) {
            fab.setOnClickListener(v -> {
                actions.onFabSelected(fab);
                // Highlight selection
            });
        }
        SetLabelText("ready...");
        // fabAction:

        fabAction.setOnClickListener(v -> {
            SetLabelText("standby...");
//            new Thread(() -> {
            if (!isRunning) {
                // Kullanıcı model seçmeden başlatmak isterse
                if (miniFabs.getSelectedFab() == null) {
                    runOnUiThread(() -> Helpers.showToastSafe(this,"Önce bir seçenek seçin"));
                    //runOnUiThread(() -> Toast.makeText(this, "Önce bir seçenek seçin", Toast.LENGTH_SHORT).show());
                }
                gpuExecutor.execute(() -> {
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
                                result -> runOnUiThread(() -> SetLabelText(result))
                        );
                        videoClassifier = new VideoClassifier(
                                getApplicationContext(),
                                ai,
                                ai.getPath(),
                                0.25f,  // probability threshold
                                result -> runOnUiThread(() -> SetLabelText(result))
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
                        currentState = State.TEST;

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
                isPredicting = false;
                currentState = State.IDLE;



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
                }



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
        //threadService.availableCPU();
        //threadService.availableGPU();
        //threadService.availableGPUThreads();

    }                                                         //done


    // Kurmes sınıfına ek alanlar
    private final MatPool matPool = new MatPool(3); // 3 Mat nesnesi havuzu
    public Bitmap reusableBitmap = null;

    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        try {
            this.rgba = inputFrame.rgba();
        } catch (Exception e) {
            Log.e(TAG, "InputFrame error: " + e);
            return new Mat(); // Hata durumunda boş Mat döndür
        }

        // Görüntüleme için klon (havuz kullanmıyoruz, OpenCV bu Mat'i yönetiyor)
        Mat display = rgba.clone();

        if (rgba.empty() || executor.isShutdown() || !isPredicting || (frameCount++ % SKIP_FRAMES != 0)) {
            return display;
        }

        // Havuzdan Mat nesnelerini al
        Mat copyForProcess = matPool.acquire(rgba.size(), rgba.type());
        Mat copyForInference = matPool.acquire(rgba.size(), rgba.type());

        // Kameradan gelen veriyi kopyala
        rgba.copyTo(copyForProcess);
        rgba.copyTo(copyForInference);

        if (!gpuExecutor.isShutdown()) {
            gpuExecutor.submit(() -> {
                Mat pre = null;
                Mat drawn = null;
                Mat overlaidMat = null;

                try {
                    // 1) Ön işlem
                    pre = detectionRunner.process(copyForProcess);

                    // 2) İnferans
                    List<float[]> buffer = detectionRunner.runInference(pre, this, linearLayout);

                    // 3) Bitmap için yeniden boyutlandırma ve dönüşüm
                    if (reusableBitmap == null ||
                            reusableBitmap.getWidth() != pre.cols() ||
                            reusableBitmap.getHeight() != pre.rows()) {

                        if (reusableBitmap != null) {
                            reusableBitmap.recycle();
                        }
                        reusableBitmap = Bitmap.createBitmap(pre.cols(), pre.rows(), Bitmap.Config.ARGB_8888);
                    }
                    Utils.matToBitmap(pre, reusableBitmap);

                    // 4) Video sınıflandırma
                    videoClassifier.classifyFrame(reusableBitmap);

                    // 5) Tespit çizimleri

                    drawn = detectionRunner.drawDetections(copyForProcess, detectionRunner.getDets(null));

                    // 6) Overlay uygula
                    overlaidMat = openCvUtil.overlay(drawn);

                    // 7) UI güncellemesi
                    runOnUiThread(() -> processedView.setImageBitmap(reusableBitmap));

                } catch (Exception e) {
                    Log.e(TAG, "Background inference error", e);
                } finally {
                    // Tüm kaynakları serbest bırak ve havuza geri ver
                    if (pre != null) matPool.release(pre);
                    if (drawn != null) matPool.release(drawn);
                    if (overlaidMat != null) matPool.release(overlaidMat);

                    matPool.release(copyForProcess);
                    matPool.release(copyForInference);
                }
            });
        }
        return display;
    }

    // Mat havuzu sınıfı
    private static class MatPool {
        private final Queue<Mat> availableMats = new ArrayDeque<>();
        private final int maxSize;

        public MatPool(int maxSize) {
            this.maxSize = maxSize;
        }

        public synchronized Mat acquire(Size size, int type) {
            // Havuzda uygun Mat var mı kontrol et
            for (Iterator<Mat> it = availableMats.iterator(); it.hasNext();) {
                Mat mat = it.next();
                if (mat.size().equals(size) && mat.type() == type) {
                    it.remove();
                    return mat;
                }
            }

            // Havuz boşsa veya uygun boyutta yoksa yeni oluştur
            return new Mat(size, type);
        }

        public synchronized void release(Mat mat) {
            if (mat == null) return;

            // Mat'i sıfırla ve havuza ekle
            mat.setTo(new Scalar(0));

            if (availableMats.size() < maxSize) {
                availableMats.offer(mat);
            } else {
                // Havuz doluysa direkt serbest bırak
                mat.release();
            }
        }

        public synchronized void clear() {
            for (Mat mat : availableMats) {
                mat.release();
            }
            availableMats.clear();
        }
    }



    protected void onResume() {
        super.onResume();
        monitor.start();
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
        monitor.stop();
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
        matPool.clear();
        if (reusableBitmap != null) {
            reusableBitmap.recycle();
            reusableBitmap = null;
        }

        // Executor'ü düzgün kapat
        if (gpuExecutor != null) {
            gpuExecutor.shutdown();
            try {
                if (!gpuExecutor.awaitTermination(800, TimeUnit.MILLISECONDS)) {
                    gpuExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                gpuExecutor.shutdownNow();
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
    public State getCurrentState() {
        return currentState;
    }
}