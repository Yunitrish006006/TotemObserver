import 'dart:async';
import 'dart:js_interop';
import 'package:web/web.dart' as web;
import 'world_pointer_capture_contract.dart';

WorldPointerCapture createWorldPointerCapture(
  void Function(bool) onChanged,
  void Function(double, double) onLook,
) => _BrowserCapture(onChanged, onLook);

class _BrowserCapture implements WorldPointerCapture {
  _BrowserCapture(this.onChanged, this.onLook) {
    _change = ((web.Event _) {
      if (_disposed || !_wanted) {
        _exitOwned();
        return;
      }
      final locked = web.document.pointerLockElement == _target;
      if (!locked) _wanted = false;
      onChanged(locked);
    }).toJS;
    _lost = ((web.Event _) => release()).toJS;
    _visibility = ((web.Event _) {
      if (web.document.hidden) release();
    }).toJS;
    _mouse = ((web.MouseEvent event) {
      if (!_disposed && _wanted && web.document.pointerLockElement == _target) {
        onLook(event.movementX, event.movementY);
      }
    }).toJS;
    web.document.addEventListener('pointerlockchange', _change);
    web.document.addEventListener('pointerlockerror', _lost);
    web.document.addEventListener('mousemove', _mouse);
    web.document.addEventListener('visibilitychange', _visibility);
    web.window.addEventListener('blur', _lost);
  }
  final void Function(bool) onChanged;
  final void Function(double, double) onLook;
  final web.Element? _target = web.document.documentElement;
  late final JSFunction _change, _lost, _visibility, _mouse;
  bool _disposed = false, _wanted = false;
  @override
  bool get supported => _target != null;
  @override
  void request() {
    unawaited(_request());
  }

  Future<void> _request() async {
    if (_disposed || !supported) return;
    _wanted = true;
    try {
      await _target!.requestPointerLock().toDart;
      if (_disposed || !_wanted) _exitOwned();
    } catch (_) {
      if (!_disposed) release();
    }
  }

  void _exitOwned() {
    if (_target != null && web.document.pointerLockElement == _target) {
      web.document.exitPointerLock();
    }
  }

  @override
  void release() {
    _wanted = false;
    _exitOwned();
    if (!_disposed) onChanged(false);
  }

  @override
  void dispose() {
    _disposed = true;
    _wanted = false;
    _exitOwned();
    web.document.removeEventListener('pointerlockchange', _change);
    web.document.removeEventListener('pointerlockerror', _lost);
    web.document.removeEventListener('mousemove', _mouse);
    web.document.removeEventListener('visibilitychange', _visibility);
    web.window.removeEventListener('blur', _lost);
  }
}
