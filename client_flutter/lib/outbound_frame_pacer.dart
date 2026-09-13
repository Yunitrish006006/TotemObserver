import 'dart:async';
import 'dart:collection';

/// Shared FIFO budget for all account-bridge requests, including heartbeats.
/// Eight initial tokens plus at most 17 refills in any second remain below
/// the server's 32-frame window. Sequence order is never changed.
class OutboundFramePacer {
  OutboundFramePacer({required this.write, required this.onError});

  static const capacity = 8;
  static const maxQueued = 8;
  static const interval = Duration(milliseconds: 60);
  final void Function(String) write;
  final void Function() onError;
  final Queue<String> _queue = Queue();
  int _tokens = capacity;
  Timer? _timer;
  bool _closed = false;

  bool get canSendImmediately => !_closed && _tokens > 0 && _queue.isEmpty;

  void send(String message) {
    if (_closed) return;
    if (message.length > 1024 || _queue.length >= maxQueued) {
      _fail();
      return;
    }
    _queue.addLast(message);
    _timer ??= Timer.periodic(interval, (_) {
      if (_tokens < capacity) _tokens++;
      _drain();
      if (_tokens == capacity && _queue.isEmpty) {
        _timer?.cancel();
        _timer = null;
      }
    });
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
