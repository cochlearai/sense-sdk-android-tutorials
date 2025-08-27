package ai.cochl.examples;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Objects;

import ai.cochl.sensesdk.CochlException;
import ai.cochl.sensesdk.Sense;

public class MainActivity extends AppCompatActivity {
    private final String projectKey = "Your project key";
    private final String configPath = "config/config.json";
    private final int SENSE_SDK_REQUEST_CODE = 0;
    private final String[] permissionList = {Manifest.permission.INTERNET};

    // runtime state (instance fields — no statics)
    private Sense sense = null;
    private HandlerThread senseThread;
    private Handler senseHandler;
    private volatile boolean senseReady = false;
    private Adapter adapter;
    private boolean fileSelected = false;
    private Item selectedItem = null;

    private boolean resultSummary;
    private static final String keyResultSummary = "summaries";

    private boolean settingsButtonClicked = false;

    // progress indicator (uses your InitProgressBarTask)
    private InitProgressBarTask progressTask = null;

    private TextView event;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Objects.requireNonNull(getSupportActionBar()).hide();

        event = findViewById(R.id.event);
        event.setMovementMethod(new ScrollingMovementMethod());

        RecyclerView recyclerView = findViewById(R.id.files);
        recyclerView.setLayoutManager(new LinearLayoutManager(this, RecyclerView.VERTICAL, false));
        adapter = new Adapter();
        recyclerView.setAdapter(adapter);

        Button btnPredict = findViewById(R.id.predict);
        Button btnClear = findViewById(R.id.clear);

        btnPredict.setEnabled(false);
        btnClear.setOnClickListener(v -> event.setText(""));

        adapter.SetOnItemClickListener((viewHolder, view, position) -> {
            if (!senseReady) return;

            if (!fileSelected) {
                fileSelected = true;
                btnPredict.setEnabled(true);
            }
            selectedItem = adapter.GetItem(position);
        });
        btnPredict.setOnClickListener(v -> {
            if (!fileSelected) return;

            new Thread(() -> {
                sensePredict(selectedItem.GetFile());
                fileSelected = false;
                selectedItem = null;
                runOnUiThread(() -> btnPredict.setEnabled(false));
            }).start();
        });

