package io.github.steven12138.miui_long_screenshot;

import androidx.annotation.Keep;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Keep
public final class FlutterLongScreenshotCallbacks {
    private static final int TIMEOUT_MS = 100;
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
    private static final ConcurrentHashMap<Integer, AwaitCallback> CALLBACKS =
            new ConcurrentHashMap<>();

    private FlutterLongScreenshotCallbacks() {
    }

    public static AwaitCallback newCallback() {
        AwaitCallback callback = new AwaitCallback(NEXT_ID.getAndIncrement());
        CALLBACKS.put(callback.id(), callback);
        return callback;
    }

    @Keep
    public static void handleDartCall(int id, int result) {
        AwaitCallback callback = CALLBACKS.remove(id);
        if (callback != null) {
            callback.release(result);
        }
    }

    @Keep
    public static final class AwaitCallback {
        public static final int RESULT_ERR = -1;

        private final int id;
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile int result = RESULT_ERR;

        private AwaitCallback(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        public AwaitCallback waitCallback() {
            try {
                if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    CALLBACKS.remove(id);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                CALLBACKS.remove(id);
            }
            return this;
        }

        private void release(int result) {
            this.result = result;
            latch.countDown();
        }

        public int getResult() {
            return result;
        }
    }
}
