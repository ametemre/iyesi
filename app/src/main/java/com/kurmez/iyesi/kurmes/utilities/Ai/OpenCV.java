package com.kurmez.iyesi.kurmes.utilities.Ai;

import android.util.Log;

import org.opencv.android.CameraBridgeViewBase;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OpenCV implements CameraBridgeViewBase.CvCameraViewListener2 {
    private static final String TAG = "OpenCV";
    private Rect roi;
    ExecutorService executor = Executors.newSingleThreadExecutor();

    private void initROI(int width, int height) {
        int w = 200, h = 200;
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        roi = new Rect(x, y, w, h);
    }

    public void setROI(int x, int y, int w, int h) {
        roi = new Rect(x, y, w, h);
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
        initROI(width, height);
    }

    @Override
    public void onCameraViewStopped() { /* temizleme gerekirse */ }

    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        Mat frame = inputFrame.rgba();
        Mat display = frame.clone();  // Çizimleri bu klona yap

        executor.submit(() -> {
            // ROI vurgulama
            if (roi != null) {
                // Karartmalar
                Imgproc.rectangle(frame, new Point(0, 0),
                        new Point(frame.cols(), roi.y),
                        new Scalar(0, 0, 0, 80), -1);
                Imgproc.rectangle(frame, new Point(0, roi.y + roi.height),
                        new Point(frame.cols(), frame.rows()),
                        new Scalar(0, 0, 0, 80), -1);
                Imgproc.rectangle(frame, new Point(0, roi.y),
                        new Point(roi.x, roi.y + roi.height),
                        new Scalar(0, 0, 0, 80), -1);
                Imgproc.rectangle(frame, new Point(roi.x + roi.width, roi.y),
                        new Point(frame.cols(), roi.y + roi.height),
                        new Scalar(0, 0, 0, 80), -1);

                // ROI çerçevesi
                Imgproc.rectangle(frame, roi.tl(), roi.br(),
                        new Scalar(0, 255, 0), 3);
            }

            // Örnek şekiller
            Imgproc.circle(frame, new Point(80, 80), 30,
                    new Scalar(255, 0, 0), 3);
            Imgproc.line(frame, new Point(0, 0),
                    new Point(frame.cols(), frame.rows()),
                    new Scalar(0, 255, 255), 2);
            Imgproc.putText(frame, "OpenCV RealTime",
                    new Point(30, frame.rows() - 30),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 1.0,
                    new Scalar(255, 255, 255), 2);
        });
        return display;
    }/*
    // OpenCV.java içinde
    public Mat overlay(Mat frame) {
        // Mevcut onCameraFrame(CvCameraViewFrame) logic’ini buradan çağırın
        return onCameraFrame(new CameraBridgeViewBase.CvCameraViewFrame() {
            @Override public Mat rgba() { return frame; }
            @Override public Mat gray() { return frame; }

            @Override
            public void release() {

            }
            // release() override gerekmez—default imple mantığı kullanılır
        });
    }*/
    /**
     * Çizim için DetectionResult listesi kullanın.
     */
    public void drawDetectionsOnMat(Mat frame, List<DetectionResult> dets) {
        if (dets == null) return;
        for (DetectionResult r : dets) {
            Point tl = new Point(r.getX1(), r.getY1());
            Point br = new Point(r.getX2(), r.getY2());

            Imgproc.rectangle(frame, tl, br,
                    new Scalar(0,255,0), 2);

            String text = r.getLabel() +
                    String.format(" %.2f", r.getScore());
            Imgproc.putText(frame, text,
                    new Point(r.getX1(), r.getY1() - 8),
                    Imgproc.FONT_HERSHEY_SIMPLEX,
                    0.6, new Scalar(255,255,255), 2);

            Log.i(TAG, String.format(
                    "Detection %d: %s %.2f @ [%.1f,%.1f-%.1f,%.1f]",
                    r.getClassId(), r.getLabel(), r.getScore(),
                    r.getX1(), r.getY1(), r.getX2(), r.getY2()
            ));
        }
    }
    public Mat overlay(Mat frame) {
        Mat dst = frame.clone();
        if (roi != null) {
            // ROI dışını gölgele
            Imgproc.rectangle(dst, new Point(0, 0),
                    new Point(dst.cols(), roi.y),
                    new Scalar(0, 0, 0, 80), -1);
            Imgproc.rectangle(dst, new Point(0, roi.y + roi.height),
                    new Point(dst.cols(), dst.rows()),
                    new Scalar(0, 0, 0, 80), -1);
            Imgproc.rectangle(dst, new Point(0, roi.y),
                    new Point(roi.x, roi.y + roi.height),
                    new Scalar(0, 0, 0, 80), -1);
            Imgproc.rectangle(dst, new Point(roi.x + roi.width, roi.y),
                    new Point(dst.cols(), roi.y + roi.height),
                    new Scalar(0, 0, 0, 80), -1);

            // ROI çerçevesi
            Imgproc.rectangle(dst, roi.tl(), roi.br(),
                    new Scalar(0, 255, 0), 3);
        }
        return dst;
    }

// OpenCV.java içinde, class sonuna doğru:

    public void drawDetectionsOnMat_raw(ArrayList<Detection> rawDetections, Mat frame) {
        List<DetectionResult> results = new ArrayList<>();
        for (Detection d : rawDetections) {
            results.add(new DetectionResult(
                    d.classId,   // veya getter
                    d.label,
                    d.score,
                    d.x1, d.y1,
                    d.x2, d.y2
            ));
        }
        // Ortaya çıkan listeyi çizim metoduna ver:
        drawDetectionsOnMat(frame, results);
    }  // <-- burası metodun kapanışı

}  // <-- ve burası sınıfın kapanışı

