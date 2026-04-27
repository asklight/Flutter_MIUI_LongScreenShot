package io.github.steven12138.miui_long_screenshot

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val PERF_TAG = "MiuiLongScreenshotPerf"
private const val RAW_TAG = "MiuiLongScreenshotRaw"

class MiuiLongScreenshotPlugin : FlutterPlugin, MethodChannel.MethodCallHandler,
    ActivityAware, MiuiLongScreenshotControlView.Delegate {

    private var channel: MethodChannel? = null
    private var activity: Activity? = null
    private var controlView: MiuiLongScreenshotControlView? = null
    private var targetViewRef: WeakReference<View>? = null

    @Volatile
    private var cachedCanScrollDown = false

    @Volatile
    private var cachedCanScrollUp = false

    @Volatile
    private var dartTargetReady = false

    private var firstScrollAfterScreenshot = true

    @Volatile
    private var lastConsumedScrollY = 0

    @Volatile
    private var zeroConsumedScrollCount = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val nextScrollRequestId = AtomicInteger(1)
    private val pendingScrolls = ConcurrentHashMap<Int, PendingScroll>()

    private val screenshotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            log("screenshot broadcast: ${intent?.action}")
            firstScrollAfterScreenshot = true
            resetScrollSession()
            controlView?.resetScrollY()
            requestDartScrollState(direction = 1, startScreenshot = true)
        }
    }

    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getStringExtra("token") != DEBUG_TOKEN) {
                perf("adb denied action=${intent?.action}")
                return
            }

            val delayMs = intent.getIntExtra("delayMs", 0).coerceAtLeast(0)
            if (delayMs > 0) {
                val command = intent.getStringExtra("cmd")
                perf("adb schedule cmd=$command delayMs=$delayMs")
                mainHandler.postDelayed({ handleDebugCommand(intent) }, delayMs.toLong())
                return
            }
            handleDebugCommand(intent)
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        activePlugin = this
        NativeLongScreenshotJni.isLoaded()
        channel = MethodChannel(binding.binaryMessenger, CHANNEL).apply {
            setMethodCallHandler(this@MiuiLongScreenshotPlugin)
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
        if (activePlugin === this) {
            activePlugin = null
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.activity.window.decorView.post {
            installControlView()
            registerScreenshotReceiver()
            registerDebugReceiver()
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {
        detachActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        detachActivity()
    }

    private fun detachActivity() {
        uninstallControlView()
        unregisterScreenshotReceiver()
        unregisterDebugReceiver()
        pendingScrolls.clear()
        activity = null
    }

    private fun handleDebugCommand(intent: Intent) {
        when (val command = intent.getStringExtra("cmd")) {
            "state" -> {
                logDebugState("adb_state")
                requestDartScrollState(direction = 1, startScreenshot = true)
            }

            "reset" -> {
                firstScrollAfterScreenshot = true
                resetScrollSession()
                controlView?.resetScrollY()
                logDebugState("adb_reset")
                requestDartScrollState(direction = 1, startScreenshot = true)
            }

            "scroll" -> {
                val x = intent.getIntExtra("x", 0)
                val y = intent.getIntExtra("y", 800)
                val view = controlView
                if (view == null) {
                    perf("adb scroll ignored controlView=null")
                    return
                }
                perf("adb scroll request x=$x y=$y beforeY=${view.scrollY}")
                view.scrollBy(x, y)
            }

            "scrollTo" -> {
                val x = intent.getIntExtra("x", 0)
                val y = intent.getIntExtra("y", 800)
                val view = controlView
                if (view == null) {
                    perf("adb scrollTo ignored controlView=null")
                    return
                }
                perf("adb scrollTo request x=$x y=$y beforeY=${view.scrollY}")
                view.scrollTo(x, y)
            }

            else -> perf("adb unknown cmd=$command")
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "updateScrollState" -> {
                    updateCachedScrollState(call.arguments)
                    result.success(null)
                }

                "resetControlView" -> {
                    firstScrollAfterScreenshot = true
                    resetScrollSession()
                    controlView?.resetScrollY()
                    result.success(null)
                }

                else -> result.notImplemented()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "method call failed", e)
            result.error("miui_long_screenshot_error", e.message, null)
        }
    }

    override fun getDrawingCacheTarget(): View? = targetViewRef?.get()

    override fun canScrollVerticallyFromDart(direction: Int): Boolean {
        val start = SystemClock.uptimeMillis()
        requestDartScrollState(direction = direction, startScreenshot = false)
        val cached = adjustCanScrollResult(
            direction,
            if (direction > 0) cachedCanScrollDown else cachedCanScrollUp
        )
        perf(
            "canScroll dir=$direction result=$cached path=ffi_cached " +
                "nativeLoaded=${NativeLongScreenshotJni.isLoaded()} ready=$dartTargetReady " +
                "cost=${SystemClock.uptimeMillis() - start}ms"
        )
        return cached
    }

    override fun scrollByFromMiui(
        x: Int,
        y: Int,
        controlScrollYBefore: Int,
        source: String
    ) {
        val firstScroll = firstScrollAfterScreenshot
        if (firstScrollAfterScreenshot) {
            firstScrollAfterScreenshot = false
        }

        if (!dartTargetReady) {
            controlView?.syncScrollY(controlScrollYBefore)
            return
        }

        val start = SystemClock.uptimeMillis()
        if (NativeLongScreenshotJni.isLoaded()) {
            val requestId = nextScrollRequestId.getAndIncrement()
            pendingScrolls[requestId] = PendingScroll(
                x = x,
                y = y,
                controlScrollYBefore = controlScrollYBefore,
                source = source,
                firstScroll = firstScroll,
                startMs = start
            )
            if (NativeLongScreenshotJni.requestScrollBy(x, y, requestId)) {
                perf(
                    "scroll source=$source x=$x y=$y path=ffi_post " +
                        "requestId=$requestId cost=${SystemClock.uptimeMillis() - start}ms"
                )
                return
            }
            pendingScrolls.remove(requestId)
        }

        scrollByWithMethodChannel(x, y, controlScrollYBefore, source, firstScroll, start)
    }

    private fun scrollByWithMethodChannel(
        x: Int,
        y: Int,
        controlScrollYBefore: Int,
        source: String,
        firstScroll: Boolean,
        start: Long
    ) {
        val currentChannel = channel ?: return
        currentChannel.invokeMethod(
            "scrollBy",
            mapOf("x" to x, "y" to y),
            object : MethodChannel.Result {
                override fun success(result: Any?) {
                    val consumedY = consumedNativeY(result)
                    if (consumedY != null) {
                        updateScrollConsumption(consumedY)
                        val correctedControlY = (controlScrollYBefore + consumedY).coerceAtLeast(0)
                        controlView?.syncScrollY(correctedControlY)
                    }
                    updateCachedScrollState(result)
                    val stabilizeMs = stabilizeAfterFlutterScroll(firstScroll)
                    perf(
                        "scroll source=$source x=$x y=$y consumedY=$consumedY path=method_channel " +
                            "first=$firstScroll stabilize=${stabilizeMs}ms " +
                            "canDown=$cachedCanScrollDown controlY=${controlView?.scrollY} " +
                            "zeroCount=$zeroConsumedScrollCount " +
                            "cost=${SystemClock.uptimeMillis() - start}ms"
                    )
                }

                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                    log("scrollBy error: $errorCode $errorMessage")
                }

                override fun notImplemented() {
                    log("scrollBy not implemented")
                }
            }
        )
    }

    private fun installControlView() {
        val currentActivity = activity ?: return
        val contentRoot = currentActivity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (controlView?.parent === contentRoot) return

        val targetView = findFlutterContentView(contentRoot)
        if (targetView != null) {
            targetViewRef = WeakReference(targetView)
        }

        val view = MiuiLongScreenshotControlView(currentActivity, this)
        contentRoot.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        controlView = view
        perf(
            "install controlView nativeLoaded=${NativeLongScreenshotJni.isLoaded()} " +
                "root=${contentRoot.width}x${contentRoot.height}"
        )
        requestDartScrollState(direction = 1, startScreenshot = true)
    }

    private fun uninstallControlView() {
        val view = controlView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        controlView = null
        targetViewRef = null
    }

    private fun findFlutterContentView(contentRoot: ViewGroup): View? {
        for (index in 0 until contentRoot.childCount) {
            val child = contentRoot.getChildAt(index)
            if (child !is MiuiLongScreenshotControlView) return child
        }
        return null
    }

    private fun registerScreenshotReceiver() {
        val currentActivity = activity ?: return
        val filter = IntentFilter().apply {
            SCREENSHOT_ACTIONS.forEach { addAction(it) }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                currentActivity.registerReceiver(
                    screenshotReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                currentActivity.registerReceiver(screenshotReceiver, filter)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "register screenshot receiver failed", e)
        }
    }

    private fun unregisterScreenshotReceiver() {
        val currentActivity = activity ?: return
        try {
            currentActivity.unregisterReceiver(screenshotReceiver)
        } catch (_: Throwable) {
        }
    }

    private fun registerDebugReceiver() {
        val currentActivity = activity ?: return
        val filter = IntentFilter().apply { addAction(DEBUG_ACTION) }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                currentActivity.registerReceiver(
                    debugReceiver,
                    filter,
                    Context.RECEIVER_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                currentActivity.registerReceiver(debugReceiver, filter)
            }
            perf("adb debug receiver registered action=$DEBUG_ACTION")
        } catch (e: Throwable) {
            Log.e(TAG, "register adb debug receiver failed", e)
        }
    }

    private fun unregisterDebugReceiver() {
        val currentActivity = activity ?: return
        try {
            currentActivity.unregisterReceiver(debugReceiver)
        } catch (_: Throwable) {
        }
    }

    private fun requestDartScrollState(direction: Int, startScreenshot: Boolean) {
        if (NativeLongScreenshotJni.requestCanScrollVertically(direction, startScreenshot)) {
            perf(
                "requestState dir=$direction start=$startScreenshot path=ffi_post " +
                    "down=$cachedCanScrollDown up=$cachedCanScrollUp"
            )
            return
        }

        if (!dartTargetReady) return

        val currentChannel = channel ?: return
        val start = SystemClock.uptimeMillis()
        currentChannel.invokeMethod(
            "canScrollVertically",
            mapOf(
                "direction" to direction,
                "startScreenshot" to startScreenshot
            ),
            object : MethodChannel.Result {
                override fun success(result: Any?) {
                    updateCachedScrollState(result)
                    perf(
                        "requestState dir=$direction start=$startScreenshot " +
                            "down=$cachedCanScrollDown up=$cachedCanScrollUp " +
                            "cost=${SystemClock.uptimeMillis() - start}ms"
                    )
                }

                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                    log("canScrollVertically error: $errorCode $errorMessage")
                }

                override fun notImplemented() {
                    log("canScrollVertically not implemented")
                }
            }
        )
    }

    private fun updateCachedScrollStateFromNative(
        hasTarget: Boolean,
        canScrollDown: Boolean,
        canScrollUp: Boolean
    ) {
        dartTargetReady = hasTarget
        cachedCanScrollDown = adjustCanScrollResult(1, canScrollDown)
        cachedCanScrollUp = canScrollUp
    }

    private fun updateCachedScrollState(value: Any?) {
        val map = value as? Map<*, *> ?: return
        updateCachedScrollStateFromNative(
            hasTarget = map["hasTarget"] as? Boolean ?: dartTargetReady,
            canScrollDown = map["canScrollDown"] as? Boolean ?: cachedCanScrollDown,
            canScrollUp = map["canScrollUp"] as? Boolean ?: cachedCanScrollUp
        )
        logFlutterScrollMetrics(map)
    }

    private fun handleNativeScrollResult(requestId: Int, encodedResult: Int) {
        val pending = pendingScrolls.remove(requestId)
        if (pending == null) {
            perf("scroll result ignored requestId=$requestId reason=no_pending")
            return
        }

        val consumedY = decodeConsumedScrollY(encodedResult)
        updateScrollConsumption(consumedY)
        val correctedControlY = (pending.controlScrollYBefore + consumedY).coerceAtLeast(0)
        controlView?.syncScrollY(correctedControlY)
        cachedCanScrollDown = adjustCanScrollResult(1, decodeCanScrollDown(encodedResult))
        cachedCanScrollUp = correctedControlY > 0
        val stabilizeMs = stabilizeAfterFlutterScroll(pending.firstScroll)
        perf(
            "scroll source=${pending.source} x=${pending.x} y=${pending.y} " +
                "consumedY=$consumedY canDown=$cachedCanScrollDown path=ffi_callback " +
                "first=${pending.firstScroll} stabilize=${stabilizeMs}ms " +
                "controlY=${controlView?.scrollY} zeroCount=$zeroConsumedScrollCount " +
                "cost=${SystemClock.uptimeMillis() - pending.startMs}ms"
        )
    }

    private fun consumedNativeY(value: Any?): Int? {
        val map = value as? Map<*, *> ?: return null
        return (map["consumedNativeY"] as? Number)?.toInt()
    }

    private fun logDebugState(source: String) {
        val view = controlView
        perf(
            "debug source=$source nativeLoaded=${NativeLongScreenshotJni.isLoaded()} " +
                "ready=$dartTargetReady pending=${pendingScrolls.size} " +
                "down=$cachedCanScrollDown up=$cachedCanScrollUp " +
                "first=$firstScrollAfterScreenshot controlY=${view?.scrollY} " +
                "controlSize=${view?.width}x${view?.height} " +
                "lastConsumed=$lastConsumedScrollY zeroCount=$zeroConsumedScrollCount"
        )
    }

    private fun resetScrollSession() {
        lastConsumedScrollY = 0
        zeroConsumedScrollCount = 0
    }

    private fun updateScrollConsumption(consumedY: Int) {
        lastConsumedScrollY = consumedY
        if (kotlin.math.abs(consumedY) <= MIN_CONSUMED_SCROLL_Y) {
            zeroConsumedScrollCount += 1
        } else {
            zeroConsumedScrollCount = 0
        }
    }

    private fun adjustCanScrollResult(direction: Int, canScroll: Boolean): Boolean {
        if (direction <= 0 || canScroll) return canScroll

        // List height can change after image decoding. If the previous scroll
        // consumed real pixels, allow one extra MIUI probe before declaring end.
        return kotlin.math.abs(lastConsumedScrollY) > MIN_CONSUMED_SCROLL_Y &&
            zeroConsumedScrollCount == 0
    }

    private fun stabilizeAfterFlutterScroll(firstScroll: Boolean): Long {
        val waitMs = if (firstScroll) {
            FIRST_SCROLL_STABILIZE_MS
        } else {
            SCROLL_STABILIZE_MS
        }
        if (waitMs > 0) {
            SystemClock.sleep(waitMs)
        }
        return waitMs
    }

    private fun logFlutterScrollMetrics(map: Map<*, *>) {
        val requestedNativeY = (map["requestedNativeY"] as? Number)?.toInt() ?: return
        perf(
            "flutterMetrics reqY=$requestedNativeY consumedY=${number(map, "consumedNativeY")} " +
                "dpr=${number(map, "devicePixelRatio")} factor=${number(map, "scrollFactor")} " +
                "before=${number(map, "beforePixels")} target=${number(map, "targetPixels")} " +
                "after=${number(map, "afterPixels")} delta=${number(map, "logicalDelta")} " +
                "consumedLogical=${number(map, "consumedLogicalY")} max=${number(map, "maxPixels")} " +
                "skip=${map["skipReason"]}"
        )
    }

    companion object {
        private const val TAG = "MIUI_LONG_SCREENSHOT"
        private const val ENABLE_PERF_LOG = true
        private const val DEBUG_ACTION = "io.github.steven12138.miui_long_screenshot.DEBUG"
        private const val DEBUG_TOKEN = "miui-long-screenshot-adb"
        private const val FIRST_SCROLL_STABILIZE_MS = 64L
        private const val SCROLL_STABILIZE_MS = 16L
        private const val MIN_CONSUMED_SCROLL_Y = 2
        private const val CHANNEL = "miui_long_screenshot"

        @Volatile
        private var activePlugin: MiuiLongScreenshotPlugin? = null

        private val SCREENSHOT_ACTIONS = listOf(
            "android.intent.action.SCREENSHOT",
            "miui.intent.TAKE_SCREENSHOT",
            "com.miui.gallery.intent.action.SCREENSHOT",
            "com.miui.screenshot.action.SCREENSHOT"
        )

        private fun log(message: String) = Log.d(TAG, message)

        private fun perf(message: String) {
            if (ENABLE_PERF_LOG) {
                Log.d(PERF_TAG, message)
            }
        }

        @JvmStatic
        fun updateCachedScrollStateFromDart(
            hasTarget: Boolean,
            canScrollDown: Boolean,
            canScrollUp: Boolean
        ) {
            activePlugin?.updateCachedScrollStateFromNative(
                hasTarget = hasTarget,
                canScrollDown = canScrollDown,
                canScrollUp = canScrollUp
            )
        }

        @JvmStatic
        fun handleDartScrollResult(requestId: Int, encodedResult: Int) {
            val plugin = activePlugin ?: return
            plugin.mainHandler.post {
                plugin.handleNativeScrollResult(requestId, encodedResult)
            }
        }

        private fun decodeCanScrollDown(result: Int): Boolean {
            return result and 0x1 == 1
        }

        private fun decodeConsumedScrollY(result: Int): Int {
            val magnitude = result ushr 2
            return if (result and 0x2 == 0x2) -magnitude else magnitude
        }

        private fun number(map: Map<*, *>, key: String): String {
            return when (val value = map[key]) {
                is Float -> "%.2f".format(value)
                is Double -> "%.2f".format(value)
                is Number -> value.toString()
                else -> "null"
            }
        }
    }

    private data class PendingScroll(
        val x: Int,
        val y: Int,
        val controlScrollYBefore: Int,
        val source: String,
        val firstScroll: Boolean,
        val startMs: Long
    )
}

