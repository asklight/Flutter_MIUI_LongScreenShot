library miui_long_screenshot;

import 'dart:async';
import 'dart:collection';
import 'dart:ffi' as ffi;
import 'dart:isolate';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Wraps a vertical Flutter scroll view and exposes it to MIUI's system long
/// screenshot tool through an Android native proxy view.
class MiuiLongScreenshot extends StatefulWidget {
  const MiuiLongScreenshot({
    super.key,
    required this.child,
    this.controller,
    this.enabled = true,
    this.scrollFactor = 1.0,
  });

  final Widget child;

  /// Optional controller of the wrapped scroll view.
  ///
  /// Passing this is more deterministic than subtree discovery. If omitted,
  /// the widget searches the wrapped subtree for the first vertical
  /// [ScrollableState].
  final ScrollController? controller;

  final bool enabled;

  /// Native scroll distance is physical pixels. Flutter scroll offset is
  /// logical pixels. Keep the default 1:1 conversion unless a page needs
  /// device-specific tuning.
  final double scrollFactor;

  @override
  State<MiuiLongScreenshot> createState() => _MiuiLongScreenshotState();
}

class _MiuiLongScreenshotState extends State<MiuiLongScreenshot> {
  final GlobalKey _contentKey = GlobalKey();
  ScrollPosition? _position;
  bool _active = true;

  @override
  void initState() {
    super.initState();
    _MiuiLongScreenshotRegistry.instance.register(this);
    _syncAfterFrame();
  }

  @override
  void didUpdateWidget(covariant MiuiLongScreenshot oldWidget) {
    super.didUpdateWidget(oldWidget);
    _syncAfterFrame();
  }

  @override
  void deactivate() {
    // mounted stays true during deactivation. Remove the target before its
    // keyed subtree becomes inactive, including when several routes go away.
    _active = false;
    _position = null;
    _MiuiLongScreenshotRegistry.instance.unregister(this);
    super.deactivate();
  }

  @override
  void activate() {
    super.activate();
    _active = true;
    _MiuiLongScreenshotRegistry.instance.register(this);
    _syncAfterFrame();
  }

