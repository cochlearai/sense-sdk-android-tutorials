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

import java.util.Objects;

import ai.cochl.sensesdk.CochlException;
import ai.cochl.sensesdk.Sense;

public class MainActivity extends AppCompatActivity {
    private final String projectKey = "Your project key";
    private final int SENSE_SDK_REQUEST_CODE = 0;
    private final int AUDIO_SOURCE = MediaRecorder.AudioSource.UNPROCESSED;
    private final int SAMPLE_RATE = 22050;
    private final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT;
    private final int RECORD_BUF_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG,
            AUDIO_FORMAT);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String[] permissionList = {Manifest.permission.INTERNET,
            Manifest.permission.RECORD_AUDIO};
    private Sense sense = null;
    private boolean settingsButtonClicked = false;
    private ProgressBar progressBar;
    private TextView event;
    private Context context;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Objects.requireNonNull(getSupportActionBar()).hide();

        event = findViewById(R.id.event);
        event.setMovementMethod(new ScrollingMovementMethod());
        context = this;

        Button btnPause = findViewById(R.id.pause);
        Button btnClear = findViewById(R.id.clear);
        btnPause.setOnClickListener(v -> {
            String strBtnPause = getResources().getString(R.string.pause);
            String strBtnResume = getResources().getString(R.string.resume);

            if (btnPause.getText().equals(strBtnPause)) {
                sense.pause();
                btnPause.setText(strBtnResume);
            } else {
                sense.resume();
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
        /* to catch 'Only this time (Ask every time)' */
        int permission = ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO);
        if (permission == PackageManager.PERMISSION_DENIED) {
            GetToast(this, "You need to allow the permission to use this app.").show();
            finish();
        }

        new Thread(() -> {
            progressBar = new ProgressBar(handler, findViewById(R.id.inc_progress_bar));
            Thread thread = new Thread(progressBar);
            thread.start();

            try {
                sense = Sense.getInstance();

                Sense.Parameters senseParams = new Sense.Parameters();
                senseParams.metrics.retentionPeriod = 0;  // days
                senseParams.metrics.freeDiskSpace = 100;  // MB
                senseParams.metrics.pushPeriod = 30;      // seconds

                senseParams.deviceName = "Android device.";

                senseParams.logLevel = 0;

                senseParams.hopSizeControl.enable = true;
                senseParams.sensitivityControl.enable = true;
                senseParams.resultAbbreviation.enable = true;
                senseParams.labelHiding.enable = true;

                sense.init(projectKey, senseParams);
                sense.addInput(new AudioRecord(AUDIO_SOURCE, SAMPLE_RATE, CHANNEL_CONFIG,
                        AUDIO_FORMAT, RECORD_BUF_SIZE));
                sensePredict();
            } catch (CochlException e) {
                runOnUiThread(() -> {
                    GetToast(this, e.getMessage()).show();
                    finish();
                });
            }
            runOnUiThread(() -> progressBar.setStop());
        }).start();
    }

    private void sensePredict() {
        boolean resultAbbreviation = sense.getParameters().resultAbbreviation.enable;

        sense.predict(new Sense.OnPredictListener() {
            @Override
            public void onReceivedResult(JSONObject json) {
                try {
                    if (resultAbbreviation) {
                        JSONArray abbreviations = json.getJSONArray("abbreviations");
                        for (int i = 0; i < abbreviations.length(); ++i) {
                            Append(abbreviations.getString(i));
                        }
                        /*
                         Even if you use the result abbreviation, you can still get precise
                         results like below if necessary:
                         String frame_result = json.getJSONObject("frame_result").toString(2);
                         Append(frame_result);
                        */
                    } else {
                        Append("---------NEW FRAME---------");
                        String frame_result = json.getJSONObject("frame_result").toString(2);
                        Append(frame_result);
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onError(CochlException e) {
                runOnUiThread(() -> {
                    GetToast(context, e.getMessage()).show();
                    sense.stopPredict();
                });
            }
        });
    }

    private void Append(String msg) {
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

    @Override
    protected void onDestroy() {
        if (sense != null) {
            sense.terminate();
            sense = null;
        }
        super.onDestroy();
    }
}