        if (!checkPermissions()) {
            requestPermissions();
        } else {
            senseInit();
        }
    }

    private void senseInit() {
        if (!new CopyAssets(this).copyAssets()) {
            GetToast(this, "Unable to copy assets.").show();
            finish();
            return;
        }

        // show progress with InitProgressBarTask
        progressTask = new InitProgressBarTask(new Handler(Looper.getMainLooper()),
                findViewById(R.id.inc_progress_bar));
        Thread progressThread = new Thread(progressTask, "InitProgress");
        progressThread.start();

        senseThread = new HandlerThread("SenseThread");
        senseThread.start();
        senseHandler = new Handler(senseThread.getLooper());

        senseHandler.post(() -> {
            try {
                sense = Sense.getInstance();

                File configFile = new File(this.getExternalFilesDir(null), configPath);
                if (!configFile.exists()) {
                    runOnUiThread(() -> {
                        GetToast(this, "Config file not found: " + configFile.getAbsolutePath()).show();
                        safeExit();
                    });
                }

                sense.init(projectKey, configFile.getAbsolutePath());

                senseReady = true;
                resultSummary = sense.getParameters().resultSummary.enable;

                addWavFiles();

                Append("Selected tags: ");
                StringBuilder sb = new StringBuilder();
                for (String tag : sense.getSelectedTags())
                    sb.append("** ").append(tag).append("\n");
                Append(sb.toString());
            } catch (CochlException e) {
                runOnUiThread(() -> {
                    GetToast(this, e.getMessage()).show();
                    safeExit(/*fromInitFail=*/true); // suppress terminate() when init is failed
                });
            } finally {
                // hide progress
                runOnUiThread(() -> {
                    if (progressTask != null) progressTask.stop();
                });
            }
        });
    }

    private void sensePredict(File file) {
        // show progress with InitProgressBarTask
        progressTask = new InitProgressBarTask(new Handler(Looper.getMainLooper()),
                findViewById(R.id.inc_progress_bar));
        Thread progressThread = new Thread(progressTask, "InitProgress");
        progressThread.start();

        String filePath = file.getAbsolutePath();
        Log.e("SENSE", file.getAbsolutePath());
        JSONObject result = sense.predict(filePath);
        Log.e("SENSE", file.getAbsolutePath());

        try {
            if (resultSummary) {
                JSONArray abbreviations = result.getJSONArray(keyResultSummary);
                Append("<Result summary>");
                for (int i = 0; i < abbreviations.length(); ++i) {
                    Append(abbreviations.getString(i));
                    // Even if you use the result abbreviation, you can still get precise
                    // results like below if necessary:
                    // Append(result.getJSONObject("result").toString(2));
                }
            } else {
                Append(result.getJSONObject("result").toString(2));
            }
        } catch (JSONException e) {
            runOnUiThread(() -> GetToast(this, e.getMessage()).show());
        } finally {
            // hide progress
            runOnUiThread(() -> {
                if (progressTask != null) progressTask.stop();
            });
        }
    }

    private void safeExit() {
        safeExit(false);
    }

    private void safeExit(boolean fromInitFail) {
        // Terminate sense
        if (senseHandler != null && senseThread != null) {
            if (senseReady && !fromInitFail) {
                final Object latch = new Object();
                final boolean[] done = {false};
                senseHandler.post(() -> {
                    try {
                        sense.terminate();
                    } catch (Exception ignored) {
                    }
                    synchronized (latch) {
                        done[0] = true;
                        latch.notifyAll();
                    }
                });

                synchronized (latch) {
                    if (!done[0]) try {
                        latch.wait(1500);
                    } catch (InterruptedException ignored) {
                    }
                }
            }

            // Release SenseThread
            senseThread.quitSafely();
            try {
                senseThread.join(1500);
            } catch (InterruptedException ignored) {
            }
            senseThread = null;
            senseHandler = null;
        }

        sense = null;
        senseReady = false;

        if (progressTask != null) {
            progressTask.stop();
            progressTask = null;
        }

        finishAndRemoveTask();
    }

    private void addWavFiles() {
        File parent = this.getExternalFilesDir(null);
        for (File file : Objects.requireNonNull(Objects.requireNonNull(parent).listFiles())) {
            if (file.isFile()) {
                String filename = file.getName();
                adapter.AddItem(new Item(filename, new File(parent, filename)));
            }
        }
        this.runOnUiThread(adapter::notifyDataSetChanged);
    }

    // UI helpers
    private void Append(String msg) {
        // Prefer working with Editable to avoid extra String allocations
        android.text.Editable e = event.getEditableText();
        if (e != null) {
            e.append(msg);
            e.append('\n');           // Editable supports append(char)
        } else {
            event.append(msg);        // TextView.append returns void (no chaining)
            event.append("\n");       // Use String, not char
        }

        // Truncate to ~8KB from the head to avoid growing forever
        final int maxLen = 8192;
        CharSequence text = event.getText();
        int len = text.length();
        if (len > maxLen) {
            int cutFrom = Math.max(0, len - maxLen);
            // Find a newline at/after the cutoff so we drop whole lines
            int firstNewline = -1;
            for (int i = cutFrom; i < len; i++) {
                if (text.charAt(i) == '\n') {
                    firstNewline = i;
                    break;
                }
            }
            int deleteUntil = (firstNewline >= 0 ? firstNewline + 1 : cutFrom);

            // Delete efficiently if we have an Editable
            if (e != null) {
                e.delete(0, deleteUntil);
            } else {
                event.setText(text.subSequence(deleteUntil, len));
            }
        }

        // Scroll after layout is ready
        event.removeCallbacks(scrollToBottomOnce);
        event.post(scrollToBottomOnce);
    }

    // Runs on UI thread; only accesses 'event'
    private final Runnable scrollToBottomOnce = new Runnable() {
        @Override
        public void run() {
            if (event == null) return;

            android.text.Layout layout = event.getLayout();
            if (layout == null) {
                // Layout not ready yet → defer exactly once to after layout pass.
                event.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        // Remove this listener and try again now that we're about to draw
                        event.getViewTreeObserver().removeOnPreDrawListener(this);
                        android.text.Layout l = event.getLayout();
                        if (l != null) {
                            int scrollAmount = l.getLineTop(event.getLineCount()) - event.getHeight();
                            event.scrollTo(0, Math.max(scrollAmount, 0));
                        }
                        return true; // keep drawing
                    }
                });
                return;
            }

            int scrollAmount = layout.getLineTop(event.getLineCount()) - event.getHeight();
            event.scrollTo(0, Math.max(scrollAmount, 0));
        }
    };

    private Toast GetToast(Context context, String msg) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.LTGRAY);
        gd.setCornerRadius(20);

        TextView tvToast = new TextView(context);
        tvToast.setText(msg);
        tvToast.setBackground(gd);
        tvToast.setPadding(16, 8, 16, 8);

        Toast toast = new Toast(context);
        toast.setDuration(Toast.LENGTH_LONG);
        toast.setView(tvToast);
        return toast;
    }

    private boolean checkPermissions() {
        for (String permission : permissionList) {
            if (checkCallingOrSelfPermission(permission) == PackageManager.PERMISSION_DENIED) {
                return false;
            }
        }
        return true;
    }

    private void requestPermissions() {
        // one shot is enough
        requestPermissions(permissionList, SENSE_SDK_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == SENSE_SDK_REQUEST_CODE) {
            boolean allPermissionsGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false;
                    break;
                }
            }

            if (allPermissionsGranted) {
                senseInit();
            } else {
                boolean shouldShowRationale = false;
                for (String permission : permissions) {
                    if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                        shouldShowRationale = true;
                        break;
                    }
                }

                if (shouldShowRationale) {
                    GetToast(this, "You need to allow the permission to use this app.").show();
                    finish();
                } else {
                    showPermissionSettingsDialog();
                }
            }
        }
    }

    private void showPermissionSettingsDialog() {
        AlertDialog alertDialog = new AlertDialog.Builder(this)
                .setMessage("Permission has been denied. Would you like to enable it in settings?")
                .setPositiveButton("Go to Settings", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                    settingsButtonClicked = true;
                }).setNegativeButton("Cancel", (dialog, which) -> {
                    GetToast(this, "You need to allow the permission to use this app.").show();
                    finish();
                }).create();

        alertDialog.show();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (settingsButtonClicked) {
            settingsButtonClicked = false;

            if (checkPermissions()) {
                senseInit();
            } else {
                GetToast(this, "You need to allow the permission to use this app.").show();
                finish();
            }
        }
    }
}