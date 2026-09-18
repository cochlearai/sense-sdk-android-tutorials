// Copyright 2020-2026 Cochl.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
//
// sense-stream: real-time microphone inference (Java). Ported from the full
// runnable app at
//   ../sense-sdk-android-tutorials/sense-stream
// updated to the CURRENT AAR API. Helper classes (CopyAssets, InitProgressBarTask)
// and layouts are unchanged -- take them from the tutorials repo.

package ai.cochl.tutorials;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.Objects;

import ai.cochl.sensesdk.CochlException;
import ai.cochl.sensesdk.Sense;

public class MainActivity extends AppCompatActivity {
    // Set your project key here before running.
    private final String projectKey = "YOUR_PROJECT_KEY";

    private final String configPath = "config/config.json";
    private final int SENSE_SDK_REQUEST_CODE = 0;
    private final String[] permissionList = { Manifest.permission.INTERNET, Manifest.permission.RECORD_AUDIO };
    // Microphone capture rate in Hz, fixed to the model's rate. Capture rates
    // BELOW the model rate are not supported (upsampling cannot recover the
    // missing high frequencies the model needs); a higher device rate would be
    // downsampled by the SDK.
    private final int SAMPLE_RATE = 22050;
    // Inference hop in seconds, read from config.json's "default_hopsize" at
    // init (see parseHopSize). Falls back to DEFAULT_HOP_SIZE when the key is
    // absent/invalid; mirrors the cpp/python tutorials so the mic buffer stays
    // aligned with the SDK's hop without a hardcoded, manually-synced constant.
    private static final double DEFAULT_HOP_SIZE = 1.0;
    private double hopSize = DEFAULT_HOP_SIZE;

    // runtime state (instance fields — no statics)
    private Sense sense = null;
    private HandlerThread senseThread;
    private Handler senseHandler;
    private volatile boolean senseReady = false;
    private AudioRecord recorder = null;
    private boolean pause = false;
    private Handler mainHandler = null;
    private Thread audioThread = null;
    private volatile boolean running = false;

    private float[] audioSampleFloat = null;
    private short[] audioSampleShort = null;
    private boolean isFloatSample = false;
    private static final String keyResultSummary = "summaries";

    private boolean settingsButtonClicked = false;

    // progress indicator (uses your InitProgressBarTask)
    private InitProgressBarTask progressTask = null;

    private TextView event;

    // messages
    private static final int AUDIO_READY = 1;
    private static final int EXIT_APP = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Objects.requireNonNull(getSupportActionBar()).hide();

        event = findViewById(R.id.event);
        event.setMovementMethod(new ScrollingMovementMethod());

        Button btnPause = findViewById(R.id.pause);
        Button btnClear = findViewById(R.id.clear);

        btnPause.setOnClickListener(v -> {
            String strBtnPause = getResources().getString(R.string.pause);
            String strBtnResume = getResources().getString(R.string.resume);
            pause = btnPause.getText().equals(strBtnPause);
            btnPause.setText(pause ? strBtnResume : strBtnPause);
        });
        btnClear.setOnClickListener(v -> event.setText(""));

