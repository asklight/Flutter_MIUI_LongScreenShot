package io.github.steven12138.miui_long_screenshot;

import androidx.annotation.Keep;

@Keep
public final class NativeLongScreenshotJni {
    private static final boolean LOADED;

    static {
        boolean loaded;
        try {
            System.loadLibrary("miui_long_screenshot");
            loaded = true;
        } catch (Throwable ignored) {
            loaded = false;
        }
        LOADED = loaded;
    }

    private NativeLongScreenshotJni() {
    }

    public static boolean isLoaded() {
        return LOADED;
    }

    public static native int initializeApi(long nativeApiData);

    public static native void registerSendPort(long sendPort);

    private static native void nativeCanScrollVertically(
            int direction,
            boolean startScreenshot,
            int callbackId
    );

    private static native void nativeScrollBy(int x, int y, int callbackId);

    public static int canScrollVerticallyResult(int direction, boolean startScreenshot) {
        if (!LOADED) return FlutterLongScreenshotCallbacks.AwaitCallback.RESULT_ERR;

        FlutterLongScreenshotCallbacks.AwaitCallback callback =
                FlutterLongScreenshotCallbacks.newCallback();
        nativeCanScrollVertically(direction, startScreenshot, callback.id());
        return callback.waitCallback().getResult();
    }

    public static int scrollByResult(int x, int y) {
        if (!LOADED) return FlutterLongScreenshotCallbacks.AwaitCallback.RESULT_ERR;

        FlutterLongScreenshotCallbacks.AwaitCallback callback =
                FlutterLongScreenshotCallbacks.newCallback();
        nativeScrollBy(x, y, callback.id());
        return callback.waitCallback().getResult();
    }
}
