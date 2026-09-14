import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'connection.dart';
import 'world_pointer_capture.dart';

/// Input owner; the child renderer consumes authoritative state only.
class WorldMovementControls extends StatefulWidget {
  const WorldMovementControls({
    super.key,
    required this.connection,
    required this.child,
    this.captureFactory = createWorldPointerCapture,
  });
  final ObserverConnection connection;
  final Widget child;
  final PointerCaptureFactory captureFactory;
  @override
  State<WorldMovementControls> createState() => _ControlsState();
}

class _ControlsState extends State<WorldMovementControls> {
  final _focus = FocusNode(debugLabel: 'Observer world controls');
  final Set<PhysicalKeyboardKey> _keys = {};
  late final WorldPointerCapture _capture;
  Timer? _timer, _outlineIdleTimer, _outlinePollCooldown;
  bool _outlineIdle = false;
  int? _queuedHotbarSlot;
  Timer? _hotbarQueueExpiry, _hotbarPollCooldown;
  final _hotbarQueueClock = Stopwatch();
  static final _hotbarKeys = [
    PhysicalKeyboardKey.digit1,
    PhysicalKeyboardKey.digit2,
    PhysicalKeyboardKey.digit3,
    PhysicalKeyboardKey.digit4,
    PhysicalKeyboardKey.digit5,
    PhysicalKeyboardKey.digit6,
    PhysicalKeyboardKey.digit7,
    PhysicalKeyboardKey.digit8,
    PhysicalKeyboardKey.digit9,
  ];
  bool _useQueued = false, _useLookSent = false;
  double _useYaw = 0, _usePitch = 0;
  bool _active = false,
      _started = false,
      _requested = false,
      _disposing = false;
  double _yawDelta = 0, _pitchDelta = 0;
  static final _allowed = {
    PhysicalKeyboardKey.keyW,
    PhysicalKeyboardKey.keyA,
    PhysicalKeyboardKey.keyS,
    PhysicalKeyboardKey.keyD,
    PhysicalKeyboardKey.space,
  };

  @override
  void initState() {
    super.initState();
    _capture = widget.captureFactory(_captureChanged, _look);
    widget.connection.addListener(_connectionChanged);
    _timer = Timer.periodic(
      const Duration(milliseconds: 110),
      (_) => _sample(),
    );
  }

