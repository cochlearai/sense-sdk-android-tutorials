package ai.cochl.tutorials;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

public class CopyAssets {

    private static final String TAG = "CopyAssets";

    private final Context context;

    public CopyAssets(Context context) {
        this.context = context;
    }

    public boolean copyAssets() {
        try {
            return copyAssetDirRecursive("", context.getExternalFilesDir(null));
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy assets", e);
            return false;
        }
    }

    private boolean copyAssetDirRecursive(String assetPath, File outDir) throws IOException {
        AssetManager assetManager = context.getAssets();
        String[] items = assetManager.list(assetPath);

        if (items == null) return true;

        final File file = new File(outDir, new File(assetPath).getName());
        if (items.length == 0) {
            // It's a file
            try (InputStream in = assetManager.open(assetPath);
                 OutputStream out = Files.newOutputStream(file.toPath())) {
                copyFile(in, out);
            }
        } else {
            // It's a directory
            if (!file.exists() && !file.mkdirs()) {
                throw new IOException("Failed to create directory: " + file.getAbsolutePath());
            }

            for (String child : items) {
                String childPath = assetPath.isEmpty() ? child : assetPath + "/" + child;
                if (!copyAssetDirRecursive(childPath, file)) return false;
            }
        }
        return true;
    }

    private void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }
}

