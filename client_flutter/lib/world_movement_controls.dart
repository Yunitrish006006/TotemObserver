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
  Timer? _timer;
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
      _started = false;
      _release();
      widget.connection.addListener(_connectionChanged);
    }
  }

  void _connectionChanged() {
    if (!widget.connection.canMove) {
      _started = false;
      _release();
    }
  }

  void _clear() {
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
    if (active) _started = true;
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
    _yawDelta = (_yawDelta + dx.clamp(-200, 200) * 0.15).clamp(-180, 180);
    _pitchDelta = (_pitchDelta + dy.clamp(-200, 200) * 0.15).clamp(-90, 90);
  }

  KeyEventResult _key(FocusNode node, KeyEvent event) {
    if (!_active) return KeyEventResult.ignored;
    if (event.physicalKey == PhysicalKeyboardKey.escape) {
      _release();
      return KeyEventResult.handled;
    }
    if (event.physicalKey == PhysicalKeyboardKey.keyE &&
        widget.connection.canUseBlock) {
      if (event is KeyDownEvent &&
          !_useQueued &&
          _focus.hasPrimaryFocus &&
          widget.connection.prepareBlockUse()) {
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
    if (event is KeyUpEvent) {
      _keys.remove(event.physicalKey);
    } else {
      _keys.add(event.physicalKey);
    }
    return KeyEventResult.handled;
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
        if (widget.connection.canMove)
          _active
              ? Text(
                  'WASD 移動 · 滑鼠轉向 · Space 跳躍${widget.connection.canUseBlock ? ' · E 使用' : ''} · Esc 釋放',
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
