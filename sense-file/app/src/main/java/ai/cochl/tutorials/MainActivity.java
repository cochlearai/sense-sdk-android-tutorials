// Copyright 2020-2026 Cochl.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
//
// sense-file: file-mode inference (Java). Ported from the full runnable app at
//   ../sense-sdk-android-tutorials/sense-file
// updated to the CURRENT AAR API. The helper classes (Adapter, Item, CopyAssets,
// InitProgressBarTask, OnItemClickListener) and layouts are unchanged -- take
// them from the tutorials repo.

package ai.cochl.tutorials;

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
    // Set your project key here before running.
    private final String projectKey = "YOUR_PROJECT_KEY";

    private final String configPath = "config/config.json";
    private final int SENSE_SDK_REQUEST_CODE = 0;
    private final String[] permissionList = { Manifest.permission.INTERNET };

    // runtime state (instance fields — no statics)
    private Sense sense = null;
    private HandlerThread senseThread;
    private Handler senseHandler;
    private volatile boolean senseReady = false;
    private Adapter adapter;
    private boolean fileSelected = false;
    private Item selectedItem = null;

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
            if (!senseReady)
                return;

            if (!fileSelected) {
                fileSelected = true;
                btnPredict.setEnabled(true);
            }
            selectedItem = adapter.GetItem(position);
        });
        btnPredict.setOnClickListener(v -> {
            if (!fileSelected)
                return;

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
                    return;
                }

                sense.init(projectKey, configFile.getAbsolutePath());

                // Optional: drive features at runtime via the Sense controls
                // (after init(); AAD/AGC are stream-mode only):
                // sense.setSensitivity("HIGH"); // VERY_LOW|LOW|NORMAL|HIGH|VERY_HIGH
                // sense.setTagSensitivity("Footstep", "LOW"); // per-tag override
                // sense.setResultSummaryEnabled(true);

                senseReady = true;

                addWavFiles();
            } catch (CochlException e) {
                runOnUiThread(() -> {
                    GetToast(this, e.getMessage()).show();
                    safeExit(/* fromInitFail= */true); // suppress terminate() when init is failed
                });
            } finally {
                // hide progress
                runOnUiThread(() -> {
                    if (progressTask != null)
                        progressTask.stop();
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
        Log.e("SENSE", filePath);
        JSONObject result = sense.predict(filePath);

        try {
            // predict() returns every inference window as {"frames":[ ... ]}.
            // When result summary is enabled, print its lines
            // ("At X.X-Y.Ys, [tag] was detected"); a window with no summary line prints
            // nothing.
            // Otherwise print the per-window pretty JSON.
            JSONArray frames = result.optJSONArray("frames");
            final boolean summaryOn = sense.isResultSummaryEnabled();
            if (frames != null) {
                for (int i = 0; i < frames.length(); ++i) {
                    JSONObject frame = frames.getJSONObject(i);
                    if (summaryOn) {
                        JSONArray summaries = frame.optJSONArray(keyResultSummary);
                        if (summaries != null) {
                            for (int j = 0; j < summaries.length(); ++j) {
                                Append(summaries.getString(j));
                            }
                        }
                    } else {
                        Append(formatFrame(frame));
                    }
                }
            }
        } catch (JSONException e) {
            runOnUiThread(() -> GetToast(this, e.getMessage()).show());
        } finally {
            // hide progress
            runOnUiThread(() -> {
                if (progressTask != null)
                    progressTask.stop();
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
                final boolean[] done = { false };
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
                    if (!done[0])
                        try {
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

    // Formats one frame result as the pretty-printed JSON.
    private static String formatFrame(JSONObject frame) throws JSONException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"start_time\": ").append(num(frame.getDouble("start_time"))).append(",\n");
        sb.append("  \"end_time\": ").append(num(frame.getDouble("end_time"))).append(",\n");
        sb.append("  \"prediction_time_ms\": ").append(num(frame.getDouble("prediction_time_ms"))).append(",\n");
        JSONArray tags = frame.optJSONArray("tags");
        if (tags == null || tags.length() == 0) {
            sb.append("  \"tags\": []");
        } else {
            sb.append("  \"tags\": [\n");
            for (int i = 0; i < tags.length(); ++i) {
                JSONObject tag = tags.getJSONObject(i);
                sb.append("    {\n");
                sb.append("      \"name\": \"").append(tag.getString("name")).append("\",\n");
                sb.append("      \"probability\": ").append(num(tag.getDouble("probability"))).append("\n");
                sb.append(i == tags.length() - 1 ? "    }\n" : "    },\n");
            }
            sb.append("  ]");
        }
        sb.append("\n}");
        return sb.toString();
    }

    // 6 significant digits with trailing zeros dropped
    // (2.0 -> "2", 0.75 -> "0.75", 3.26014 -> "3.26014").
    private static String num(double v) {
        String s = String.format(java.util.Locale.US, "%.6g", v);
        if (s.indexOf('e') < 0 && s.indexOf('E') < 0 && s.indexOf('.') >= 0) {
            int end = s.length();
            while (end > 0 && s.charAt(end - 1) == '0')
                end--;
            if (end > 0 && s.charAt(end - 1) == '.')
                end--;
            s = s.substring(0, end);
        }
        return s;
    }

    // UI helpers
    private void Append(String msg) {
        // Prefer working with Editable to avoid extra String allocations
        android.text.Editable e = event.getEditableText();
        if (e != null) {
            e.append(msg);
            e.append('\n');
        } else {
            event.append(msg);
            event.append("\n");
        }

        // Truncate to ~8KB from the head to avoid growing forever
        final int maxLen = 8192;
        CharSequence text = event.getText();
        int len = text.length();
        if (len > maxLen) {
            int cutFrom = Math.max(0, len - maxLen);
            int firstNewline = -1;
            for (int i = cutFrom; i < len; i++) {
                if (text.charAt(i) == '\n') {
                    firstNewline = i;
                    break;
                }
            }
            int deleteUntil = (firstNewline >= 0 ? firstNewline + 1 : cutFrom);

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
            if (event == null)
                return;

            android.text.Layout layout = event.getLayout();
            if (layout == null) {
                event.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        event.getViewTreeObserver().removeOnPreDrawListener(this);
                        android.text.Layout l = event.getLayout();
                        if (l != null) {
                            int scrollAmount = l.getLineTop(event.getLineCount()) - event.getHeight();
                            event.scrollTo(0, Math.max(scrollAmount, 0));
                        }
                        return true;
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
