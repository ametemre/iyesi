package com.kurmez.iyesi.kurmes.utilities.Ai.delegate;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.tensorflow.lite.Delegate;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.nnapi.NnApiDelegate;

import java.io.File;
import java.lang.reflect.Field;

/**
 * TFLite delegate manager: choose NNAPI > GPU > CPU.
 */
public class TFLiteDelegateManager {
    private static final String TAG = "TFLiteDelegateManager";

    /** Build Interpreter.Options with best delegate. */
    public static Interpreter.Options getInterpreterOptions(Context context) {
        Interpreter.Options opts = new Interpreter.Options();
        Delegate delegate = createBestDelegate(context);
        if (delegate != null) {
            opts.addDelegate(delegate);
            Log.i(TAG, "Delegate added: " + delegate.getClass().getSimpleName());
        } else {
            Log.w(TAG, "No hardware delegate, using CPU");
        }
        opts.setNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        return opts;
    }

    private static Delegate createBestDelegate(Context context) {
        // Try NNAPI first
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            NnApiDelegate nn = tryInitNnApi(context);
            if (nn != null) return nn;
        }
        // Fallback to GPU
        GpuDelegate gpu = tryInitGpu(context);
        if (gpu != null) return gpu;
        // CPU fallback
        return null;
    }

    private static NnApiDelegate tryInitNnApi(Context context) {
        try {
            NnApiDelegate.Options opts = new NnApiDelegate.Options();
            opts.setAllowFp16(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q);
            File ext = context.getExternalCacheDir();
            if (ext != null) opts.setCacheDir(ext.getAbsolutePath());
            return new NnApiDelegate(opts);
        } catch (Exception e) {
            Log.w(TAG, "NNAPI init failed", e);
            return null;
        }
    }

    private static GpuDelegate tryInitGpu(Context context) {
        try {
            CompatibilityList compatList = new CompatibilityList();
            if (!compatList.isDelegateSupportedOnThisDevice()) {
                Log.w(TAG, "GPU not supported");
                return null;
            }
            GpuDelegate.Options opts = compatList.getBestOptionsForThisDevice();
            opts.setPrecisionLossAllowed(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q);

            // Reflectively set mSerializationDir for GPU caching
            String cacheDir = getValidCacheDir(context, "/sdcard/tflite_gpu_cache");
            if (cacheDir != null) {
                Field f = GpuDelegate.Options.class.getDeclaredField("mSerializationDir");
                f.setAccessible(true);
                f.set(opts, cacheDir);
            }

            return new GpuDelegate(opts);
        } catch (Exception e) {
            Log.w(TAG, "GPU init failed", e);
            return null;
        }
    }

    private static String getValidCacheDir(Context context, String defaultPath) {
        File cacheDir = new File(defaultPath);
        if (cacheDir.exists() && cacheDir.canWrite()) {
            return defaultPath;
        }
        File internal = context.getCacheDir();
        if (internal != null && internal.canWrite()) {
            return internal.getAbsolutePath();
        }
        return null;
    }
}
