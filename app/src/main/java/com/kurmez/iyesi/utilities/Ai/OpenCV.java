package com.kurmez.iyesi.utilities.Ai;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.util.Consumer;

import org.opencv.android.CameraBridgeViewBase;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * OpenCV kamera akışında sabit bir dikdörtgen çizmek için örnek sınıf
 */
public class OpenCV implements CameraBridgeViewBase.CvCameraViewListener2 {
    private static final String TAG = "OpenCV";
    private Rect roi;
    private void initROI(int width, int height) {
        int w = 200, h = 200; // dikdörtgen boyutu
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        roi = new Rect(new Point(x, y), new Point(x + w, y + h));
    }
    public void drawDetections(Mat frame,List<Detection> dets) {
        Log.d(TAG, "-drawDetections- çağırıldı...");
        /*for (Detection d : dets) {
            Point tl = new Point(d.x1, d.y1);
            Point br = new Point(d.x2, d.y2);
            Imgproc.rectangle(frame, tl, br, new Scalar(0,255,0), 2);
            Imgproc.putText(
                    frame,
                    d.label + String.format(" %.2f", d.score),
                    new Point(d.x1, d.y1 - 8),
                    Imgproc.FONT_HERSHEY_SIMPLEX,
                    0.6,
                    new Scalar(255,255,255),
                    2
            );
            Log.i(TAG,d.classId + "Detection" + d.label + d.x1 + d.y1 + d.x2 + d.y2 + d.score);
        }*/
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
    private void drawDetectionsOnMat(Mat frame, List<Detection> dets) {
        /*for (Detection d : dets) {
            Point tl = new Point(d.x1, d.y1);
            Point br = new Point(d.x2, d.y2);
            Imgproc.rectangle(frame, tl, br, new Scalar(0,255,0), 2);
            Imgproc.putText(
                    frame,
                    d.label + String.format(" %.2f", d.score),
                    new Point(d.x1, d.y1 - 8),
                    Imgproc.FONT_HERSHEY_SIMPLEX,
                    0.6,
                    new Scalar(255,255,255),
                    2
            );
        }*/
    }

}
