package com.kurmez.iyesi.kurmes;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import com.kurmez.iyesi.kurmes.Kurmes.*;

import java.util.ArrayList;
import java.util.List;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import com.kurmez.iyesi.Companion;
import com.kurmez.iyesi.Founded;
import com.kurmez.iyesi.ImageSliderAdapter;
import com.kurmez.iyesi.Login;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.Welcome;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.VelocityTracker;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;
import androidx.viewpager2.widget.ViewPager2;

import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.JavaCamera2View;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class Kurmes_dummy extends AppCompatActivity implements CameraBridgeViewBase.CvCameraViewListener2 {

    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    private FloatingActionButton fabDraggable;
    private float dX, dY;
    private boolean isDragging = false;
    private boolean isPressed = false;
    private FirebaseAuth mAuth;
    private Handler handler = new Handler();
    private JavaCamera2View cameraView;
    private TextView cameraStatusText;
    private Mat mRgba;
    private FrameLayout rootLayout;
    private VelocityTracker velocityTracker = null;
    private long pressStartTime;
    private boolean isLongPressTriggered = false;
    private final int LONG_PRESS_THRESHOLD = 2000; // 2 seconds
    private final int DRAG_THRESHOLD = 20; // Minimum movement to consider a drag

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_kurmes);

        cameraView = findViewById(R.id.kurmes_camera_view);
        cameraView.setVisibility(SurfaceView.VISIBLE);
        cameraView.setCvCameraViewListener(this);

        cameraStatusText = findViewById(R.id.camera_status_text);
        fabDraggable = findViewById(R.id.fab_main);
        mAuth = FirebaseAuth.getInstance();
        rootLayout = findViewById(android.R.id.content);

        checkAndRequestPermissions();
        setupDraggableFAB();
        setupButtonActions();
    }

    private void checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        } else {
            initializeCamera();
        }
    }

    private void initializeCamera() {
        boolean success = OpenCVLoader.initDebug();
        if (success) {
            if (cameraView != null) {
                cameraView.enableView();
                updateCameraStatus("Camera Enabled.");
            }
        } else {
            Toast.makeText(this, "OpenCV initialization failed.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (OpenCVLoader.initDebug()) {
            if (cameraView != null) {
                cameraView.enableView();
                updateCameraStatus("Camera View Resumed.");
            }
        } else {
            updateCameraStatus("OpenCV Initialization Failed.");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (cameraView != null) {
            cameraView.disableView();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraView != null) {
            cameraView.disableView();
        }
    }

    private void updateCameraStatus(String status) {
        if (cameraStatusText != null) {
            cameraStatusText.setText("Camera Status: " + status);
        }
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
        mRgba = new Mat(height, width, CvType.CV_8UC4);
    }

    @Override
    public void onCameraViewStopped() {
        if (mRgba != null) {
            mRgba.release();
        }
    }

    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        mRgba = inputFrame.rgba();
        Imgproc.cvtColor(mRgba, mRgba, Imgproc.COLOR_RGBA2GRAY); // Example processing
        return mRgba;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupDraggableFAB() {
        fabDraggable.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dX = v.getX() - event.getRawX();
                    dY = v.getY() - event.getRawY();
                    isDragging = false;
                    isLongPressTriggered = false;
                    pressStartTime = System.currentTimeMillis();
                    handler.postDelayed(() -> {
                        if (!isDragging) {
                            isLongPressTriggered = true;
                            handleLongClick();
                        }
                    }, LONG_PRESS_THRESHOLD);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float moveX = event.getRawX() + dX;
                    float moveY = event.getRawY() + dY;

                    if (Math.abs(moveX - v.getX()) > DRAG_THRESHOLD || Math.abs(moveY - v.getY()) > DRAG_THRESHOLD) {
                        isDragging = true;
                    }

                    v.animate().x(moveX).y(moveY).setDuration(0).start();
                    return true;

                case MotionEvent.ACTION_UP:
                    handler.removeCallbacksAndMessages(null);
                    if (!isDragging || (System.currentTimeMillis() - pressStartTime) < LONG_PRESS_THRESHOLD) {
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
            openCamera();
        });
    }

    private void handleLongClick() {
        animateButtonPress();
        if (mAuth.getCurrentUser() != null) {
            startActivity(new Intent(Kurmes_dummy.this, Welcome.class));
        } else {
            startActivity(new Intent(Kurmes_dummy.this, Login.class));
        }
    }
    private void animateButtonPress() {
        fabDraggable.setEnabled(false);

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

    private void openCamera() {
        Toast.makeText(this, "Opening Camera...", Toast.LENGTH_SHORT).show();
    }


    public abstract class Founded extends AppCompatActivity {
        public final String LOG_TAG = "MLImageHelper";
        public final static int CAPTURE_IMAGE_ACTIVITY_REQUEST_CODE = 1034;
        private static final int REQUEST_IMAGE_CAPTURE = 1; // Request code for capturing a photo
        public final static int PICK_IMAGE_ACTIVITY_REQUEST_CODE = 1064;
        public final static int REQUEST_READ_EXTERNAL_STORAGE = 2031;
        File photoFile;
        private List<Bitmap> photoList;
        private ImageLabeler imageLabeler;
        private ImageView inputImageView;
        private EditText outputTextView;

        @SuppressLint("ObsoleteSdkInt")
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_founded);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_READ_EXTERNAL_STORAGE);
                }
            }
            //inputImageView = findViewById(R.id.slider_image);
            outputTextView = findViewById(R.id.companion_species);
            // Retrieve the photos passed from Found activity
            photoList = getIntent().getParcelableArrayListExtra("photos");
            if (photoList == null || photoList.isEmpty()) {
                Toast.makeText(this, "No photos found", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            // Set up the image slider
            ViewPager2 photoSlider = findViewById(R.id.founded_photos_slider);
            ImageSliderAdapter sliderAdapter = new ImageSliderAdapter(photoList, this);
            photoSlider.setAdapter(sliderAdapter);

            findViewById(R.id.take_anotherphoto_button).setOnClickListener(v -> finish()); // Return to capture screen
            findViewById(R.id.save_companion_button).setOnClickListener(v -> saveCompanion());

            imageLabeler = ImageLabeling.getClient(new ImageLabelerOptions.Builder()
                    .setConfidenceThreshold(0.7f)
                    .build());
        }
        @Override
        public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            Log.d(com.kurmez.iyesi.Founded.class.getSimpleName(), "grant result for" + permissions[0] + "is" + grantResults[0]);
        }
        /**
         * Saves the companion information along with photos and navigates to the Companion activity.
         */
        private void saveCompanion() {
            String species = "Sarman Cat"; // Replace with actual user input
            String foundDate = "01/01/2025";     // Replace with actual user input
            String foundPlace = "Bishkek";     // Replace with actual user input
            String profileId = "Veterinarian : Evren Hoca"; // Replace with actual data

            if (photoList != null && !photoList.isEmpty()) {
                Bitmap firstPhoto = photoList.get(0);
                String photoUrl = uploadPhotoAndGetUrl(firstPhoto);

                if (photoUrl != null) {
                    Intent intent = new Intent(Kurmes_dummy.this, com.kurmez.iyesi.Companion.class);
                    intent.putExtra("species", species);
                    intent.putExtra("foundDate", foundDate);
                    intent.putExtra("foundPlace", foundPlace);
                    intent.putExtra("photoUrl", photoUrl);
                    intent.putExtra("profileId", profileId);
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "Failed to upload photo", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "No photo available to register companion", Toast.LENGTH_SHORT).show();
            }
        }
        /**
         * Simulates uploading the photo and returning a URL (replace with actual implementation).
         */
        private String uploadPhotoAndGetUrl(Bitmap photo) {
            // TODO: Replace this with actual upload logic (e.g., Firebase Storage)
            return "https://example.com/photo.jpg"; // Replace with the real uploaded URL
        }


        private void runClassification(Bitmap bitmap){
            InputImage inputImage = InputImage.fromBitmap(bitmap,0);
            imageLabeler.process(inputImage).addOnSuccessListener(new OnSuccessListener<List<ImageLabel>>() {
                @Override
                public void onSuccess(List<ImageLabel> imageLabels) {
                    if (imageLabels.size() > 0) {
                        StringBuilder builder = new StringBuilder();
                        for (ImageLabel label : imageLabels) {
                            builder.append(label.getText())
                                    .append(" : ")
                                    .append("\n");
                        }
                        outputTextView.setText(builder.toString());
                    }else {
                        outputTextView.setText("Could Not Classify");
                    }
                }
            }).addOnFailureListener(new OnFailureListener() {
                @Override
                public void onFailure(@NonNull Exception e) {
                    e.printStackTrace();
                }
            });
        }
        public void onGotoImageActivity(View view){
            Intent intent = new Intent();
        }
        public void onPickImage(View view) {
            // create Intent to take a picture and return control to the calling application
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");

            // If you call startActivityForResult() using an intent that no app can handle, your app will crash.
            // So as long as the result is not null, it's safe to use the intent.
            if (intent.resolveActivity(getPackageManager()) != null) {
                // Start the image capture intent to take photo
                startActivityForResult(intent, PICK_IMAGE_ACTIVITY_REQUEST_CODE);
            }
        }
        public void onStartCamera(View view){
            Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
                startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
            } else {
                Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show();
            }
        }
        protected abstract void runDetection(Bitmap bitmap);
        private Bitmap getCapturedImage() {
            // Get the dimensions of the View
            int targetW = inputImageView.getWidth();
            int targetH = inputImageView.getHeight();

            BitmapFactory.Options bmOptions = new BitmapFactory.Options();
            bmOptions.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(photoFile.getAbsolutePath());
            int photoW = bmOptions.outWidth;
            int photoH = bmOptions.outHeight;
            int scaleFactor = Math.max(1, Math.min(photoW / targetW, photoH /targetH));

            bmOptions = new BitmapFactory.Options();
            bmOptions.inJustDecodeBounds = false;
            bmOptions.inSampleSize = scaleFactor;
            bmOptions.inMutable = true;
            return BitmapFactory.decodeFile(photoFile.getAbsolutePath(), bmOptions);
        }
        private Bitmap rotateImage(Bitmap source, float angle) {
            Matrix matrix = new Matrix();
            matrix.postRotate(angle);
            return Bitmap.createBitmap(
                    source, 0, 0, source.getWidth(), source.getHeight(),
                    matrix, true
            );
        }
        private void rotateIfRequired(Bitmap bitmap) {
            try {
                ExifInterface exifInterface = new ExifInterface(photoFile.getAbsolutePath());
                int orientation = exifInterface.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_UNDEFINED
                );

                if (orientation == ExifInterface.ORIENTATION_ROTATE_90) {
                    rotateImage(bitmap, 90f);
                } else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) {
                    rotateImage(bitmap, 180f);
                } else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) {
                    rotateImage(bitmap, 270f);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        protected Bitmap loadFromUri(Uri photoUri) {
            Bitmap image = null;

            try {
                if (Build.VERSION.SDK_INT > 27) {
                    ImageDecoder.Source source = ImageDecoder.createSource(this.getContentResolver(), photoUri);
                    image = ImageDecoder.decodeBitmap(source);
                } else {
                    image = MediaStore.Images.Media.getBitmap(this.getContentResolver(), photoUri);
                }
            } catch(IOException e) {
                e.printStackTrace();
            }

            return image;
        }
        public void onTakeImage(View view) {
            // create Intent to take a picture and return control to the calling application
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            // Create a File reference for future access
            photoFile = getPhotoFileUri(new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".jpg");

            // wrap File object into a content provider
            // required for API >= 24
            // See https://guides.codepath.com/android/Sharing-Content-with-Intents#sharing-files-with-api-24-or-higher
            Uri fileProvider = FileProvider.getUriForFile(this, "com.iago.fileprovider1", photoFile);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, fileProvider);

            // If you call startActivityForResult() using an intent that no app can handle, your app will crash.
            // So as long as the result is not null, it's safe to use the intent.
            if (intent.resolveActivity(getPackageManager()) != null) {
                // Start the image capture intent to take photo
                startActivityForResult(intent, CAPTURE_IMAGE_ACTIVITY_REQUEST_CODE);
            }
        }
        // Returns the File for a photo stored on disk given the fileName
        public File getPhotoFileUri(String fileName) {
            // Get safe storage directory for photos
            // Use `getExternalFilesDir` on Context to access package-specific directories.
            // This way, we don't need to request external read/write runtime permissions.
            File mediaStorageDir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), LOG_TAG);

            // Create the storage directory if it does not exist
            if (!mediaStorageDir.exists() && !mediaStorageDir.mkdirs()){
                Log.d(LOG_TAG, "failed to create directory");
            }

            // Return the file target for the photo based on filename
            File file = new File(mediaStorageDir.getPath() + File.separator + fileName);

            return file;
        }
        /**
         * getCapturedImage():
         *     Decodes and crops the captured image from camera.
         */
        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (requestCode == CAPTURE_IMAGE_ACTIVITY_REQUEST_CODE) {
                if (resultCode == RESULT_OK) {
                    Bitmap bitmap = getCapturedImage();
                    rotateIfRequired(bitmap);
                    inputImageView.setImageBitmap(bitmap);
                    runDetection(bitmap);
                } else { // Result was a failure
                    Toast.makeText(this, "Picture wasn't taken!", Toast.LENGTH_SHORT).show();
                }
            } else if (requestCode == PICK_IMAGE_ACTIVITY_REQUEST_CODE) {
                if (resultCode == RESULT_OK) {
                    Bitmap takenImage = loadFromUri(data.getData());
                    inputImageView.setImageBitmap(takenImage);
                    runDetection(takenImage);
                } else { // Result was a failure
                    Toast.makeText(this, "Picture wasn't selected!", Toast.LENGTH_SHORT).show();
                }
            }
        }

    }


    public class Foundede extends AppCompatActivity {
        private List<Bitmap> photoList;
        private ImageLabeler imageLabeler;
        private ImageView inputImageView;
        private EditText outputTextView;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_founded);

            //requestPermissions(new String[]{com.kurmez.iyesi.Manifest.permission.READ_EXTERNAL});

            //inputImageView = findViewById(R.id.slider_image);
            outputTextView = findViewById(R.id.companion_species);
            // Retrieve the photos passed from Found activity
            photoList = getIntent().getParcelableArrayListExtra("photos");
            if (photoList == null || photoList.isEmpty()) {
                Toast.makeText(this, "No photos found", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            // Set up the image slider
            ViewPager2 photoSlider = findViewById(R.id.founded_photos_slider);
            ImageSliderAdapter sliderAdapter = new ImageSliderAdapter(photoList, this);
            photoSlider.setAdapter(sliderAdapter);

            findViewById(R.id.take_anotherphoto_button).setOnClickListener(v -> finish()); // Return to capture screen

            findViewById(R.id.save_companion_button).setOnClickListener(v -> saveCompanion());

            imageLabeler = ImageLabeling.getClient(new ImageLabelerOptions.Builder()
                    .setConfidenceThreshold(0.7f)
                    .build());

        }
        /**
         * Saves the companion information along with photos and navigates to the Companion activity.
         */
        private void saveCompanion() {
            String species = "Sarman Cat"; // Replace with actual user input
            String foundDate = "01/01/2025";     // Replace with actual user input
            String foundPlace = "Bishkek";     // Replace with actual user input
            String profileId = "Veterinarian : Evren Hoca"; // Replace with actual data

            if (photoList != null && !photoList.isEmpty()) {
                Bitmap firstPhoto = photoList.get(0);
                String photoUrl = uploadPhotoAndGetUrl(firstPhoto);

                if (photoUrl != null) {
                    Intent intent = new Intent(Kurmes_dummy.this, Companion.class);
                    intent.putExtra("species", species);
                    intent.putExtra("foundDate", foundDate);
                    intent.putExtra("foundPlace", foundPlace);
                    intent.putExtra("photoUrl", photoUrl);
                    intent.putExtra("profileId", profileId);
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "Failed to upload photo", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "No photo available to register companion", Toast.LENGTH_SHORT).show();
            }
        }
        /**
         * Simulates uploading the photo and returning a URL (replace with actual implementation).
         */
        private String uploadPhotoAndGetUrl(Bitmap photo) {
            // TODO: Replace this with actual upload logic (e.g., Firebase Storage)
            return "https://example.com/photo.jpg"; // Replace with the real uploaded URL
        }
        private void runClassification(Bitmap bitmap){
            InputImage inputImage = InputImage.fromBitmap(bitmap,0);
            imageLabeler.process(inputImage).addOnSuccessListener(new OnSuccessListener<List<ImageLabel>>() {
                @Override
                public void onSuccess(List<ImageLabel> imageLabels) {
                    if (imageLabels.size() > 0) {
                        StringBuilder builder = new StringBuilder();
                        for (ImageLabel label : imageLabels) {
                            builder.append(label.getText())
                                    .append(" : ")
                                    .append("\n");
                        }
                        outputTextView.setText(builder.toString());
                    }else {
                        outputTextView.setText("Could Not Classify");
                    }
                }
            }).addOnFailureListener(new OnFailureListener() {
                @Override
                public void onFailure(@NonNull Exception e) {
                    e.printStackTrace();
                }
            });
        }
        public void onGotoImageActivity(View view){
            Intent intent = new Intent();
        }
    }
}
