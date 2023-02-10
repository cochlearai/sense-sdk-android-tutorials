package ai.cochl.examples;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

import ai.cochl.sensesdk.CochlException;
import ai.cochl.sensesdk.Sense;

public class MainActivity extends AppCompatActivity {
    private final String projectKey = "Your project key";
    private Sense sense = null;

    private final int AUDIO_SOURCE = MediaRecorder.AudioSource.UNPROCESSED;
    private final int SAMPLE_RATE = 22050;
    private final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT;
    private final int RECORD_BUF_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                                                                     CHANNEL_CONFIG,
                                                                     AUDIO_FORMAT);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ProgressBar progressBar;
    private TextView event;
    private Context context;

    private final String[] permissionList = {
            Manifest.permission.INTERNET,
            Manifest.permission.RECORD_AUDIO
    };

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
        new Thread(() -> {
            progressBar = new ProgressBar(handler,
                                          findViewById(R.id.inc_progress_bar));
            Thread thread = new Thread(progressBar);
            thread.start();

            try {
                if (ActivityCompat.checkSelfPermission(this,
                                                       Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    throw new CochlException("You need to enable record audio "
                            + "permission to use this app.");
                }

                sense = Sense.getInstance();

                Sense.Parameters senseParams = new Sense.Parameters();
                senseParams.metrics.retentionPeriod = 0;  // days
                senseParams.metrics.freeDiskSpace = 100;  // MB
                senseParams.metrics.pushPeriod = 30;      // seconds
                senseParams.deviceName = "Android device.";

                sense.init(projectKey, senseParams);
                sense.addInput(new AudioRecord(AUDIO_SOURCE,
                                               SAMPLE_RATE,
                                               CHANNEL_CONFIG,
                                               AUDIO_FORMAT,
                                               RECORD_BUF_SIZE));
                sensePredict();
            } catch (CochlException error) {
                runOnUiThread(() ->
                        Toast.makeText(this,
                                       error.getMessage(),
                                       Toast.LENGTH_LONG).show());
                finish();
            }
            runOnUiThread(() -> progressBar.setStop());
        }).start();
    }

    private void sensePredict() {
        sense.predict(new Sense.OnPredictListener() {
            @Override
            public void onReceivedResult(JSONObject result) {
                try {
                    JSONArray jsonArr = result.getJSONArray("tags");
                    for (int i = 0; i < jsonArr.length(); ++i) {
                        JSONObject obj = jsonArr.getJSONObject(i);
                        if (obj.getString("name").equals("Others"))
                            continue;
                        event.append(obj.getString("name") + " "
                                + "("
                                + obj.getDouble("probability")
                                + ")\n");

                        final int scrollAmount = event.getLayout().getLineTop(
                                event.getLineCount()) - event.getHeight();
                        event.scrollTo(0, Math.max(scrollAmount, 0));
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                } catch (CochlException error) {
                    Toast.makeText(context,
                                   error.getMessage(),
                                   Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(CochlException error) {
                sense.stopPredict();
                Toast.makeText(context,
                               error.getMessage(),
                               Toast.LENGTH_SHORT).show();
            }
        });
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
                requestPermissions(permissionList, 0);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode,
                                         permissions,
                                         grantResults);
        if (requestCode == 0) {
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(getApplicationContext(),
                                   "You need to allow the permissions to use "
                                           + "this app.",
                                   Toast.LENGTH_LONG).show();
                    finish();
                }
            }
        }
        senseInit();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sense != null) {
            sense.terminate();
        }
    }
}
