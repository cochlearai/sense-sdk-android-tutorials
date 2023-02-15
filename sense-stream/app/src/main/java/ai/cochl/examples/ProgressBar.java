package ai.cochl.examples;

import android.os.Handler;
import android.view.View;

public class ProgressBar implements Runnable {
    private final Handler handler;
    private final View progressBar;
    private boolean isTaskDone;

    public ProgressBar(Handler handler, View progressBar) {
        this.isTaskDone = false;
        this.handler = handler;
        this.progressBar = progressBar;
    }

    @Override
    public void run() {
        handler.post(() -> progressBar.setVisibility(View.VISIBLE));
        while (!isTaskDone) {
            try {
                //noinspection BusyWait
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        handler.post(() -> progressBar.setVisibility(View.INVISIBLE));
    }

    public void setStop() {
        isTaskDone = true;
    }
}