  @override
  void dispose() {
    _active = false;
    _MiuiLongScreenshotRegistry.instance.unregister(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return NotificationListener<ScrollMetricsNotification>(
      onNotification: (_) {
        _markActiveAndUpdate();
        return false;
      },
      child: NotificationListener<ScrollNotification>(
        onNotification: (_) {
          _markActiveAndUpdate();
          return false;
        },
        child: KeyedSubtree(
          key: _contentKey,
          child: widget.child,
        ),
      ),
    );
  }

  bool get hasTarget {
    _syncPosition();
    return widget.enabled && _position != null;
  }

  Map<String, Object?> collectState({int consumedNativeY = 0}) {
    _syncPosition();
    final position = _position;
    return {
      'hasTarget': widget.enabled && _position != null,
      'canScrollDown': widget.enabled && _canScroll(1),
      'canScrollUp': widget.enabled && _canScroll(-1),
      'consumedNativeY': consumedNativeY,
      'pixels': position?.hasPixels == true ? position?.pixels : null,
      'minPixels': position?.hasContentDimensions == true
          ? position?.minScrollExtent
          : null,
      'maxPixels': position?.hasContentDimensions == true
          ? position?.maxScrollExtent
          : null,
    };
  }

  bool canScrollVertically(int direction) {
    _syncPosition();
    return widget.enabled && _canScroll(direction);
  }

  Map<String, Object?> scrollByNativePixels(int nativeY) {
    _syncPosition();

    final position = _position;
    if (!widget.enabled ||
        position == null ||
        !position.hasPixels ||
        !position.hasContentDimensions ||
        widget.scrollFactor <= 0) {
      return collectState()
        ..addAll({
          'requestedNativeY': nativeY,
          'scrollFactor': widget.scrollFactor,
          'skipReason': 'no_position',
        });
    }

    final devicePixelRatio = _devicePixelRatio();
    final beforePixels = position.pixels;
    final logicalDelta = nativeY / devicePixelRatio * widget.scrollFactor;
    final target = (position.pixels + logicalDelta).clamp(
      position.minScrollExtent,
      position.maxScrollExtent,
    );

    if ((target - position.pixels).abs() > precisionErrorTolerance) {
      try {
        position.jumpTo(target);
      } catch (_) {
        // The ScrollPosition may detach while MIUI is running long screenshot.
      }
    }

    final consumedLogicalY = position.pixels - beforePixels;
    final consumedNativeY =
        (consumedLogicalY * devicePixelRatio / widget.scrollFactor).round();
    return collectState(consumedNativeY: consumedNativeY)
      ..addAll({
        'requestedNativeY': nativeY,
        'devicePixelRatio': devicePixelRatio,
        'scrollFactor': widget.scrollFactor,
        'beforePixels': beforePixels,
        'targetPixels': target,
        'afterPixels': position.pixels,
        'logicalDelta': logicalDelta,
        'consumedLogicalY': consumedLogicalY,
      });
  }

  Rect? globalRect() {
    if (!_active) return null;
    final context = _contentKey.currentContext;
    final renderObject = context?.findRenderObject();
    if (renderObject is! RenderBox ||
        !renderObject.attached ||
        !renderObject.hasSize) {
      return null;
    }

    return renderObject.localToGlobal(Offset.zero) & renderObject.size;
  }

  Size? screenSize() {
    return MediaQuery.maybeOf(context)?.size;
  }

  void _markActiveAndUpdate() {
    if (!_active) return;
    _syncPosition();
    _MiuiLongScreenshotRegistry.instance.markActive(this);
    _MiuiLongScreenshotRegistry.instance.updateNativeState();
  }

  void _syncAfterFrame() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_active) return;
      _markActiveAndUpdate();
    });
  }

  void _syncPosition() {
    _position = _active
        ? _positionFromController() ?? _positionFromSubtree()
        : null;
  }

  ScrollPosition? _positionFromController() {
    final controller = widget.controller;
    if (controller == null || !controller.hasClients) return null;

    for (final position in controller.positions) {
      if (_isVertical(position)) return position;
    }
    return controller.positions.isEmpty ? null : controller.positions.first;
  }

  ScrollPosition? _positionFromSubtree() {
    final context = _contentKey.currentContext;
    if (context is! Element) return null;

    ScrollPosition? result;
    void visit(Element element) {
      if (result != null) return;

      if (element is StatefulElement && element.state is ScrollableState) {
        final state = element.state as ScrollableState;
        try {
          final position = state.position;
          if (_isVertical(position)) {
            result = position;
            return;
          }
        } catch (_) {
          return;
        }
      }

      element.visitChildren(visit);
    }

    context.visitChildren(visit);
    return result;
  }

  bool _isVertical(ScrollPosition position) {
    try {
      return position.axis == Axis.vertical;
    } catch (_) {
      return false;
    }
  }

  bool _canScroll(int direction) {
    final position = _position;
    if (position == null ||
        !position.hasPixels ||
        !position.hasContentDimensions) {
      return false;
    }

    if (direction > 0) {
      return position.pixels <
          position.maxScrollExtent - precisionErrorTolerance;
    }
    if (direction < 0) {
      return position.pixels >
          position.minScrollExtent + precisionErrorTolerance;
    }
    return false;
  }

  double _devicePixelRatio() {
    final mediaQuery = MediaQuery.maybeOf(context);
    if (mediaQuery != null) return mediaQuery.devicePixelRatio;

    // ignore: deprecated_member_use
    return WidgetsBinding.instance.window.devicePixelRatio;
  }
}

