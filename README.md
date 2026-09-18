# sense-sdk-android-tutorials

The two `MainActivity.java` files here are the **main app code** ported from the full runnable tutorials and updated to the **current AAR API**:

- `sense-file/MainActivity.java` - file-mode: `init` -> `predict(filePath)` -> `terminate`.
- `sense-stream/MainActivity.java` - stream-mode: `AudioRecord` capture -> `predict(short[]|float[], sampleRate)` -> `terminate`.

The full runnable apps -- helper classes (`Adapter`, `Item`, `CopyAssets`, `InitProgressBarTask`, `OnItemClickListener`), layouts, Gradle setup -- live in:

```text
../sense-sdk-android-tutorials/sense-file
../sense-sdk-android-tutorials/sense-stream
```

Drop these `MainActivity.java` files into that project (`sense-sdk-android-tutorials`, they are otherwise unchanged) to run against the new AAR.

## Using the AAR

Add the AAR produced by this project (`sense-sdk-v<ver>-ndk-<tag>.aar`) to the app module:

```gradle
dependencies {
    implementation files("libs/sense-sdk-v1.6.1-ndk-r26b.aar")
}
```

Set `projectKey` in each `MainActivity.java` before running. Min NDK r22b.
