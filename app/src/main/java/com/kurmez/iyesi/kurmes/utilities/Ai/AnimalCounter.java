package com.kurmez.iyesi.kurmes.utilities.Ai;

import android.content.Context;
import androidx.core.util.Consumer;

import com.kurmez.iyesi.kurmes.Kurmes;
import org.opencv.core.*;
import org.opencv.dnn.Dnn;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * AnimalCounter: AI tabanlı nesne tespiti + sayaç + izleyici + çizim
 * Ai sınıfıyla birlikte çalışır; OpenCV kullanır.
 */
public class AnimalCounter {
    private final Ai ai;
    private final int INPUT_SIZE = Kurmes.DETECTION_INPUT_SIZE;
    private static final float CONF_THRESH = 0.5f;
    private static final float NMS_THRESH  = 0.45f;

    // YOLOv8 Nano anchor setleri (w,h) üçerli
    private static final float[][] ANCHORS = {
            {10,13, 16,30, 33,23},
            {30,61, 62,45, 59,119},
            {116,90, 156,198, 373,326}
    };
    private static final int[] STRIDES = {8, 16, 32};

    // Tracking veri yapıları
    private int nextTrackId = 0;
    private final Map<Integer, Point> tracks = new HashMap<>();
    private final Map<Integer, Integer> disappeared = new HashMap<>();
    private final Set<Integer> countedIds = new HashSet<>();
    public int totalAnimalCount = 0;

    public AnimalCounter(Context context) throws Exception {
        // Ai: (context, soundModel, videoModel)
        ai = new Ai(context, null, "yolov8n.tflite","coco_labels.txt");
    }

    /**
     * Her kare için çağrılır. callback içinde işlenmiş <Mat> döner.
     */
    public void processFrame(Mat frame, Consumer<Mat> callback) {
        // 1) Ön işleme: BGR->RGB resize normalize
        Mat rgb = new Mat();
        Imgproc.cvtColor(frame, rgb, Imgproc.COLOR_BGR2RGB);
        Mat resized = new Mat();
        Imgproc.resize(rgb, resized, new Size(INPUT_SIZE, INPUT_SIZE));

        // ByteBuffer -> float array
        float[][][][] input = preprocess(resized);

        // 2) İnference
        ai.predictVideo(input, rawOutput -> {
            // 3) Decode + NMS
            List<DetectionBox> dets = decodeAndNms(rawOutput, frame.size());
            // 4) Track & Count
            updateTracking(dets);
            // 5) Çizim
            Mat annotated = annotate(frame, dets);
            callback.accept(annotated);
        });
    }

    private float[][][][] preprocess(Mat img) {
        ByteBuffer bb = ByteBuffer.allocateDirect(INPUT_SIZE*INPUT_SIZE*3*4)
                .order(ByteOrder.nativeOrder());
        for (int y=0; y<INPUT_SIZE; y++) {
            for (int x=0; x<INPUT_SIZE; x++) {
                double[] p = img.get(y, x);
                bb.putFloat((float)(p[0]/255.0));
                bb.putFloat((float)(p[1]/255.0));
                bb.putFloat((float)(p[2]/255.0));
            }
        }
        bb.rewind();
        float[][][][] input = new float[1][INPUT_SIZE][INPUT_SIZE][3];
        for (int y=0; y<INPUT_SIZE; y++) {
            for (int x=0; x<INPUT_SIZE; x++) {
                input[0][y][x][0] = bb.getFloat();
                input[0][y][x][1] = bb.getFloat();
                input[0][y][x][2] = bb.getFloat();
            }
        }
        return input;
    }

    private List<DetectionBox> decodeAndNms(float[][][] raw, Size orig) {
        // TODO: Sigmoid+exp+anchor dönüşümleri uygulayın
        // Örnek stub: hiçbir kutu döndürmez
        List<DetectionBox> boxes = new ArrayList<>();
        // ... decode adımları burada ...

        // NMS
        List<Rect> rects = new ArrayList<>();
        MatOfRect mRect = new MatOfRect();
        MatOfFloat mConf = new MatOfFloat();
        List<Float> confs = new ArrayList<>();
        for (DetectionBox b : boxes) {
            rects.add(b.rect);
            confs.add(b.score);
        }
        mRect.fromList(rects);
        mConf.fromList(confs);
        MatOfInt idx = new MatOfInt();
        Dnn.NMSBoxes(mRect, mConf, CONF_THRESH, NMS_THRESH, idx);
        List<DetectionBox> out = new ArrayList<>();
        for (int i : idx.toArray()) out.add(boxes.get(i));
        return out;
    }

    private void updateTracking(List<DetectionBox> dets) {
        // Basit centroid tracker
        List<Point> centroids = new ArrayList<>();
        for (DetectionBox b : dets) centroids.add(
                new Point(b.rect.x + b.rect.width/2.0, b.rect.y + b.rect.height/2.0)
        );
        if (tracks.isEmpty()) {
            for (Point c : centroids) {
                tracks.put(nextTrackId, c);
                disappeared.put(nextTrackId, 0);
                countedIds.add(nextTrackId);
                totalAnimalCount++;
                nextTrackId++;
            }
        } else {
            // TODO: Eşleme mantığını uygulayın
        }
    }

    private Mat annotate(Mat frame, List<DetectionBox> dets) {
        Mat out = frame.clone();
        for (DetectionBox b : dets) {
            // ID çıkarmak için proximity match yapabilirsiniz
            Imgproc.rectangle(out, b.rect.tl(), b.rect.br(), new Scalar(0,255,0),2);
            Imgproc.putText(out, String.valueOf(b.id),
                    new Point(b.rect.x, b.rect.y-5),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, new Scalar(0,255,0),2);
        }
        return out;
    }

    // Basit veri tutucu
    private static class DetectionBox {
        Rect rect; float score; int cls; int id;
        DetectionBox(Rect r, float s, int c) { rect=r; score=s; cls=c; }
    }
}
