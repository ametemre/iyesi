package com.kurmez.iyesi.utilities.Ai;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

public class TFLiteInputPreprocessor {
    private final TFLiteInputMapper mapper;

    public TFLiteInputPreprocessor(TFLiteInputMapper mapper) {
        this.mapper = mapper;
    }

    public float[][][][] map(Mat inputFrame) {
        int w      = mapper.getScaledWidth();
        int h      = mapper.getScaledHeight();
        int modelW = w + 2 * mapper.getOffsetX();
        int modelH = h + 2 * mapper.getOffsetY();

        // 1. Resize ve RGBA→RGB
        Mat resized = new Mat();
        Imgproc.resize(inputFrame, resized, new Size(w, h));
        Imgproc.cvtColor(resized, resized, Imgproc.COLOR_RGBA2RGB);

        // 2. Padding ile model boyutuna oturt
        Mat padded = Mat.zeros(modelH, modelW, CvType.CV_8UC3);
        resized.copyTo(
                padded.submat(
                        mapper.getOffsetY(),
                        mapper.getOffsetY() + h,
                        mapper.getOffsetX(),
                        mapper.getOffsetX() + w
                )
        );

        // 3. byte[] ile veriyi oku
        byte[] data = new byte[modelW * modelH * 3];
        padded.get(0, 0, data);

        // 4. float tensor'a çevir
        float[][][][] result = new float[1][modelH][modelW][3];
        for (int y = 0; y < modelH; y++) {
            for (int x = 0; x < modelW; x++) {
                int base = (y * modelW + x) * 3;
                // byte → unsigned int → float
                result[0][y][x][0] = (data[base]     & 0xFF) / 255.0f;
                result[0][y][x][1] = (data[base + 1] & 0xFF) / 255.0f;
                result[0][y][x][2] = (data[base + 2] & 0xFF) / 255.0f;
            }
        }

        // Temizlik
        resized.release();
        padded.release();

        return result;
    }
}
