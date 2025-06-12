package com.kurmez.iyesi.utilities.Ai;

import android.content.Context;
import android.graphics.Color;
import android.graphics.RectF;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Tracker {
    private final Map<Integer, RectF> trackedObjects = new HashMap<>();
    private final Set<Integer> countedIds = new HashSet<>();
    private int nextObjectId = 0;
    private int totalCount = 0;

    public int assignObjectId(RectF box) {
        for (Map.Entry<Integer, RectF> entry : trackedObjects.entrySet()) {
            if (iou(box, entry.getValue()) > 0.5f) {
                entry.setValue(box);
                return entry.getKey();
            }
        }
        int id = nextObjectId++;
        trackedObjects.put(id, box);
        return id;
    }

    public boolean isCounted(int objId) {
        return countedIds.contains(objId);
    }

    public void markCounted(int objId) {
        if (countedIds.add(objId)) totalCount++;
    }

    public int getTotalCount() {
        return totalCount;
    }

    private float iou(RectF a, RectF b) {
        float left = Math.max(a.left, b.left);
        float top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right);
        float bottom = Math.min(a.bottom, b.bottom);
        float inter = Math.max(0, right - left) * Math.max(0, bottom - top);
        float union = a.width() * a.height() + b.width() * b.height() - inter;
        return union > 0 ? (inter / union) : 0f;
    }
}