  @override
  void didUpdateWidget(covariant WorldMovementControls oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.connection, widget.connection)) {
      oldWidget.connection.removeListener(_connectionChanged);
      oldWidget.connection.cancelBlockUsePreparation();
      oldWidget.connection.clearTargetOutline();
      _started = false;
      _release();
      widget.connection.addListener(_connectionChanged);
    }
  }

  void _connectionChanged() {
    if (!widget.connection.canMove) {
      _started = false;
      _release();
    } else {
      _pollOutline();
      if (mounted && !_disposing) setState(() {});
    }
  }

  void _clearHotbarQueue() {
    _queuedHotbarSlot = null;
    _hotbarQueueExpiry?.cancel();
    _hotbarQueueClock.stop();
  }

  void _clear() {
    _clearHotbarQueue();
    _hotbarPollCooldown?.cancel();
    _outlineIdleTimer?.cancel();
    _outlinePollCooldown?.cancel();
    _outlineIdle = false;
    widget.connection.clearTargetOutline(notify: true);
    _cancelUse();
    _keys.clear();
    _yawDelta = 0;
    _pitchDelta = 0;
  }

  void _captureChanged(bool active) {
    if (_disposing) return;
    if (active && (!_requested || !widget.connection.canMove)) {
      _capture.release();
      return;
    }
    if (!active) {
      _requested = false;
      _clear();
    }
    if (_active != active) setState(() => _active = active);
    if (active) {
      _started = true;
      _outlineActivity();
    }
  }

  void _release() {
    _requested = false;
    _clear();
    _capture.release();
    _captureChanged(false);
  }

  void _activate() {
    if (!widget.connection.canMove || !_capture.supported) return;
    _requested = true;
    _focus.requestFocus();
    _capture.request();
  }

  void _look(double dx, double dy) {
    if (_useQueued ||
        !_active ||
        !_focus.hasPrimaryFocus ||
        !dx.isFinite ||
        !dy.isFinite)
      return;
    _outlineActivity();
    _yawDelta = (_yawDelta + dx.clamp(-200, 200) * 0.15).clamp(-180, 180);
    _pitchDelta = (_pitchDelta + dy.clamp(-200, 200) * 0.15).clamp(-90, 90);
  }

  KeyEventResult _key(FocusNode node, KeyEvent event) {
    if (!_active) return KeyEventResult.ignored;
    if (event.physicalKey == PhysicalKeyboardKey.escape) {
      _release();
      return KeyEventResult.handled;
    }
    final hotbarSlot = _hotbarKeys.indexOf(event.physicalKey);
    if (hotbarSlot >= 0 && widget.connection.canRequestHotbar) {
      if (event is KeyDownEvent &&
          _focus.hasPrimaryFocus &&
          !_useQueued &&
          !widget.connection.blockUsePending) {
        _queuedHotbarSlot = hotbarSlot;
        _hotbarQueueClock
          ..reset()
          ..start();
        _hotbarQueueExpiry?.cancel();
        _hotbarQueueExpiry = Timer(
          const Duration(milliseconds: 750),
          _clearHotbarQueue,
        );
        _outlineActivity();
      }
      return KeyEventResult.handled;
    }
    if (event.physicalKey == PhysicalKeyboardKey.keyE &&
        (_queuedHotbarSlot != null || widget.connection.hotbarPending))
      return KeyEventResult.handled;
    if (event.physicalKey == PhysicalKeyboardKey.keyE &&
        widget.connection.canUseBlock) {
      if (event is KeyDownEvent &&
          !_useQueued &&
          _focus.hasPrimaryFocus &&
          widget.connection.prepareBlockUse()) {
        _outlineActivity();
        _useQueued = true;
        _useLookSent = false;
        _useYaw = widget.connection.worldYaw + _yawDelta;
        _usePitch = widget.connection.worldPitch + _pitchDelta;
        _yawDelta = 0;
        _pitchDelta = 0;
      }
      return KeyEventResult.handled;
    }
    if (!_allowed.contains(event.physicalKey)) return KeyEventResult.ignored;
    _outlineActivity();
    if (event is KeyUpEvent) {
      _keys.remove(event.physicalKey);
    } else {
      _keys.add(event.physicalKey);
    }
    return KeyEventResult.handled;
  }

  void _outlineActivity() {
    _outlineIdle = false;
    widget.connection.clearTargetOutline(notify: true);
    _outlineIdleTimer?.cancel();
    _outlineIdleTimer = Timer(const Duration(milliseconds: 330), () {
      _outlineIdle = true;
      _pollOutline();
    });
    if (mounted && !_disposing) setState(() {});
  }

  void _pollOutline() {
    final c = widget.connection;
    if (_disposing ||
        !_outlineIdle ||
        !_active ||
        !_focus.hasPrimaryFocus ||
        _useQueued ||
        _queuedHotbarSlot != null ||
        c.hotbarPending ||
        _keys.isNotEmpty ||
        _yawDelta != 0 ||
        _pitchDelta != 0 ||
        !c.worldOnGround ||
        !c.lastMovementApplied ||
        !c.canRequestTargetOutline ||
        (_outlinePollCooldown?.isActive ?? false))
      return;
    // Set the lease before send/notify to guard reentrant connection listeners.
    _outlinePollCooldown = Timer(const Duration(milliseconds: 500), () {});
    if (!c.requestTargetOutline()) _outlinePollCooldown?.cancel();
  }

  void _sample() {
    final connection = widget.connection;
    if (!_started || !connection.canMove) return;
    final enabled = _active && _focus.hasPrimaryFocus;
    if (_useQueued) {
      if (!enabled || !connection.blockUsePreparing) {
        _cancelUse();
      } else if (!_useLookSent) {
        _useLookSent = connection.sendMovement(
          strafe: 0,
          forward: 0,
          yaw: _useYaw,
          pitch: _usePitch,
          jump: false,
        );
      } else if (!connection.movementPending) {
        if (!connection.lastMovementApplied || connection.sendBlockUse()) {
          _cancelUse();
        }
      }
      return;
    }
    if (_queuedHotbarSlot != null) {
      if (!enabled || _hotbarQueueClock.elapsedMilliseconds >= 750) {
        _clearHotbarQueue();
      } else {
        if (connection.requestHotbar(slot: _queuedHotbarSlot)) {
          _clearHotbarQueue();
          _hotbarPollCooldown?.cancel();
          _hotbarPollCooldown = Timer(const Duration(seconds: 2), () {});
        }
        return;
      }
    }
    if (enabled &&
        connection.canRequestHotbar &&
        connection.lastMovementApplied &&
        connection.worldOnGround &&
        _keys.isEmpty &&
        _yawDelta == 0 &&
        _pitchDelta == 0 &&
        !(_hotbarPollCooldown?.isActive ?? false)) {
      // Acquire the lease before request/notify can reenter listeners.
      _hotbarPollCooldown = Timer(const Duration(seconds: 2), () {});
      if (connection.requestHotbar()) return;
      _hotbarPollCooldown?.cancel();
    }
    int held(PhysicalKeyboardKey key) => enabled && _keys.contains(key) ? 1 : 0;
    if (connection.sendMovement(
      strafe: held(PhysicalKeyboardKey.keyA) - held(PhysicalKeyboardKey.keyD),
      forward: held(PhysicalKeyboardKey.keyW) - held(PhysicalKeyboardKey.keyS),
      yaw: connection.worldYaw + (enabled ? _yawDelta : 0),
      pitch: connection.worldPitch + (enabled ? _pitchDelta : 0),
      jump: held(PhysicalKeyboardKey.space) == 1,
    )) {
      // New deltas after this send are rebased on the next server correction.
      _yawDelta = 0;
      _pitchDelta = 0;
    }
  }

  void _cancelUse() {
    _useQueued = false;
    _useLookSent = false;
    widget.connection.cancelBlockUsePreparation();
  }

  String _hotbarLabel() {
    final snapshot = widget.connection.hotbarSnapshot;
    if (snapshot == null) return '快捷列：操作世界後同步';
    final selected = snapshot.selected;
    if (selected == null) return '快捷列：目前無法讀取';
    final stack = snapshot.slots![selected];
    final held = stack.count == 0 ? '空手' : '${stack.item} × ${stack.count}';
    return '快捷列 ${selected + 1}/9 · $held${widget.connection.hotbarPending ? ' · 同步中' : ''}';
  }

  @override
  Widget build(BuildContext context) => Focus(
    focusNode: _focus,
    onKeyEvent: _key,
    onFocusChange: (focused) {
      if (!focused) _release();
    },
    child: Column(
      children: [
        widget.child,
        const SizedBox(height: 8),
        if (widget.connection.canRequestHotbar)
          Text(_hotbarLabel(), textAlign: TextAlign.center),
        if (widget.connection.canMove)
          _active
              ? Text(
                  'WASD 移動 · 滑鼠轉向 · Space 跳躍${widget.connection.canUseBlock ? ' · E 使用' : ''}${widget.connection.canRequestHotbar ? ' · 1–9 選槽' : ''} · Esc 釋放',
                )
              : TextButton(
                  onPressed: _capture.supported ? _activate : null,
                  child: Text(_capture.supported ? '點擊操作世界' : '此平台尚未支援滑鼠鎖定'),
                ),
      ],
    ),
  );
  @override
  void dispose() {
    _disposing = true;
    _clear();
    _timer?.cancel();
    widget.connection.removeListener(_connectionChanged);
    _capture.dispose();
    _focus.dispose();
    super.dispose();
  }
}
