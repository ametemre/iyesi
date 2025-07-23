package com.kurmez.iyesi.utilities.Ai.threading;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLES31;
import android.util.Log;

import org.tensorflow.lite.Delegate;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.nnapi.NnApiDelegate;

import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Advanced hardware resource management for TFLite with automatic
 * precision adjustment and hardware-aware delegation.
 */
public final class Threading {
    private static final String TAG = "Threading";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Map<String, Delegate> ACTIVE_DELEGATES = new HashMap<>();

    // Hardware capability cache
    private static Boolean isHighEndDevice;
    private static Integer cpuCoreCount;
    private static GpuInfo gpuInfo;

    // Thread pools
    private static ExecutorService computationExecutor;
    private static ExecutorService ioExecutor;

    static {
        initExecutors();
    }

    // GPU info container
    public static class GpuInfo {
        public final String renderer;
        public final String vendor;
        public final String version;
        public final int[] maxWorkGroupCount = new int[3];
        public final int[] maxWorkGroupSize = new int[3];

        public GpuInfo(String renderer, String vendor, String version) {
            this.renderer = renderer;
            this.vendor = vendor;
            this.version = version;
        }
    }

    // Executor initialization with priority threads
    private static void initExecutors() {
        int cores = getCpuCoreCount();
        computationExecutor = Executors.newFixedThreadPool(Math.max(2, cores / 2), new PriorityThreadFactory(Thread.NORM_PRIORITY + 1));
        ioExecutor = Executors.newCachedThreadPool(new PriorityThreadFactory(Thread.NORM_PRIORITY));
    }

    // Thread factory with configurable priority
    private static class PriorityThreadFactory implements ThreadFactory {
        private final int priority;

        public PriorityThreadFactory(int priority) {
            this.priority = priority;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setPriority(priority);
            return t;
        }
    }

    /* Hardware Introspection */
    public static int getCpuCoreCount() {
        if (cpuCoreCount == null) {
            cpuCoreCount = Runtime.getRuntime().availableProcessors();
            Log.i(TAG, "CPU cores detected: " + cpuCoreCount);
        }
        return cpuCoreCount;
    }

