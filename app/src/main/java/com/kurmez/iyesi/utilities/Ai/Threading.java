// Threading.java
package com.kurmez.iyesi.utilities.Ai;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Log;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES31;
import android.util.Log;
/**
 * Helper sınıf: UI thread dışındaki işleri çalıştırmak ve
 * UI güncellemelerini main thread’e post etmek için kullanılır.
 */
public class Threading {
    // UI thread’e görev göndermek için global handler
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Verilen Runnable’ı yeni bir background thread’te çalıştırır.
     */
    public static void runOnBackground(Runnable task) {
        new Thread(task).start();
    }
    /**
     * Verilen Runnable’ı UI (main) thread’te çalıştırmak üzere post eder.
     */
    public static void runOnUi(Runnable task) {
        mainHandler.post(task);
    }
    // ToDo : GPU worker
    // ToDo : CPU worker
    // ToDo : orchestrator worker
    // ToDo : MemoryControl

    public void availableCPU() {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        Log.d("AvailableProcessors", "Number of available threads: " + availableProcessors);
    }
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

        GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 0, grpCount, 0); // X yönü
        int threadCount = grpCount[0];

        EGL14.eglDestroySurface(display, surf);
        EGL14.eglDestroyContext(display, ctx);
        EGL14.eglTerminate(display);

        return Math.max(1, Math.min(threadCount, 6)); // pratik sınır: max 6
    }

    public void availableGPU() {
        // 1. EGL Display aç
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) {
            Log.e("GPUInfo", "Unable to get EGL display");
            return;
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            Log.e("GPUInfo", "Unable to initialize EGL");
            return;
        }

        // 2. Uygun bir config seç
        int[] attribList = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(display, attribList, 0, configs, 0, configs.length, numConfigs, 0);
        if (numConfigs[0] == 0) {
            Log.e("GPUInfo", "No EGL configs match");
            EGL14.eglTerminate(display);
            return;
        }

        // 3. Context oluştur
        int[] ctxAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        EGLContext context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (context == null || context == EGL14.EGL_NO_CONTEXT) {
            Log.e("GPUInfo", "Failed to create EGL context");
            EGL14.eglTerminate(display);
            return;
        }

        // 4. Offscreen surface (Pbuffer) oluştur ve makeCurrent yap
        int[] surfAttribs = {
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
        };
        EGLSurface surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfAttribs, 0);
        if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
            Log.e("GPUInfo", "Failed to create Pbuffer surface");
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
            return;
        }
        EGL14.eglMakeCurrent(display, surface, surface, context);

        // 5. GPU bilgilerini sor
        String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
        String vendor   = GLES20.glGetString(GLES20.GL_VENDOR);
        String versionGL= GLES20.glGetString(GLES20.GL_VERSION);
        Log.d("GPUInfo", "Renderer: " + renderer);
        Log.d("GPUInfo", "Vendor:   " + vendor);
        Log.d("GPUInfo", "Version:  " + versionGL);

        // 6. Kaynakları temizle
        EGL14.eglDestroySurface(display, surface);
        EGL14.eglDestroyContext(display, context);
        EGL14.eglTerminate(display);
    }
    public void availableGPUThreads() {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) {
            Log.e("GPUThreads", "EGL display alınamadı");
            return;
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            Log.e("GPUThreads", "EGL initialize başarısız");
            return;
        }

        // Basit bir config seç
        int[] attribList = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,  // ES2 bit ama ES3 context isteğiyle çalışır
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0);
        if (numConfigs[0] == 0) {
            Log.e("GPUThreads", "EGL config bulunamadı");
            EGL14.eglTerminate(display);
            return;
        }

        // OpenGL ES 3.1 context
        int[] ctxAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE
        };
        EGLContext ctx = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) {
            Log.e("GPUThreads", "EGL context oluşturulamadı");
            EGL14.eglTerminate(display);
            return;
        }

        // Offscreen Pbuffer surface
        int[] surfAttribs = {
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
        };
        EGLSurface surf = EGL14.eglCreatePbufferSurface(display, configs[0], surfAttribs, 0);
        EGL14.eglMakeCurrent(display, surf, surf, ctx);

        // Maksimum iş grubu sayısı (x, y, z)
        int[] grpCount = new int[1];
        GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 0, grpCount, 0);
        int gcX = grpCount[0];
        GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 1, grpCount, 0);
        int gcY = grpCount[0];
        GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 2, grpCount, 0);
        int gcZ = grpCount[0];
        Log.d("GPUThreads", "Max work group count: [" + gcX + ", " + gcY + ", " + gcZ + "]");

        // Maksimum iş grubu boyutu (x, y, z)
        GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_SIZE, 0, grpCount, 0);
        int gsX = grpCount[0];
        GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_SIZE, 1, grpCount, 0);
        int gsY = grpCount[0];
        GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_SIZE, 2, grpCount, 0);
        int gsZ = grpCount[0];
        Log.d("GPUThreads", "Max work group size:  [" + gsX + ", " + gsY + ", " + gsZ + "]");

        // Temizlik
        EGL14.eglDestroySurface(display, surf);
        EGL14.eglDestroyContext(display, ctx);
        EGL14.eglTerminate(display);
    }

}