        if (!checkPermissions()) {
            requestPermissions();
        } else {
            senseInit();
        }
    }

    // Initialise Sense; only on success do we start audio.
    private void senseInit() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_DENIED) {
            GetToast(this, "You need to allow the permission to use this app.").show();
            finish();
            return;
        }

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

                // Read the inference hop from config.json so the mic buffer
                // matches the SDK's cadence (fallback keeps the prior 1.0s).
                hopSize = parseHopSize(configFile, DEFAULT_HOP_SIZE);

                // Optional: drive features at runtime via the Sense controls
                // (after init(); AAD/AGC are stream-mode only):
                // sense.setSensitivity("HIGH"); // VERY_LOW|LOW|NORMAL|HIGH|VERY_HIGH
                // sense.setTagSensitivity("Footstep", "LOW"); // per-tag override
                // sense.setResultSummaryEnabled(true);
                // sense.enableAudioActivityDetection(true); // stream-mode only
                // sense.enableAutomaticGainControl(true); // stream-mode only

                senseReady = true;

                runOnUiThread(() -> {
                    initMainHandler();

                    // start audio capture ONLY after successful init
                    startAudioThread();
                });
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

    private void initMainHandler() {
        mainHandler = new MainHandler(this, Looper.getMainLooper());
    }

    private void startAudioThread() {
        if (running)
            return;
        running = true;
        audioThread = new Thread(this::readAudioData, "AudioThread");
        audioThread.start();
    }

    private void readAudioData() {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendExitMessage("RECORD_AUDIO not granted");
            return;
        }

        // Audio bit depth: AudioRecord's PCM encoding sets what the mic captures;
        // the array type read below then selects the SDK format via
        // sense.predict(...) -- float[] -> FLOAT32, short[] -> INT16.
        // SDK-supported formats: FLOAT32, INT16, INT32, FLOAT64.
        // Android AudioRecord can capture: ENCODING_PCM_FLOAT (= FLOAT32) and
        // ENCODING_PCM_16BIT (= INT16); PCM_24BIT_PACKED/PCM_32BIT need API 31+.
        // FLOAT64 is a valid SDK format, but Android has no 64-bit-float PCM
        // encoding, so it cannot be captured live (file input only).
        // This tutorial prefers FLOAT (best quality), falling back to 16-bit.
        AudioRecord rec = tryCreateRecorder(MediaRecorder.AudioSource.UNPROCESSED, AudioFormat.ENCODING_PCM_FLOAT);
        if (rec == null)
            rec = tryCreateRecorder(MediaRecorder.AudioSource.DEFAULT, AudioFormat.ENCODING_PCM_16BIT);
        if (rec == null) {
            sendExitMessage("Failed to init AudioRecord");
            return;
        }
        recorder = rec;

        // Buffer one hop of audio per read (hopSize seconds, from config.json).
        int bufferSize = Math.max(1, (int) Math.round(SAMPLE_RATE * recorder.getChannelCount() * hopSize));
        final boolean isFloat = recorder.getAudioFormat() == AudioFormat.ENCODING_PCM_FLOAT;

        try {
            recorder.startRecording();

            while (running && recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                int statusRead;
                if (isFloat) {
                    float[] buf = new float[bufferSize];
                    statusRead = recorder.read(buf, 0, buf.length, AudioRecord.READ_BLOCKING);
                    if (statusRead <= 0)
                        throw new IllegalStateException("Failed to read audio data");
                    // send a CLONE to avoid concurrent mutation
                    mainHandler.obtainMessage(AUDIO_READY, buf).sendToTarget();
                } else {
                    short[] buf = new short[bufferSize];
                    statusRead = recorder.read(buf, 0, buf.length, AudioRecord.READ_BLOCKING);
                    if (statusRead <= 0)
                        throw new IllegalStateException("Failed to read audio data");
                    mainHandler.obtainMessage(AUDIO_READY, buf).sendToTarget();
                }
            }
        } catch (Exception e) {
            sendExitMessage(e.toString());
        } finally {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
            recorder = null;
        }
        // no auto-exit here; lifecycle manages shutdown
    }

    // Reads "default_hopsize" (seconds) from config.json; returns the fallback
    // when the key is absent, non-positive, or the file/JSON cannot be parsed.
    private static double parseHopSize(File configFile, double fallback) {
        try {
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(configFile))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
            }
            double value = new JSONObject(sb.toString()).optDouble("default_hopsize", fallback);
            return value > 0.0 ? value : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    // Try to build a recorder; return null if unsupported.
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private AudioRecord tryCreateRecorder(int audioSource, int encoding) {
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, encoding);
        if (min <= 0)
            return null;
        try {
            AudioRecord r = new AudioRecord(audioSource, SAMPLE_RATE, channelConfig, encoding, min);
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                return null;
            }
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    private void sendExitMessage(String reason) {
        if (mainHandler != null) {
            mainHandler.obtainMessage(EXIT_APP, reason).sendToTarget();
        }
    }

    private void sensePredict(Object buf) {
        try {
            performSensePredict(buf);
        } catch (CochlException e) {
            sendExitMessage(e.toString());
        }
    }

    private void performSensePredict(Object buf) {
        // First frame: allocate sliding window of 2x frame
        if (audioSampleFloat == null && audioSampleShort == null) {
            if (buf instanceof float[]) {
                float[] b = (float[]) buf;
                isFloatSample = true;
                audioSampleFloat = new float[b.length * 2];
                System.arraycopy(b, 0, audioSampleFloat, b.length, b.length);
            } else {
                short[] b = (short[]) buf;
                isFloatSample = false;
                audioSampleShort = new short[b.length * 2];
                System.arraycopy(b, 0, audioSampleShort, b.length, b.length);
            }
            return;
        }

        JSONObject frameResult;
        if (isFloatSample) {
            float[] b = (float[]) buf;
            float[] win = audioSampleFloat;
            System.arraycopy(win, b.length, win, 0, b.length);
            System.arraycopy(b, 0, win, b.length, b.length);
            frameResult = sense.predict(win, SAMPLE_RATE);
        } else {
            short[] b = (short[]) buf;
            short[] win = audioSampleShort;
            System.arraycopy(win, b.length, win, 0, b.length);
            System.arraycopy(b, 0, win, b.length, b.length);
            frameResult = sense.predict(win, SAMPLE_RATE);
        }

        try {
            // When result summary is enabled, print its lines
            // ("At X.X-Y.Ys, [tag] was detected", or "Listening...");
            // a window with no summary line prints nothing.
            // Otherwise print the per-window pretty JSON.
            if (sense.isResultSummaryEnabled()) {
                JSONArray summaries = frameResult.optJSONArray(keyResultSummary);
                if (summaries != null) {
                    for (int i = 0; i < summaries.length(); ++i) {
                        Append(summaries.getString(i));
                    }
                }
            } else {
                Append(formatFrame(frameResult));
            }
        } catch (JSONException e) {
            sendExitMessage(e.toString());
        }
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
            while (end > 0 && s.charAt(end - 1) == '0') end--;
            if (end > 0 && s.charAt(end - 1) == '.') end--;
            s = s.substring(0, end);
        }
        return s;
    }

    // Graceful app exit without System.exit(0).
    private void exitApp(String reason) {
        GetToast(this, "Exiting app due to: " + reason).show();
        safeExit();
    }

    private void safeExit() {
        safeExit(false);
    }

    private void safeExit(boolean fromInitFail) {
        // stop audio and SDK first
        running = false;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
            recorder = null;
        }
        if (audioThread != null) {
            try {
                audioThread.join(1500);
            } catch (InterruptedException ignored) {
            }
            audioThread = null;
        }

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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;

        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
            recorder = null;
        }
        if (audioThread != null) {
            try {
                audioThread.join(1500);
            } catch (InterruptedException ignored) {
            }
            audioThread = null;
        }
        if (sense != null) {
            try {
                sense.terminate();
            } catch (Exception ignored) {
            }
            sense = null;
        }
        if (progressTask != null) {
            progressTask.stop();
            progressTask = null;
        }
    }

    // Handlers
    private static class MainHandler extends Handler {
        private final WeakReference<MainActivity> ref;

        MainHandler(MainActivity activity, Looper looper) {
            super(looper);
            ref = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            MainActivity a = ref.get();
            if (a == null)
                return;

            if (msg.what == AUDIO_READY) {
                a.sensePredict(msg.obj);
            } else if (msg.what == EXIT_APP) {
                String reason = (String) msg.obj;
                a.exitApp(reason);
            }
        }
    }

    // UI helpers
    private void Append(String msg) {
        if (pause || event == null)
            return;

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
