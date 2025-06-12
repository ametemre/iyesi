package com.kurmez.iyesi.utilities.Ai;
import android.graphics.Bitmap;
import android.graphics.RectF;

import org.opencv.core.Mat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TFLiteHelpers {
    public interface ModelProcessor {
        float[][][] runInference(ByteBuffer inputBuffer, int[] outputShape);
        void close();
    }

}