class MiuiLongScreenshotControlView(
    context: Context,
    private val delegate: Delegate
) : ScrollView(context) {

    interface Delegate {
        fun getDrawingCacheTarget(): View?
        fun canScrollVerticallyFromDart(direction: Int): Boolean
        fun scrollByFromMiui(
            x: Int,
            y: Int,
            controlScrollYBefore: Int,
            source: String
        )
    }

    private var suppressScrollCallback = false
    private var forwardingFakeTouchScroll = false

    @Volatile
    private var fakeTouchUntilMs = 0L

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isFillViewport = false
        isFocusable = false
        isFocusableInTouchMode = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                Space(context),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    FAKE_CONTENT_HEIGHT_PX
                )
            )
        }
        addView(
            content,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        resetScrollY()
    }

    fun resetScrollY() {
        fakeTouchUntilMs = 0L
        forwardingFakeTouchScroll = false
        syncScrollY(0)
    }

    fun syncScrollY(y: Int) {
        suppressScrollCallback = true
        try {
            super.scrollTo(0, y)
            if (scrollY != y) {
                forceScrollY(y)
            }
        } finally {
            suppressScrollCallback = false
        }
    }

    private fun forceScrollY(y: Int) {
        try {
            scrollYField?.setInt(this, y)
            invalidate()
        } catch (e: Throwable) {
            Log.w(PERF_TAG, "forceScrollY failed target=$y actual=$scrollY", e)
        }
    }

    override fun canScrollVertically(direction: Int): Boolean {
        val result = delegate.canScrollVerticallyFromDart(direction)
        if (direction > 0 && result) {
            armFakeTouchWindow()
        }
        raw(
            "view.canScrollVertically dir=$direction result=$result " +
                "scrollY=$scrollY height=$height childHeight=${getChildAt(0)?.height}"
        )
        return result
    }

    override fun scrollBy(x: Int, y: Int) {
        val beforeY = scrollY
        super.scrollTo(scrollX + x, scrollY + y)
        if (!suppressScrollCallback && !forwardingFakeTouchScroll && (x != 0 || y != 0)) {
            delegate.scrollByFromMiui(x, y, beforeY, "scrollBy")
        }
    }

    override fun scrollTo(x: Int, y: Int) {
        val beforeX = scrollX
        val beforeY = scrollY
        super.scrollTo(x, y)
        val dx = x - beforeX
        val dy = y - beforeY
        if (!suppressScrollCallback && !forwardingFakeTouchScroll && (dx != 0 || dy != 0)) {
            delegate.scrollByFromMiui(dx, dy, beforeY, "scrollTo")
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        val dy = t - oldt
        if (forwardingFakeTouchScroll && !suppressScrollCallback && dy != 0) {
            raw("view.fakeTouchNative dy=$dy beforeY=$oldt afterY=$t")
            delegate.scrollByFromMiui(0, dy, oldt, "fakeTouch:native")
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val beforeY = scrollY
        val handled = super.performAccessibilityAction(action, arguments)
        val dy = scrollY - beforeY
        if (!suppressScrollCallback && !forwardingFakeTouchScroll && dy != 0) {
            delegate.scrollByFromMiui(0, dy, beforeY, "accessibility:${accessibilityActionName(action)}")
        }
        return handled
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!isFakeTouchWindowActive()) {
            return false
        }
        return handleMiuiFakeTouch(ev)
    }

    @Deprecated("Deprecated in Java")
    override fun setDrawingCacheEnabled(enabled: Boolean) {
        super.setDrawingCacheEnabled(enabled)
        delegate.getDrawingCacheTarget()?.setDrawingCacheEnabled(enabled)
    }

    @Deprecated("Deprecated in Java")
    override fun isDrawingCacheEnabled(): Boolean {
        return delegate.getDrawingCacheTarget()?.isDrawingCacheEnabled
            ?: super.isDrawingCacheEnabled()
    }

    @Deprecated("Deprecated in Java")
    override fun buildDrawingCache(autoScale: Boolean) {
        super.buildDrawingCache(autoScale)
        delegate.getDrawingCacheTarget()?.buildDrawingCache(autoScale)
    }

    @Deprecated("Deprecated in Java")
    override fun getDrawingCache(autoScale: Boolean): Bitmap? {
        return delegate.getDrawingCacheTarget()?.getDrawingCache(autoScale)
            ?: super.getDrawingCache(autoScale)
    }

    @Deprecated("Deprecated in Java")
    override fun destroyDrawingCache() {
        super.destroyDrawingCache()
        delegate.getDrawingCacheTarget()?.destroyDrawingCache()
    }

    private fun armFakeTouchWindow() {
        fakeTouchUntilMs = SystemClock.uptimeMillis() + FAKE_TOUCH_WINDOW_MS
    }

    private fun isFakeTouchWindowActive(): Boolean {
        return SystemClock.uptimeMillis() <= fakeTouchUntilMs
    }

    private fun handleMiuiFakeTouch(ev: MotionEvent): Boolean {
        armFakeTouchWindow()
        forwardingFakeTouchScroll = true
        try {
            val beforeY = scrollY
            val handled = super.onTouchEvent(ev)
            raw(
                "view.fakeTouchSuper action=${motionActionName(ev.actionMasked)} " +
                    "handled=$handled beforeY=$beforeY afterY=$scrollY"
            )
        } finally {
            forwardingFakeTouchScroll = false
        }
        return true
    }

    companion object {
        private const val FAKE_CONTENT_HEIGHT_PX = 1_000_000
        private const val FAKE_TOUCH_WINDOW_MS = 2_500L
        private const val ENABLE_RAW_LOG = true

        private val scrollYField by lazy {
            try {
                View::class.java.getDeclaredField("mScrollY").apply {
                    isAccessible = true
                }
            } catch (_: Throwable) {
                null
            }
        }

        private fun raw(message: String) {
            if (ENABLE_RAW_LOG) {
                Log.d(RAW_TAG, message)
            }
        }

        private fun motionActionName(action: Int): String {
            return when (action) {
                MotionEvent.ACTION_DOWN -> "DOWN"
                MotionEvent.ACTION_UP -> "UP"
                MotionEvent.ACTION_MOVE -> "MOVE"
                MotionEvent.ACTION_CANCEL -> "CANCEL"
                else -> action.toString()
            }
        }

        private fun accessibilityActionName(action: Int): String {
            return when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "SCROLL_FORWARD"
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "SCROLL_BACKWARD"
                else -> action.toString()
            }
        }
    }
}
