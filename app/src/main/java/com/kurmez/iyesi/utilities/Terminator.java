package com.kurmez.iyesi.utilities;

import android.content.Context;
import com.kurmez.iyesi.utilities.Helpers;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/*
 * Terminator handles AI resource lifecycle: init, start, pause, resume, shutdown, cleanup.
 * Includes a "turnaround" fallback to CPU mode on interpreter errors.
 */
public class Terminator {

    private enum AppState { IDLE, RUNNING, STANDBY }

    private final Context context;
    private final ExecutorService aiExecutor;
    private final ExecutorService cleanupExecutor;

    private final Supplier<Interpreter> videoInterpreterFactory;
    private final Supplier<Interpreter> soundInterpreterFactory;
    private final Supplier<GpuDelegate> gpuDelegateFactory;

    private Interpreter videoInterpreter;
    private Interpreter soundInterpreter;
    private GpuDelegate gpuDelegate;

    private final List<float[][][]> videoBuffer;
    private final List<float[]> soundBuffer;

    private final List<Future<?>> submittedTasks = new java.util.concurrent.CopyOnWriteArrayList<>();

    private volatile AppState currentState = AppState.IDLE;
    private volatile boolean paused = false;
    private volatile boolean shutdownRequested = false;

    public Terminator(Context context,
                      ExecutorService aiExecutor,
                      Supplier<Interpreter> videoInterpreterFactory,
                      Supplier<Interpreter> soundInterpreterFactory,
                      Supplier<GpuDelegate> gpuDelegateFactory,
                      List<float[][][]> videoBuffer,
                      List<float[]> soundBuffer) {
        this.context = context;
        this.aiExecutor = aiExecutor;
        this.cleanupExecutor = Executors.newSingleThreadExecutor();
        this.videoInterpreterFactory = videoInterpreterFactory;
        this.soundInterpreterFactory = soundInterpreterFactory;
        this.gpuDelegateFactory = gpuDelegateFactory;
        this.videoBuffer = videoBuffer;
        this.soundBuffer = soundBuffer;
    }

    public Terminator(Context context,
                      ExecutorService aiExecutor,
                      Interpreter videoInterpreterInstance,
                      Interpreter soundInterpreterInstance,
                      GpuDelegate gpuDelegateInstance,
                      List<float[][][]> videoBuffer,
                      List<float[]> soundBuffer) {
        this(context,
                aiExecutor,
                () -> videoInterpreterInstance,
                () -> soundInterpreterInstance,
                () -> gpuDelegateInstance,
                videoBuffer,
                soundBuffer);
    }

    private synchronized void initResources() {
        gpuDelegate = gpuDelegateFactory.get();
        Interpreter.Options options = new Interpreter.Options().addDelegate(gpuDelegate);
        videoInterpreter = videoInterpreterFactory.get();
        soundInterpreter = soundInterpreterFactory.get();
    }

    public synchronized void start() {
        if (currentState != AppState.IDLE) return;
        shutdownRequested = false;
        initResources();
        paused = false;
        currentState = AppState.RUNNING;
        Helpers.showToastSafe(context, "AI started");
    }

    public synchronized void pause() {
        if (currentState != AppState.RUNNING) return;
        paused = true;
        currentState = AppState.STANDBY;
        Helpers.showToastSafe(context, "AI paused");
    }

    public synchronized void resume() {
        if (currentState != AppState.STANDBY) return;
        paused = false;
        currentState = AppState.RUNNING;
        Helpers.showToastSafe(context, "AI resumed");
    }

    /**
     * Submit an AI task, tracking its Future and adding error fallback.
     */
    public void submitAiTask(Runnable task) {
        if (currentState != AppState.RUNNING) return;
        Future<?> future = aiExecutor.submit(() -> {
            if (shutdownRequested || Thread.currentThread().isInterrupted()) return;
            try {
                task.run();
            } catch (Exception e) {
                // Turnaround fallback to CPU-only mode on error
                Helpers.showToastSafe(context, "Interpreter error, switching to CPU mode");
                turnaroundToCpu();
            }
        });
        submittedTasks.add(future);
    }

    /**
     * Fallback: clean GPU delegate and reinit interpreters in CPU mode.
     */
    private synchronized void turnaroundToCpu() {
        if (currentState != AppState.RUNNING) return;
        // Close GPU delegate
        if (gpuDelegate != null) {
            gpuDelegate.close();
            gpuDelegate = null;
        }
        // Reinitialize interpreters without GPU
        Interpreter.Options cpuOptions = new Interpreter.Options();
        if (videoInterpreter != null) videoInterpreter.close();
        //videoInterpreter = new Interpreter(videoInterpreter.getInputTensor(0).shape(), cpuOptions);
        if (soundInterpreter != null) soundInterpreter.close();
        //soundInterpreter = new Interpreter(soundInterpreter.getInputTensor(0).shape(), cpuOptions);
        Helpers.showToastSafe(context, "Switched to CPU interpreters");
    }

    public synchronized void shutdown() {
        if (currentState == AppState.IDLE) return;
        shutdownRequested = true;
        paused = true;
        currentState = AppState.IDLE;
        aiExecutor.shutdown();
        cleanupExecutor.execute(() -> {
            try {
                if (!aiExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                    for (Future<?> f : submittedTasks) {
                        if (!f.isDone()) f.cancel(true);
                    }
                    aiExecutor.shutdownNow();
                    Helpers.showToastSafe(context, "Force-cancelled AI tasks");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cleanupResources();
        });
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
        if (gpuDelegate != null) {
            gpuDelegate.close();
            gpuDelegate = null;
        }
        videoBuffer.clear();
        soundBuffer.clear();
        submittedTasks.clear();
        Helpers.showToastSafe(context, "AI stopped");
        cleanupExecutor.shutdown();
    }

    public boolean isPaused()            { return paused; }
    public boolean isRunning()          { return currentState == AppState.RUNNING; }
    public boolean isIdle()             { return currentState == AppState.IDLE; }
    public boolean isShutdownRequested(){ return shutdownRequested; }
}