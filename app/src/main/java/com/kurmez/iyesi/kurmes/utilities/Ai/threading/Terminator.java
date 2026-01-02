package com.kurmez.iyesi.kurmes.utilities.Ai.threading;

import android.content.Context;
import android.util.Log;

import com.kurmez.iyesi.kurmes.utilities.Helpers;

import org.tensorflow.lite.Delegate;
import org.tensorflow.lite.Interpreter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Advanced AI lifecycle manager with Threading integration
 * - Automatic GPU/CPU fallback
 * - Resource-efficient task execution
 * - Thread-safe state management
 */
public class Terminator {

    private static final String TAG = "Terminator";

    public enum AppState { IDLE, RUNNING, STANDBY }

    // Threading integration
    private final String delegateKey;
    private final boolean useGpu;

    // Core components
    private final Context context;
    private final Supplier<Interpreter> videoInterpreterFactory;
    private final Supplier<Interpreter> soundInterpreterFactory;
    private final List<float[][][]> videoBuffer;
    private final List<float[]> soundBuffer;

    // Resources
    private Interpreter videoInterpreter;
    private Interpreter soundInterpreter;
    private Delegate delegate;

    // State management
    private volatile AppState currentState = AppState.IDLE;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
    private final List<Future<?>> submittedTasks = new CopyOnWriteArrayList<>();

    public Terminator(Context context,
                      Supplier<Interpreter> videoInterpreterFactory,
                      Supplier<Interpreter> soundInterpreterFactory,
                      String delegateKey,
                      boolean useGpu,
                      List<float[][][]> videoBuffer,
                      List<float[]> soundBuffer) {
        this.context = context;
        this.videoInterpreterFactory = videoInterpreterFactory;
        this.soundInterpreterFactory = soundInterpreterFactory;
        this.delegateKey = delegateKey;
        this.useGpu = useGpu;
        this.videoBuffer = videoBuffer;
        this.soundBuffer = soundBuffer;
    }

    private synchronized void initResources() {
        try {
            // Get or create delegate using Threading
            if (useGpu) {
                delegate = ThreadService
                        .getInstance()
                        .getOrCreateDelegate(delegateKey, context);
            }

            // Create interpreters with optional delegate
            Interpreter.Options options = new Interpreter.Options();
            if (delegate != null) {
                options.addDelegate(delegate);
            }

            videoInterpreter = videoInterpreterFactory.get();
            soundInterpreter = soundInterpreterFactory.get();

            Log.i(TAG, "AI resources initialized" + (useGpu ? " with GPU" : " in CPU mode"));
        } catch (Exception e) {
            Log.e(TAG, "Resource initialization failed", e);
            cleanupResources();
            throw e;
        }
    }

    public synchronized void start() {
        if (currentState != AppState.IDLE) {
            Log.w(TAG, "Cannot start: not in IDLE state");
            return;
        }

        shutdownRequested.set(false);
        paused.set(false);

        // Initialize resources in computation thread
        ThreadService
                .getInstance()
                .submitComputation(() -> {
            initResources();
            currentState = AppState.RUNNING;
            Helpers.showToastSafe(context, "AI started");
        });
    }

    public synchronized void pause() {
        if (currentState != AppState.RUNNING) {
            Log.w(TAG, "Cannot pause: not RUNNING");
            return;
        }

        paused.set(true);
        currentState = AppState.STANDBY;
        Helpers.showToastSafe(context, "AI paused");
    }

    public synchronized void resume() {
        if (currentState != AppState.STANDBY) {
            Log.w(TAG, "Cannot resume: not STANDBY");
            return;
        }

        paused.set(false);
        currentState = AppState.RUNNING;
        Helpers.showToastSafe(context, "AI resumed");
    }

    /**
     * Fallback to CPU mode when GPU errors occur
     */
    private synchronized void turnaroundToCpu() {
        if (!useGpu || currentState != AppState.RUNNING) return;

        Log.i(TAG, "Falling back to CPU mode");
        Helpers.showToastSafe(context, "Switching to CPU mode");

        // Release GPU resources
        if (delegate != null) {
            ThreadService
                    .getInstance()
                    .releaseDelegate(delegateKey);
            delegate = null;
        }

        // Reinitialize without GPU
        if (videoInterpreter != null) videoInterpreter.close();
        if (soundInterpreter != null) soundInterpreter.close();

        videoInterpreter = videoInterpreterFactory.get();
        soundInterpreter = soundInterpreterFactory.get();
    }

    public synchronized void shutdown() {
        if (currentState == AppState.IDLE) return;

        shutdownRequested.set(true);
        currentState = AppState.IDLE;

        // Cancel pending tasks
        for (Future<?> task : submittedTasks) {
            if (!task.isDone()) task.cancel(true);
        }
        submittedTasks.clear();

        // Cleanup in background
        ThreadService
                .getInstance()
                .submitIo(this::cleanupResources);
    }

    private void cleanupResources() {
        if (videoInterpreter != null) {
            videoInterpreter.close();
            videoInterpreter = null;
        }

        if (soundInterpreter != null) {
            soundInterpreter.close();
            soundInterpreter = null;
        }

        if (delegate != null) {
            ThreadService
                    .getInstance()
                    .releaseDelegate(delegateKey);
            delegate = null;
        }

        videoBuffer.clear();
        soundBuffer.clear();

        Helpers.showToastSafe(context, "AI stopped");
        Log.i(TAG, "Resources cleaned up");
    }

    // State check methods
    public boolean isPaused() { return paused.get(); }
    public boolean isRunning() { return currentState == AppState.RUNNING; }
    public boolean isIdle() { return currentState == AppState.IDLE; }
}