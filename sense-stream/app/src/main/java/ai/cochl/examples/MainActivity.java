package ai.cochl.examples;

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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.Objects;

import ai.cochl.sensesdk.CochlException;
import ai.cochl.sensesdk.Sense;

public class MainActivity extends AppCompatActivity {
    private final String projectKey = "Your project key";

    private final int SENSE_SDK_REQUEST_CODE = 0;
    private final String[] permissionList = {Manifest.permission.INTERNET,
            Manifest.permission.RECORD_AUDIO};

    private final int SAMPLE_RATE = 22050;

    private static Sense sense = null;
    private static boolean pause = false;
    private static Object audioSample = null;
    private static boolean resultAbbreviation;
    private static final String keyResultAbbreviation = "abbreviations";

    private static Handler mainHandler = null;
    private static BackgroundHandler backgroundHandler = null;
    private static final int AUDIO_READY = 1;
    private static final int EXIT_APP = 2;

    private boolean settingsButtonClicked = false;
    private ProgressBar progressBar;
    private TextView event;


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

            if (btnPause.getText().equals(strBtnPause)) {
                pause = true;
                btnPause.setText(strBtnResume);
            } else {
                pause = false;
                btnPause.setText(strBtnPause);
            }
        });
        btnClear.setOnClickListener(v -> event.setText(""));

        if (!checkPermissions()) {
            requestPermissions();
        } else {
            senseInit();
        }
    }

    private void senseInit() {
        int permission = ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO);
        if (permission == PackageManager.PERMISSION_DENIED) {
            GetToast(this, "You need to allow the permission to use this app.").show();
            finish();
        }

        new Thread(() -> {
            progressBar = new ProgressBar(new Handler(Looper.getMainLooper()), findViewById(R.id.inc_progress_bar));
            Thread thread = new Thread(progressBar);
            thread.start();

            sense = Sense.getInstance();

            Sense.Parameters senseParams = new Sense.Parameters();
            senseParams.metrics.retentionPeriod = 0;  // days
            senseParams.metrics.freeDiskSpace = 100;  // MB
            senseParams.metrics.pushPeriod = 30;      // seconds

            senseParams.deviceName = "Android device.";

            senseParams.logLevel = 0;

            senseParams.sensitivityControl.enable = true;
            senseParams.resultAbbreviation.enable = true;

            try {
                sense.init(projectKey, senseParams);
                resultAbbreviation = sense.getParameters().resultAbbreviation.enable;
            } catch (CochlException e) {
                runOnUiThread(() -> {
                    GetToast(this, e.getMessage()).show();
                    finish();
                });
            }

            initMainHandler();
            startBackgroundThread();

            runOnUiThread(() -> progressBar.setStop());
            try {
                thread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void initMainHandler() {
        mainHandler = new MainHandler(this, Looper.getMainLooper());
    }

    private void startBackgroundThread() {
        Thread backgroundThread = new Thread(() -> {
            // Prepare the looper and the message queue for this thread
            Looper.prepare();

            // Initialize the background handler
            initBackgroundHandler();

            // Start the data production method
            readAudioData();

            // Begin the loop to process the message queue (audio data)
            Looper.loop();
        });
        backgroundThread.start();
    }

    private void initBackgroundHandler() {
        backgroundHandler = new BackgroundHandler(Looper.myLooper());
    }

    // You can replace this part receiving audio data with what you want to use.
    private void readAudioData() {
        // AudioEncoding Inner Class
        class AudioEncoding {
            // For good performance, audio encoding allows only two, restricting the use of other encodings.
            public static final int ENCODING_PCM_FLOAT = AudioFormat.ENCODING_PCM_FLOAT;
            public static final int ENCODING_PCM_16BIT = AudioFormat.ENCODING_PCM_16BIT;

            private final int encoding;

            public AudioEncoding(int encodingType) {
                if (encodingType == ENCODING_PCM_FLOAT || encodingType == ENCODING_PCM_16BIT) {
                    this.encoding = encodingType;
                } else {
                    this.encoding = ENCODING_PCM_16BIT;  // default audio encoding
                }
            }

            public int getEncoding() {
                return encoding;
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }

        int audioSource = MediaRecorder.AudioSource.UNPROCESSED;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        AudioEncoding encoding = new AudioEncoding(AudioFormat.ENCODING_PCM_FLOAT);
        int bufferSizeInBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, encoding.getEncoding());

        AudioRecord recorder;
        try {
            recorder = new AudioRecord(audioSource, SAMPLE_RATE, channelConfig, encoding.getEncoding(), bufferSizeInBytes);
        } catch (Exception e) {
            sendExitMessage(e.toString());
            return;
        }

        // The buffer size must be obtained in the following way after calling the init method:
        int bufferSize = (int) (SAMPLE_RATE * recorder.getChannelCount() * sense.getHopSize());
        Object buffer = (recorder.getAudioFormat() == AudioFormat.ENCODING_PCM_FLOAT ? new float[bufferSize] : new short[bufferSize]);

        recorder.startRecording();

        while (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                int statusRead;

                // This method is a blocking method that reads audio data
                if (buffer instanceof float[]) {
                    statusRead = recorder.read((float[]) buffer, 0, bufferSize, AudioRecord.READ_BLOCKING);
                } else {
                    statusRead = recorder.read((short[]) buffer, 0, bufferSize, AudioRecord.READ_BLOCKING);
                }

                if (statusRead <= 0) {
                    throw new Exception("Failed to read audio data");
                }
            } catch (Exception e) {
                sendExitMessage(e.toString());
                break;
            }

            Message msg = mainHandler.obtainMessage(AUDIO_READY, buffer);
            mainHandler.sendMessage(msg);
        }

        try {
            recorder.stop();
            recorder.release();
            exitApp();
        } catch (Exception e) {
            sendExitMessage(e.toString());
        }
    }

    private void sendExitMessage(String reason) {
        Message msg = mainHandler.obtainMessage(EXIT_APP, reason);
        mainHandler.sendMessage(msg);
    }

    private void sensePredict(Object buf) {
        try {
            performSensePredict(buf);
        } catch (CochlException e) {
            sendExitMessage(e.toString());
        }
    }

    private void performSensePredict(Object buf) {
        if (audioSample == null) {  // first frame
            if (buf instanceof float[]) {
                float[] floatBuf = (float[]) buf;
                audioSample = new float[floatBuf.length * 2];
                System.arraycopy(floatBuf, 0, (float[]) audioSample, floatBuf.length, floatBuf.length);
            } else {
                short[] shortBuf = (short[]) buf;
                audioSample = new short[shortBuf.length * 2];
                System.arraycopy(shortBuf, 0, (short[]) audioSample, shortBuf.length, shortBuf.length);
            }
            return;
        }

        if (buf instanceof short[]) {
            short[] shortBuf = (short[]) buf;
            System.arraycopy((short[]) audioSample, shortBuf.length, (short[]) audioSample, 0, shortBuf.length);
            System.arraycopy(shortBuf, 0, (short[]) audioSample, shortBuf.length, shortBuf.length);
        } else {
            float[] floatBuf = (float[]) buf;
            System.arraycopy((float[]) audioSample, floatBuf.length, (float[]) audioSample, 0, floatBuf.length);
            System.arraycopy(floatBuf, 0, (float[]) audioSample, floatBuf.length, floatBuf.length);
        }

        JSONObject frameResult;
        if (audioSample instanceof short[]) {
            frameResult = sense.predict((short[]) audioSample, SAMPLE_RATE);
        } else {
            frameResult = sense.predict((float[]) audioSample, SAMPLE_RATE);
        }

        try {
            if (resultAbbreviation) {
                JSONArray abbreviations = frameResult.getJSONArray(keyResultAbbreviation);
                for (int i = 0; i < abbreviations.length(); ++i) {
                    Append(abbreviations.getString(i));
                }
                // Even if you use the result abbreviation, you can still get precise
                // results like below if necessary:
                // Append(printResult(frameResult));
            } else {
                Append("---------NEW FRAME---------");
                Append(printResult(frameResult));
            }
        } catch (JSONException e) {
            sendExitMessage(e.toString());
        }
    }

    private String printResult(JSONObject frameResult) throws JSONException {
        frameResult.remove(keyResultAbbreviation);
        return frameResult.toString(2);
    }

    @SuppressWarnings("unused")
    private String printResult(JSONObject frameResult, int indent) throws JSONException {
        frameResult.remove(keyResultAbbreviation);
        return frameResult.toString(indent);
    }

    private void exitApp() {
        finishAndRemoveTask(); // This method finishes the activity and removes it from the recent apps list.

        // Give the system some time to call onDestroy()
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            System.exit(0); // This line forcefully exits the app.
        }, 500); // Delay for half a second to allow onDestroy to be called
    }

    private void exitApp(String reason) {
        GetToast(this, "Exiting app due to: " + reason).show();
        exitApp();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (backgroundHandler != null) {
            backgroundHandler.getLooper().quit();
        }

        if (sense != null) {
            sense.terminate();
            sense = null;
        }
    }

    private static class MainHandler extends Handler {
        private final WeakReference<MainActivity> activityReference;

        MainHandler(MainActivity activity, Looper looper) {
            super(looper);
            activityReference = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            MainActivity activity = activityReference.get();
            if (activity != null) {
                if (msg.what == AUDIO_READY) {
                    activity.sensePredict(msg.obj);
                } else if (msg.what == EXIT_APP) {
                    String reason = (String) msg.obj;
                    activity.exitApp(reason);
                }
            }
        }
    }

    private static class BackgroundHandler extends Handler {
        BackgroundHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            // No messages expected in background handler in this tutorial
        }
    }

    private void Append(String msg) {
        if (pause) {
            return;
        }

        String currentText = (event.getText().toString() + msg + "\n");

        int maxTextViewStringLength = 8192;
        if (currentText.length() > maxTextViewStringLength) {
            int idx = currentText.indexOf('\n', currentText.length() - maxTextViewStringLength);
            if (idx >= 0) {
                currentText = currentText.substring(idx + 1);
            }
        }

        event.setText(currentText);

        event.post(() -> {
            final int scrollAmount =
                    event.getLayout().getLineTop(event.getLineCount()) - event.getHeight();
            event.scrollTo(0, Math.max(scrollAmount, 0));
        });
    }

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
        for (String permission : permissionList) {
            if (checkCallingOrSelfPermission(permission) == PackageManager.PERMISSION_DENIED) {
                requestPermissions(permissionList, SENSE_SDK_REQUEST_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == SENSE_SDK_REQUEST_CODE) {
            boolean allPermissionsGranted = true;
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
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
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
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