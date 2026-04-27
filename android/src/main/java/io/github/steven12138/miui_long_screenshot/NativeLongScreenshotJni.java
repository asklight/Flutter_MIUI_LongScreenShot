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

    private static native boolean nativeCanScrollVertically(
            int direction,
            boolean startScreenshot,
            int callbackId
    );

    private static native boolean nativeScrollBy(int x, int y, int callbackId);

    public static boolean requestCanScrollVertically(int direction, boolean startScreenshot) {
        return LOADED && nativeCanScrollVertically(direction, startScreenshot, 0);
    }

    public static boolean requestScrollBy(int x, int y, int callbackId) {
        return LOADED && nativeScrollBy(x, y, callbackId);
    }
}
