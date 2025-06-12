package com.kurmez.iyesi.utilities.Ai;

import android.content.Context;
import android.graphics.Color;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.List;

public class Renderer {
    private final Context context;

    public Renderer(Context context) {
        this.context = context;
    }

    public void drawDetectionsOnFrame(Mat frame, List<Detection> detections) {
        for (Detection d : detections) {
            Point tl = new Point(d.x1, d.y1);
            Point br = new Point(d.x2, d.y2);
            Imgproc.rectangle(frame, tl, br, new Scalar(0, 255, 0), 2);
            Imgproc.putText(frame, d.label, tl, Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, new Scalar(255, 255, 255), 2);
        }
    }

    public void updateDetectedItemsList(LinearLayout layout, List<Detection> detections) {
        layout.removeAllViews();
        for (Detection detection : detections) {
            if (detection.score > 0.79) {
                TextView textView = new TextView(context);
                textView.setText(String.format("%s - %.2f%%", detection.label, detection.score * 100));
                textView.setTextColor(Color.WHITE);
                layout.addView(textView);
            }
        }

        if (layout.getChildCount() == 0) {
            TextView noResult = new TextView(context);
            noResult.setText("No strong detections");
            noResult.setTextColor(Color.GRAY);
            layout.addView(noResult);
        }
    }
}