class _MiuiLongScreenshotRegistry {
  _MiuiLongScreenshotRegistry._() {
    if (_isAndroid) {
      _nativeBridge =
          _MiuiLongScreenshotNativeBridge.tryCreate(_handleNativeMessage);
      _channel.setMethodCallHandler(_handleNativeCall);
    }
  }

  static final _MiuiLongScreenshotRegistry instance =
      _MiuiLongScreenshotRegistry._();

  static const MethodChannel _channel = MethodChannel('miui_long_screenshot');

  _MiuiLongScreenshotNativeBridge? _nativeBridge;

  final ListQueue<_MiuiLongScreenshotState> _targets =
      ListQueue<_MiuiLongScreenshotState>();

  static bool get _isAndroid {
    return !kIsWeb && defaultTargetPlatform == TargetPlatform.android;
  }

  void register(_MiuiLongScreenshotState target) {
    if (!_isAndroid) return;
    markActive(target);
  }

  void unregister(_MiuiLongScreenshotState target) {
    if (!_isAndroid) return;
    _targets.remove(target);
    updateNativeState();
  }

  void markActive(_MiuiLongScreenshotState target) {
    if (!_isAndroid) return;
    _targets.remove(target);
    _targets.addLast(target);
  }

  void updateNativeState() {
    if (!_isAndroid) return;
    final state = _currentState();
    if (_nativeBridge != null) {
      _nativeBridge!.updateScrollState(state);
      return;
    }
    unawaited(_invokeNative('updateScrollState', state));
  }

  Future<Object?> _handleNativeCall(MethodCall call) async {
    switch (call.method) {
      case 'canScrollVertically':
        final target = _selectTarget();
        final state = target?.collectState() ?? _emptyState();

        unawaited(_invokeNative('updateScrollState', state));
        return state;

      case 'scrollBy':
        final arguments = call.arguments as Map<dynamic, dynamic>?;
        final nativeY = arguments?['y'] as int? ?? 0;
        final target = _selectTarget();
        final state = target?.scrollByNativePixels(nativeY) ?? _emptyState();

        unawaited(_invokeNative('updateScrollState', state));
        return state;

      default:
        throw MissingPluginException('No ${call.method} implementation');
    }
  }

  Future<void> _handleNativeMessage(dynamic message) async {
    final args = message as List<dynamic>;
    final method = args[0] as String;

    switch (method) {
      case 'canScrollVertically':
        final target = _selectTarget();
        final state = target?.collectState() ?? _emptyState();
        _nativeBridge?.updateScrollState(state);
        break;

      case 'scrollBy':
        final nativeY = args[2] as int;
        final callbackId = args[3] as int;
        final target = _selectTarget();
        final state = target?.scrollByNativePixels(nativeY) ?? _emptyState();
        _nativeBridge?.updateScrollState(state);
        _nativeBridge?.resultCallback(callbackId, _encodeScrollResult(state));
        break;
    }
  }

  int _encodeScrollResult(Map<String, Object?> state) {
    final canScrollDown = state['canScrollDown'] == true ? 1 : 0;
    final consumedNativeY = state['consumedNativeY'] as int? ?? 0;
    final magnitude = consumedNativeY.abs().clamp(0, 0x1fffffff).toInt();
    final sign = consumedNativeY < 0 ? 0x2 : 0;
    return (magnitude << 2) | sign | canScrollDown;
  }

  Map<String, Object?> _currentState() {
    return _selectTarget()?.collectState() ?? _emptyState();
  }

  Map<String, Object?> _emptyState() {
    return const {
      'hasTarget': false,
      'canScrollDown': false,
      'canScrollUp': false,
      'consumedNativeY': 0,
    };
  }

