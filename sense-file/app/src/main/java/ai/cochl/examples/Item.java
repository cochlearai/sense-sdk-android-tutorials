package ai.cochl.examples;

import java.io.File;

public class Item {
    final private String filename;
    final private File file;

    Item(String filename, File file) {
        this.filename = filename;
        this.file = file;
    }

    String GetFilename() { return this.filename; }
    File GetFile() { return this.file; }
}
