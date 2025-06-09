package com.kurmez.iyesi.utilities.Ai;

import org.opencv.android.CameraBridgeViewBase;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

/**
 * OpenCV kamera akışında sabit bir dikdörtgen çizmek için örnek sınıf
 */
public class OpenCV implements CameraBridgeViewBase.CvCameraViewListener2 {
    private Rect roi;

    // Kamera başlatıldığında frame boyutuna göre ROI tanımlanır
    private void initROI(int width, int height) {
        int w = 200, h = 200; // dikdörtgen boyutu
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        roi = new Rect(new Point(x, y), new Point(x + w, y + h));
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
        initROI(width, height);
    }

    @Override
    public void onCameraViewStopped() {
        // Gerekirse kaynakları serbest bırak
    }

    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        Mat frame = inputFrame.rgba();
        // Yeşil kalınlığı 3 piksel olan dikdörtgen çiz
        Imgproc.rectangle(frame, roi.tl(), roi.br(), new Scalar(0, 255, 0), 3);
        return frame;
    }
}
