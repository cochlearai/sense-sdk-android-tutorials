package ai.cochl.examples;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Objects;

import ai.cochl.sensesdk.CochlException;
import ai.cochl.sensesdk.Sense;

public class MainActivity extends AppCompatActivity {
    private final String projectKey = "Your project key";
    private final int SENSE_SDK_REQUEST_CODE = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String[] permissionList = {Manifest.permission.INTERNET};
    private Sense sense = null;
    private boolean settingsButtonClicked = false;
    private ProgressBar progressBar;
    private TextView event;
    private Context context;
    private Adapter adapter;
    private boolean fileSelected = false;
    private Item selectedItem = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Objects.requireNonNull(getSupportActionBar()).hide();

        event = findViewById(R.id.event);
        event.setMovementMethod(new ScrollingMovementMethod());
        context = this;

        RecyclerView recyclerView = findViewById(R.id.files);
        recyclerView.setLayoutManager(new LinearLayoutManager(this, RecyclerView.VERTICAL, false));
        adapter = new Adapter();
        recyclerView.setAdapter(adapter);

        Button btnPredict = findViewById(R.id.predict);
        Button btnClear = findViewById(R.id.clear);

        btnPredict.setEnabled(false);
        btnClear.setOnClickListener(v -> event.setText(""));

        adapter.SetOnItemClickListener((viewHolder, view, position) -> {
            if (!fileSelected) {
                fileSelected = true;
                btnPredict.setEnabled(true);
            }
            selectedItem = adapter.GetItem(position);
        });
        btnPredict.setOnClickListener(v -> {
            if (!fileSelected) return;

            new Thread(() -> {
                try {
                    sense.addInput(selectedItem.GetFile());
                    fileSelected = false;
                    selectedItem = null;
                    runOnUiThread(() -> btnPredict.setEnabled(false));
                    sensePredict();
                } catch (CochlException e) {
                    runOnUiThread(() -> GetToast(this, e.getMessage()).show());
                }
            }).start();
        });

        copyAssets();
        addWavFiles();

        if (!checkPermissions()) {
            requestPermissions();
        } else {
            senseInit();
        }
    }

    private void senseInit() {
        new Thread(() -> {
            progressBar = new ProgressBar(handler, findViewById(R.id.inc_progress_bar));
            Thread thread = new Thread(progressBar);
            thread.start();

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
            senseParams.labelHiding.enable = false;  // stream mode only

            try {
                sense.init(projectKey, senseParams);
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

        progressBar = new ProgressBar(handler, findViewById(R.id.inc_progress_bar));
        Thread thread = new Thread(progressBar);
        thread.start();

        sense.predict(new Sense.OnPredictListener() {
            @Override
            public void onReceivedResult(JSONObject json) {
                try {
                    if (resultAbbreviation) {
                        JSONArray abbreviations = json.getJSONArray("abbreviations");
                        Append("<Result summary>");
                        for (int i = 0; i < abbreviations.length(); ++i) {
                            Append(abbreviations.getString(i));
                        }
                        /*
                         Even if you use the result abbreviation, you can still get precise
                         results like below if necessary:
                         String result = json.getJSONObject("result").toString(2);
                         Append(result);
                        */
                    } else {
                        String result = json.getJSONObject("result").toString(2);
                        Append(result);
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                } finally {
                    runOnUiThread(() -> progressBar.setStop());
                }
            }

            @Override
            public void onError(CochlException e) {
                runOnUiThread(() -> {
                    progressBar.setStop();
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

    private void copyAssets() {
        AssetManager assetManager = getAssets();
        String[] files = null;
        try {
            files = assetManager.list("");
        } catch (IOException ignored) {
        }
        if (files == null) {
            GetToast(this, "Failed to get asset file list.").show();
            finish();
        }

        for (String filename : Objects.requireNonNull(files)) {
            InputStream in = null;
            OutputStream out = null;
            try {
                in = assetManager.open(filename);
                File outFile = new File(this.getExternalFilesDir(null), filename);
                out = Files.newOutputStream(outFile.toPath());
                copyFile(in, out);
            } catch (IOException ignored) {
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    private void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private void addWavFiles() {
        File parent = this.getExternalFilesDir(null);
        for (String filename : Objects.requireNonNull(Objects.requireNonNull(parent).list())) {
            adapter.AddItem(new Item(filename, new File(parent, filename)));
        }
        this.runOnUiThread(adapter::notifyDataSetChanged);
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