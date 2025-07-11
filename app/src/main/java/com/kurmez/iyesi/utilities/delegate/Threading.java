package com.kurmez.iyesi.utilities.delegate;

import android.app.Activity;
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

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Modern Threading utility: GPU delegate init, UI/Main thread helpers, and hardware introspection.
 * Yüksek seviyede thread/gpu orchestrasyonu için tasarlanmıştır.
 */
public class Threading {
    private static final String TAG = "Threading";
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());


    //---------------------------------------------------------------------------------------------- Önbelleklenmiş reflection (sınıf yüklendiğinde bir kereliğe)
    private static Method setPrecisionMethod;
    public static Method setInferencePrefMethod;
    private static Field sustainedSpeedField;
    static {
        try {
            setPrecisionMethod = GpuDelegate.Options.class.getMethod("setPrecisionLossAllowed", boolean.class);
            setInferencePrefMethod = GpuDelegate.Options.class.getMethod("setInferencePreference", int.class);
            sustainedSpeedField = GpuDelegate.Options.class.getField("INFERENCE_PREFERENCE_SUSTAINED_SPEED");
        } catch (NoSuchMethodException | NoSuchFieldException e) {
            Log.d(TAG, "Advanced GPU options not available, using defaults");
        } catch (Exception e) {
            Log.e(TAG, "Reflection Failed",e);
        }
    }
    //---------------------------------------------------------------------------------------------- Önbelleklenmiş reflection (sınıf yüklendiğinde bir kereliğe)

    // --- GPU Delegate Init ---
    public static GpuDelegate initGpuDelegate(Context context) {
        try {
            if (shouldSkipGpuForDevice()) {
                Log.w(TAG, "Skipping GPU for this device model");
                return null;
            }
            CompatibilityList compatList = new CompatibilityList();
            if (!compatList.isDelegateSupportedOnThisDevice()) {
                Log.w(TAG, "GPU delegate not supported on this device");
                return null;
            }
/*            if (!compatList.isOperationSupportedOnDevice("TRANSPOSE", CompatibilityList.DEVICE_GPU)) {
                Log.w(TAG, "Transpose op not GPU-compatible, skipping GPU");
                return null;
            }
 */
            GpuDelegate.Options options = new GpuDelegate.Options();
            options.setQuantizedModelsAllowed(true);
            //--------------------------------------------------------------------------------------OpenGL desteklenmiyorsa OpenCL kullan{
            try {
                Method setGlBackend = GpuDelegate.Options.class.getMethod("setGlBackend", int.class);
                Field glBackendField = GpuDelegate.Options.class.getField("GL");
                setGlBackend.invoke(options, glBackendField.getInt(null));
            } catch (Exception e) {
                Log.d(TAG, "GL backend setting not available");
            }
            //--------------------------------------------------------------------------------------OpenGL desteklenmiyorsa OpenCL kullan}
            // Try advanced options (reflective)
            try {
                setPrecisionMethod.invoke(options, true);
                setInferencePrefMethod.invoke(options, sustainedSpeedField.getInt(null));
            }
            catch (Exception e) {
                Log.w(TAG, "Error setting GPU options", e);
            }
            GpuDelegate delegate = new GpuDelegate(options);
            try {
                Log.i(TAG, "GPU delegate initialized successfully");
                Interpreter.Options interpreterOptions = new Interpreter.Options();
                interpreterOptions.addDelegate(delegate);
                // Test with a tiny model if possible
                return delegate;
            } catch (Exception testException) {
                delegate.close();
                Log.w(TAG, "GPU delegate failed basic test", testException);
                return null;
            }
        } catch (Exception e) {
            Log.w(TAG, "GPU acceleration unavailable", e);
            return null;
        }
    }
    private static boolean shouldSkipGpuForDevice() {
        String model = android.os.Build.MODEL;
        // Örnek blacklist:
        return model.contains("NONE") || model.contains("POCO") || model.contains("SM-") /* vs... */;
    }
    // --- Main/UI Thread helpers ---
    public static void runOnBackground(Runnable task) {
        new Thread(task).start();
    }
    public static void runOnUiThread(Runnable r, Context context) {
        if (context instanceof Activity) ((Activity)context).runOnUiThread(r);
        else mainHandler.post(r);
    }
    // --- Hardware Introspection ---
    public static int getSuggestedGPUThreadCount() {
        final int[] grpCount = new int[1];
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) return 1;
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return 1;
        int[] attribList = { EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0);
        if (numConfigs[0] == 0) return 1;
        int[] ctxAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE };
        EGLContext ctx = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (ctx == null) return 1;
        int[] surfAttribs = { EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE };
        EGLSurface surf = EGL14.eglCreatePbufferSurface(display, configs[0], surfAttribs, 0);
        EGL14.eglMakeCurrent(display, surf, surf, ctx);
        GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 0, grpCount, 0); // X axis
        int threadCount = grpCount[0];
        EGL14.eglDestroySurface(display, surf);
        EGL14.eglDestroyContext(display, ctx);
        EGL14.eglTerminate(display);
        return Math.max(1, Math.min(threadCount, 6)); // safe cap
    }
    public static void availableGPU() {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "Unable to get EGL display");
            return;
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            Log.e(TAG, "Unable to initialize EGL");
            return;
        }
        int[] attribList = { EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(display, attribList, 0, configs, 0, configs.length, numConfigs, 0);
        if (numConfigs[0] == 0) {
            Log.e(TAG, "No EGL configs match");
            EGL14.eglTerminate(display);
            return;
        }
        int[] ctxAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        EGLContext context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (context == null || context == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "Failed to create EGL context");
            EGL14.eglTerminate(display);
            return;
        }
        int[] surfAttribs = { EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE };
        EGLSurface surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfAttribs, 0);
        if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "Failed to create Pbuffer surface");
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
            return;
        }
        EGL14.eglMakeCurrent(display, surface, surface, context);
        String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
        String vendor   = GLES20.glGetString(GLES20.GL_VENDOR);
        String versionGL= GLES20.glGetString(GLES20.GL_VERSION);
        Log.d(TAG, "Renderer: " + renderer);
        Log.d(TAG, "Vendor:   " + vendor);
        Log.d(TAG, "Version:  " + versionGL);
        EGL14.eglDestroySurface(display, surface);
        EGL14.eglDestroyContext(display, context);
        EGL14.eglTerminate(display);
    }
    public static void availableCPU() {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        Log.d(TAG, "Number of available threads: " + availableProcessors);
    }
    public static void availableGPUThreads() {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "EGL display alınamadı");
            return;
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            Log.e(TAG, "EGL initialize başarısız");
            return;
        }
        int[] attribList = { EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0);
        if (numConfigs[0] == 0) {
            Log.e(TAG, "EGL config bulunamadı");
            EGL14.eglTerminate(display);
            return;
        }
        int[] ctxAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE };
        EGLContext ctx = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "EGL context oluşturulamadı");
            EGL14.eglTerminate(display);
            return;
        }
        int[] surfAttribs = { EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE };
        EGLSurface surf = EGL14.eglCreatePbufferSurface(display, configs[0], surfAttribs, 0);
        EGL14.eglMakeCurrent(display, surf, surf, ctx);
        int[] grpCount = new int[1];
        GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 0, grpCount, 0);
        int gcX = grpCount[0];
        GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 1, grpCount, 0);
        int gcY = grpCount[0];
        GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 2, grpCount, 0);
        int gcZ = grpCount[0];
        Log.d(TAG, "Max work group count: [" + gcX + ", " + gcY + ", " + gcZ + "]");
        GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_SIZE, 0, grpCount, 0);
        int gsX = grpCount[0];
        GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_SIZE, 1, grpCount, 0);
        int gsY = grpCount[0];
        GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_SIZE, 2, grpCount, 0);
        int gsZ = grpCount[0];
        Log.d(TAG, "Max work group size:  [" + gsX + ", " + gsY + ", " + gsZ + "]");
        EGL14.eglDestroySurface(display, surf);
        EGL14.eglDestroyContext(display, ctx);
        EGL14.eglTerminate(display);
    }
}
