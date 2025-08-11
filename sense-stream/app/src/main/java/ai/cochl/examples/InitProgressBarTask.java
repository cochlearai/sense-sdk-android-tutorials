package ai.cochl.examples;

import android.os.Handler;
import android.view.View;

import java.util.concurrent.CountDownLatch;

/**
 * Shows a View while some background task is running.
 * Call {@link #stop()} once the task is finished.
 */
public final class InitProgressBarTask implements Runnable {

    private final Handler uiHandler;
    private final View bar;
    private final CountDownLatch done = new CountDownLatch(1);

    public InitProgressBarTask(Handler uiHandler, View bar) {
        this.uiHandler = uiHandler;
        this.bar = bar;
    }

    @Override
    public void run() {
        uiHandler.post(() -> bar.setVisibility(View.VISIBLE));

        try {                       // Wait until stop() is called
            done.await();
        } catch (InterruptedException ignored) {
        }

        uiHandler.post(() -> bar.setVisibility(View.INVISIBLE));
    }

    /**
     * Hide the bar and unblock the thread
     */
    public void stop() {
        done.countDown();
    }
}