    public static synchronized GpuInfo getGpuInfo() {
        if (gpuInfo != null) return gpuInfo;

        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) {
            Log.w(TAG, "No EGL display");
            return null;
        }

        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            Log.w(TAG, "EGL initialization failed");
            return null;
        }

        try {
            // Get basic GPU info
            int[] configAttr = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            EGL14.eglChooseConfig(display, configAttr, 0, configs, 0, 1, numConfigs, 0);

            if (numConfigs[0] == 0) {
                Log.w(TAG, "No compatible EGL config");
                return null;
            }

            int[] contextAttr = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            EGLContext context = EGL14.eglCreateContext(
                    display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttr, 0
            );

            int[] surfaceAttr = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
            EGLSurface surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttr, 0);

            EGL14.eglMakeCurrent(display, surface, surface, context);

            // Extract GPU info
            String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
            String vendor = GLES20.glGetString(GLES20.GL_VENDOR);
            String versionStr = GLES20.glGetString(GLES20.GL_VERSION);

            gpuInfo = new GpuInfo(renderer, vendor, versionStr);
            Log.i(TAG, "GPU: " + renderer + " | " + vendor + " | " + versionStr);

            // Try to get advanced compute capabilities (ES 3.1+)
            try {
                int[] maxCount = new int[3];
                int[] maxSize = new int[3];

                GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 0, maxCount, 0);
                GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 1, maxCount, 1);
                GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 2, maxCount, 2);

                GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_SIZE, 0, maxSize, 0);
                GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_SIZE, 1, maxSize, 1);
                GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_SIZE, 2, maxSize, 2);

                System.arraycopy(maxCount, 0, gpuInfo.maxWorkGroupCount, 0, 3);
                System.arraycopy(maxSize, 0, gpuInfo.maxWorkGroupSize, 0, 3);

                Log.d(TAG, "Max work group count: ["
                        + maxCount[0] + ", " + maxCount[1] + ", " + maxCount[2] + "]");
                Log.d(TAG, "Max work group size: ["
                        + maxSize[0] + ", " + maxSize[1] + ", " + maxSize[2] + "]");
            } catch (Exception e) {
                Log.w(TAG, "OpenGL ES 3.1 not supported", e);
            }

            return gpuInfo;
        } finally {
            EGL14.eglMakeCurrent(display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
            );
            EGL14.eglTerminate(display);
        }
    }

    /* Device Classification */
    public static synchronized boolean isHighEndDevice(Context context) {
        if (isHighEndDevice != null) return isHighEndDevice;

        int cores = getCpuCoreCount();
        CompatibilityList compatList = new CompatibilityList();
        boolean gpuSupported = compatList.isDelegateSupportedOnThisDevice();

        // High-end criteria: ≥6 cores and GPU support
        isHighEndDevice = (cores >= 6) && gpuSupported;
        Log.i(TAG, "Device classification: " +
                (isHighEndDevice ? "HIGH-END" : "MID/LOW-END"));

        return isHighEndDevice;
    }

    /* Precision Management */
    public static boolean shouldUseFp16(Context context) {
        // Use FP16 unless high-end device
        return !isHighEndDevice(context);
    }

    /* Delegate Management */
    public static synchronized Delegate getOrCreateDelegate(String key, Context context) {
        if (ACTIVE_DELEGATES.containsKey(key)) {
            return ACTIVE_DELEGATES.get(key);
        }

        Delegate delegate = createOptimalDelegate(context);
        ACTIVE_DELEGATES.put(key, delegate);
        return delegate;
    }
    public static Delegate createOptimalDelegate(Context context) {
        // First try NNAPI
        NnApiDelegate nnDelegate = tryCreateNnApiDelegate(context);
        if (nnDelegate != null) return nnDelegate;

        // Then try GPU
        GpuDelegate gpuDelegate = tryCreateGpuDelegate(context);
        if (gpuDelegate != null) return gpuDelegate;

        // Fallback to CPU
        Log.w(TAG, "No hardware delegate available");
        return null;
    }
    private static NnApiDelegate tryCreateNnApiDelegate(Context context) {
        try {
            NnApiDelegate.Options options = new NnApiDelegate.Options();
            options.setAllowFp16(shouldUseFp16(context));

            File cacheDir = context.getExternalCacheDir();
            if (cacheDir != null && cacheDir.canWrite()) {
                options.setCacheDir(cacheDir.getAbsolutePath());
            }

            NnApiDelegate delegate = new NnApiDelegate(options);
            Log.i(TAG, "Created NNAPI delegate");
            return delegate;
        } catch (Exception e) {
            Log.w(TAG, "NNAPI delegate creation failed", e);
            return null;
        }
    }
    private static GpuDelegate tryCreateGpuDelegate(Context context) {
        try {
            CompatibilityList compatList = new CompatibilityList();
            if (!compatList.isDelegateSupportedOnThisDevice()) {
                Log.w(TAG, "GPU delegate not supported");
                return null;
            }

            GpuDelegate.Options options = compatList.getBestOptionsForThisDevice();
            options.setPrecisionLossAllowed(shouldUseFp16(context));

            // Set cache directory using reflection
            try {
                File cacheDir = new File(context.getCacheDir(), "tflite_gpu_cache");
                if (!cacheDir.exists()) cacheDir.mkdirs();

                if (cacheDir.canWrite()) {
                    Field cacheField = GpuDelegate.Options.class.getDeclaredField("mSerializationDir");
                    cacheField.setAccessible(true);
                    cacheField.set(options, cacheDir.getAbsolutePath());
                    Log.d(TAG, "GPU cache dir: " + cacheDir);
                }
            } catch (Exception e) {
                Log.w(TAG, "Couldn't set GPU cache dir", e);
            }

            GpuDelegate delegate = new GpuDelegate(options);
            Log.i(TAG, "Created GPU delegate (FP16: " + options.isPrecisionLossAllowed() + ")");
            return delegate;
        } catch (Exception e) {
            Log.w(TAG, "GPU delegate creation failed", e);
            return null;
        }
    }
    public static synchronized void releaseDelegate(String key) {
        Delegate delegate = ACTIVE_DELEGATES.remove(key);
        if (delegate != null) {
            if (delegate instanceof GpuDelegate) {
                ((GpuDelegate) delegate).close();
            } else if (delegate instanceof NnApiDelegate) {
                ((NnApiDelegate) delegate).close();
            }
            Log.i(TAG, "Released delegate: " + key);
        }
    }
    public static synchronized void releaseAllDelegates() {
        for (Delegate delegate : ACTIVE_DELEGATES.values()) {
            if (delegate instanceof GpuDelegate) {
                ((GpuDelegate) delegate).close();
            } else if (delegate instanceof NnApiDelegate) {
                ((NnApiDelegate) delegate).close();
            }
        }
        ACTIVE_DELEGATES.clear();
        Log.i(TAG, "Released all delegates");
    }

    /* Thread Management */
    public static void runComputation(Runnable task) {
        computationExecutor.execute(task);
    }
    public static void runIo(Runnable task) {ioExecutor.execute(task);}
}