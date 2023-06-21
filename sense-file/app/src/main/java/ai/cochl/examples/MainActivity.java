package ai.cochl.examples;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
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
    private Sense sense = null;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ProgressBar progressBar;
    private TextView event;
    private Context context;

    private Adapter adapter;
    private boolean isFileSelected = false;
    private Item selectedItem = null;

    private final String[] permissionList = {
            Manifest.permission.INTERNET
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Objects.requireNonNull(getSupportActionBar()).hide();

        event = findViewById(R.id.event);
        event.setMovementMethod(new ScrollingMovementMethod());
        context = this;

        RecyclerView recyclerView = findViewById(R.id.files);
        recyclerView.setLayoutManager(
                new LinearLayoutManager(this,
                                        RecyclerView.VERTICAL,
                                        false));
        adapter = new Adapter();
        recyclerView.setAdapter(adapter);

        Button btnPredict = findViewById(R.id.predict);
        Button btnClear = findViewById(R.id.clear);

        btnPredict.setEnabled(false);
        btnClear.setOnClickListener(v -> event.setText(""));

        adapter.SetOnItemClickListener((viewHolder, view, position) -> {
            if (!isFileSelected) {
                isFileSelected = true;
                btnPredict.setEnabled(true);
            }
            selectedItem = adapter.GetItem(position);
        });
        btnPredict.setOnClickListener(v -> {
            if (!isFileSelected)
                return;

            new Thread(() -> {
                progressBar = new ProgressBar(handler,
                                              findViewById(R.id.inc_progress_bar));
                Thread thread = new Thread(progressBar);
                thread.start();

                try {
                    sense.addInput(selectedItem.GetFile());
                    isFileSelected = false;
                    selectedItem = null;
                    runOnUiThread(() -> btnPredict.setEnabled(false));
                    sensePredict();
                } catch (CochlException error) {
                    runOnUiThread(() ->
                            Toast.makeText(this,
                                           error.getMessage(),
                                           Toast.LENGTH_LONG).show());
                    finish();
                }
            }).start();
        });

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

            copyAssets();
            addWavFiles();
            try {
                sense = Sense.getInstance();

                Sense.Parameters senseParams = new Sense.Parameters();
                senseParams.metrics.retentionPeriod = 0;  // days
                senseParams.metrics.freeDiskSpace = 100;  // MB
                senseParams.metrics.pushPeriod = 30;      // seconds
                senseParams.deviceName = "Android device.";
                senseParams.logLevel = 0;

                sense.init(projectKey, senseParams);
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

                // For file mode results, all results are stored in one json array and returned
                // regardless of the audio file length.
                runOnUiThread(() -> progressBar.setStop());
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
                                   "You need to allow the permissions to use this app.",
                                   Toast.LENGTH_LONG).show();
                    finish();
                }
            }
        }
        senseInit();
    }

    private void copyAssets() {
        AssetManager assetManager = getAssets();
        String[] files = null;
        try {
            files = assetManager.list("");
        } catch (IOException ignored) {
        }
        if (files == null) {
            Toast.makeText(getApplicationContext(),
                           "Failed to get asset file list.",
                           Toast.LENGTH_LONG).show();
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
            } catch(IOException ignored) {
            }
            finally {
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
        for (String filename : Objects.requireNonNull(parent.list())) {
            adapter.AddItem(new Item(filename, new File(parent, filename)));
        }
        this.runOnUiThread(adapter::notifyDataSetChanged);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sense != null) {
            sense.terminate();
        }
    }
}