  _MiuiLongScreenshotState? _selectTarget() {
    final candidates = _targets
        .where((target) =>
            target.mounted && target._active && target.widget.enabled)
        .toList(growable: false)
        .reversed;

    _MiuiLongScreenshotState? fallback;
    double fallbackScore = -1;

    for (final target in candidates) {
      if (!target.hasTarget) continue;

      final rect = target.globalRect();
      final screenSize = target.screenSize();
      if (rect == null || screenSize == null) {
        fallback ??= target;
        continue;
      }

      final screen = Offset.zero & screenSize;
      final visible = rect.intersect(screen);
      if (visible.isEmpty) continue;

      final screenCenter = screen.center;
      final score = visible.width * visible.height +
          (rect.contains(screenCenter)
              ? screenSize.width * screenSize.height
              : 0);

      if (score > fallbackScore) {
        fallbackScore = score;
        fallback = target;
      }
    }

    return fallback;
  }

  Future<void> _invokeNative(String method, Object? arguments) async {
    try {
      await _channel.invokeMethod<void>(method, arguments);
    } on MissingPluginException {
      // Non-Android targets and older native builds should behave as no-op.
    } catch (_) {
      // Screenshot support must never affect normal page scrolling.
    }
  }
}

typedef _InitializeApiNative = ffi.IntPtr Function(ffi.Pointer<ffi.Void>);
typedef _InitializeApiDart = int Function(ffi.Pointer<ffi.Void>);
typedef _RegisterSendPortNative = ffi.Void Function(ffi.Int64);
typedef _RegisterSendPortDart = void Function(int);
typedef _UpdateScrollStateNative = ffi.Void Function(
  ffi.Int32,
  ffi.Int32,
  ffi.Int32,
);
typedef _UpdateScrollStateDart = void Function(int, int, int);
typedef _ResultCallbackNative = ffi.Void Function(ffi.Int32, ffi.Int32);
typedef _ResultCallbackDart = void Function(int, int);

class _MiuiLongScreenshotNativeBridge {
  _MiuiLongScreenshotNativeBridge._({
    required ReceivePort receivePort,
    required StreamSubscription<dynamic> subscription,
    required _UpdateScrollStateDart updateScrollStateNative,
    required this.resultCallback,
  })  : _receivePort = receivePort,
        _subscription = subscription,
        _updateScrollStateNative = updateScrollStateNative;

  final ReceivePort _receivePort;
  final StreamSubscription<dynamic> _subscription;
  final _UpdateScrollStateDart _updateScrollStateNative;
  final _ResultCallbackDart resultCallback;

  static _MiuiLongScreenshotNativeBridge? tryCreate(
    Future<void> Function(dynamic message) onMessage,
  ) {
    try {
      final library = ffi.DynamicLibrary.open('libmiui_long_screenshot.so');
      final initializeApi =
          library.lookupFunction<_InitializeApiNative, _InitializeApiDart>(
        'initializeApi',
      );
      final registerSendPort = library
          .lookupFunction<_RegisterSendPortNative, _RegisterSendPortDart>(
        'registerSendPort',
      );
      final updateScrollState = library
          .lookupFunction<_UpdateScrollStateNative, _UpdateScrollStateDart>(
        'updateScrollState',
      );
      final resultCallback =
          library.lookupFunction<_ResultCallbackNative, _ResultCallbackDart>(
        'resultCallback',
      );

      final initialized = initializeApi(ffi.NativeApi.initializeApiDLData) == 0;
      if (!initialized) return null;

      final receivePort = ReceivePort();
      final subscription = receivePort.listen((message) {
        unawaited(onMessage(message));
      });
      registerSendPort(receivePort.sendPort.nativePort);

      return _MiuiLongScreenshotNativeBridge._(
        receivePort: receivePort,
        subscription: subscription,
        updateScrollStateNative: updateScrollState,
        resultCallback: resultCallback,
      );
    } catch (_) {
      return null;
    }
  }

  void updateScrollState(Map<String, Object?> state) {
    _updateScrollStateNative(
      state['hasTarget'] == true ? 1 : 0,
      state['canScrollDown'] == true ? 1 : 0,
      state['canScrollUp'] == true ? 1 : 0,
    );
  }

  void dispose() {
    unawaited(_subscription.cancel());
    _receivePort.close();
  }
}
