import 'dart:async';
import 'dart:collection';

/// Shared FIFO budget for all account-bridge requests, including heartbeats.
/// Eight initial tokens plus at most 17 refills in any second remain below
/// the server's 32-frame window. Sequence order is never changed.
class OutboundFramePacer {
  OutboundFramePacer({
    required this.write,
    required this.onError,
    this.onBackgroundAvailable,
    this.createPeriodicTimer = Timer.periodic,
  });

  static const capacity = 8;
  static const maxQueued = 8;
  static const interval = Duration(milliseconds: 60);
  final void Function(String) write;
  final void Function() onError;
  final void Function()? onBackgroundAvailable;
  final Timer Function(Duration, void Function(Timer)) createPeriodicTimer;
  final Queue<String> _queue = Queue();
  int _tokens = capacity;
  Timer? _timer;
  int _timerTicks = 0;
  bool _closed = false;

  bool get canSendImmediately => !_closed && _tokens > 0 && _queue.isEmpty;

  /// Background reads leave room for fresh input without reordering any sequence.
  bool get canSendBackgroundImmediately =>
      !_closed && _tokens > 2 && _queue.isEmpty;

  void send(String message) {
    if (_closed) return;
    if (message.length > 1024 || _queue.length >= maxQueued) {
      _fail();
      return;
    }
    _queue.addLast(message);
    if (_timer == null) {
      _timerTicks = 0;
      _timer = createPeriodicTimer(interval, (timer) {
        final backgroundWasAvailable = canSendBackgroundImmediately;
        // Timer.tick counts elapsed intervals, including missed callbacks when
        // rendering delays the event loop. Credit remains capped at eight.
        final elapsedTicks = timer.tick - _timerTicks;
        _timerTicks = timer.tick;
        if (elapsedTicks > 0) {
          _tokens = (_tokens + elapsedTicks).clamp(0, capacity);
        }
        _drain();
        if (!backgroundWasAvailable && canSendBackgroundImmediately) {
          onBackgroundAvailable?.call();
        }
        if (_tokens == capacity && _queue.isEmpty) {
          _timer?.cancel();
          _timer = null;
        }
      });
    }
    _drain();
  }

  void _drain() {
    while (!_closed && _tokens > 0 && _queue.isNotEmpty) {
      _tokens--;
      final message = _queue.removeFirst();
      try {
        write(message);
      } catch (_) {
        _fail();
      }
    }
  }

  void _fail() {
    close();
    onError();
  }

  void close() {
    _closed = true;
    _timer?.cancel();
    _timer = null;
    _queue.clear();
  }
}
