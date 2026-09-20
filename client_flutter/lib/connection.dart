import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:web_socket_channel/web_socket_channel.dart';
import 'world/world_bootstrap.dart';
import 'world/world_state.dart';
import 'world/world_window.dart';

abstract interface class BridgeTransport {
  Stream<dynamic> get messages;
  Future<void> get ready;
  void send(String message);
  void close();
}

class SocketTransport implements BridgeTransport {
  SocketTransport(Uri uri)
    : _channel = WebSocketChannel.connect(
        uri,
        protocols: const ['totem-observer-account-v1'],
      );
  final WebSocketChannel _channel;
  @override
  Stream<dynamic> get messages => _channel.stream;
  @override
  Future<void> get ready => _channel.ready;
  @override
  void send(String message) => _channel.sink.add(message);
  @override
  void close() {
    unawaited(_channel.sink.close(1000));
  }
}

enum ConnectionPhase { offline, connecting, connected }

class ObserverConnection extends ChangeNotifier {
  ObserverConnection({BridgeTransport Function(Uri)? open})
    : _open = open ?? SocketTransport.new;
  final BridgeTransport Function(Uri) _open;
  BridgeTransport? _transport;
  StreamSubscription<dynamic>? _subscription;
  Timer? _deadline, _heartbeat, _responseDeadline, _worldDeadline;
  int _generation = 0,
      _sequence = 0,
      _lastPong = -1,
      _identityProtocol = 0,
      _worldProtocol = 0,
      _worldWindowProtocol = 0;
  String? _login;
  String _expectedAccount = '';
  bool _hello = false, _disposed = false, _playerAdmissionAvailable = false;
  ConnectionPhase phase = ConnectionPhase.offline;
  String status = '尚未連線';
  String account = '';
  String playerUuid = '';
  String playerName = '';
  int sessionEpoch = 0;
  bool playerAttached = false;
  WorldState? world;
  int replies = 0;

  Future<void> authenticate(
    String address,
    String username,
    String password, {
    bool register = false,
  }) async {
    disconnect();
    final uri = Uri.tryParse(address.trim());
    if (uri == null ||
        uri.scheme != 'ws' ||
        !['localhost', '127.0.0.1'].contains(uri.host) ||
        !uri.hasPort ||
        uri.port < 1 ||
        uri.port > 65535 ||
        uri.path != '/observer/bridge' ||
        uri.hasQuery ||
        uri.hasFragment ||
        uri.userInfo.isNotEmpty) {
      _fail('請輸入本機 Observer 連線位址');
      return;
    }
    if (!RegExp(r'^[a-z0-9_]{3,24}$').hasMatch(username) ||
        username == 'version' ||
        password.length < 12 ||
        password.length > 128) {
      _fail('帳號須為 3–24 個小寫英數或底線；密碼須為 12–128 個字元');
      return;
    }
    final generation = _generation;
    _expectedAccount = username;
    _login = jsonEncode({
      'type': register ? 'register' : 'login',
      'username': username,
      'password': password,
    });
    phase = ConnectionPhase.connecting;
    status = register ? '正在建立帳號…' : '正在登入…';
    _notify();
    _deadline = Timer(const Duration(seconds: 9), () => _fail('登入逾時，請重試'));
    try {
      final transport = _open(uri);
      _transport = transport;
      _subscription = transport.messages.listen(
        (data) {
          if (generation == _generation) _receive(data);
        },
        onError: (_) {
          if (generation == _generation) _fail('連線失敗，請確認伺服器已啟動');
        },
        onDone: () {
          if (generation == _generation) _fail('連線已結束，請重新登入');
        },
      );
      await transport.ready.timeout(const Duration(seconds: 5));
    } catch (_) {
      if (generation == _generation) _fail('無法連線，請確認位址與伺服器設定');
    }
  }

  void _receive(dynamic data) {
    try {
      if (data is! String || data.length > 2048) throw const FormatException();
      final message = jsonDecode(data) as Map<String, dynamic>;
      switch (message['type']) {
        case 'hello':
          if (_hello ||
              _login == null ||
              message['protocol'] != 1 ||
              message['authentication'] != true) {
            throw const FormatException();
          }
          final identityProtocol = message['playerIdentityProtocol'];
          if (identityProtocol != null && identityProtocol != 1) {
            throw const FormatException();
          }
          final playerAdmission = message['playerAdmission'];
          if (playerAdmission != null && playerAdmission is! bool) {
            throw const FormatException();
          }
          final worldProtocol = message['worldProtocol'];
          if (worldProtocol != null && worldProtocol != 1) {
            throw const FormatException();
          }
          if (worldProtocol == 1 &&
              (identityProtocol != 1 || playerAdmission != true)) {
            throw const FormatException();
          }
          final worldWindowProtocol = message['worldWindowProtocol'];
          if (worldWindowProtocol != null && worldWindowProtocol != 1) {
            throw const FormatException();
          }
          if (worldWindowProtocol == 1 && worldProtocol != 1) {
            throw const FormatException();
          }
          _identityProtocol = identityProtocol == 1 ? 1 : 0;
          _playerAdmissionAvailable = playerAdmission == true;
          _worldProtocol = worldProtocol == 1 ? 1 : 0;
          _worldWindowProtocol = worldWindowProtocol == 1 ? 1 : 0;
          _hello = true;
          _transport!.send(_login!);
          _login = null;
        case 'authenticated':
          if (!_hello ||
              phase != ConnectionPhase.connecting ||
              message['username'] != _expectedAccount ||
              message['play'] != false) {
            throw const FormatException();
          }
          if (_identityProtocol == 1) {
            final uuid = message['playerUuid'];
            final name = message['playerName'];
            final epoch = message['sessionEpoch'];
            final attached = message['playerAttached'];
            if (uuid is! String ||
                !RegExp(
                  r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
                ).hasMatch(uuid) ||
                name is! String ||
                !RegExp(r'^obs_[0-9a-f]{12}$').hasMatch(name) ||
                epoch is! int ||
                epoch <= 0 ||
                (attached != null && attached is! bool) ||
                (_playerAdmissionAvailable && attached != true)) {
              throw const FormatException();
            }
            playerUuid = uuid;
            playerName = name;
            sessionEpoch = epoch;
            playerAttached = attached == true;
          }
          _deadline?.cancel();
          account = _expectedAccount;
          phase = ConnectionPhase.connected;
          status = _worldProtocol == 1 && playerAttached
              ? '玩家已接入 Minecraft，等待世界控制面同步…'
              : playerAttached
              ? '玩家已接入 Minecraft'
              : _identityProtocol == 1
              ? '已驗證玩家身分'
              : '已連線';
          if (_worldProtocol == 1 && playerAttached) {
            _worldDeadline = Timer(
              const Duration(seconds: 5),
              () => _fail('世界控制面同步逾時，請重新連線'),
            );
          }
          _watchResponse();
          _heartbeat = Timer.periodic(
            const Duration(seconds: 5),
            (_) => ping(),
          );
          ping();
        case 'world_bootstrap':
          if (phase != ConnectionPhase.connected ||
              _worldProtocol != 1 ||
              !playerAttached ||
              world != null) {
            throw const FormatException();
          }
          final bootstrap = WorldBootstrap.fromMessage(message);
          if (bootstrap.sessionEpoch != sessionEpoch) {
            throw const FormatException();
          }
          world = WorldState.bootstrap(bootstrap);
          if (_worldWindowProtocol == 1) {
            status = '玩家已接入 Minecraft，世界核心已同步，等待區塊視窗…';
          } else {
            _worldDeadline?.cancel();
            status = '玩家已接入 Minecraft，世界核心已同步';
          }
          _notify();
        case 'world_window':
          if (phase != ConnectionPhase.connected ||
              _worldWindowProtocol != 1 ||
              !playerAttached ||
              world == null ||
              world!.window != null) {
            throw const FormatException();
          }
          final window = WorldWindow.fromMessage(message);
          if (window.sessionEpoch != sessionEpoch) {
            throw const FormatException();
          }
          world = world!.attachInitialWindow(window);
          _worldDeadline?.cancel();
          status = '玩家已接入 Minecraft，世界控制面已同步';
          _notify();
        case 'pong':
          final seq = message['seq'];
          if (phase != ConnectionPhase.connected ||
              seq is! int ||
              seq <= _lastPong ||
              seq >= _sequence) {
            throw const FormatException();
          }
          _lastPong = seq;
          replies++;
          _watchResponse();
          _notify();
        case 'auth_failed':
          _fail('帳號或密碼不正確，或目前無法建立帳號');
        case 'logged_out':
          disconnect();
        default:
          throw const FormatException();
      }
    } catch (_) {
      _fail('伺服器回應不相容，請重新連線');
    }
  }

  void ping() {
    if (phase != ConnectionPhase.connected) return;
    try {
      _transport?.send(jsonEncode({'type': 'ping', 'seq': _sequence++}));
    } catch (_) {
      _fail('連線已中斷');
    }
    _notify();
  }

  void _watchResponse() {
    _responseDeadline?.cancel();
    _responseDeadline = Timer(
      const Duration(seconds: 12),
      () => _fail('伺服器未回應，連線已結束'),
    );
  }

  void disconnect() {
    if (phase == ConnectionPhase.connected) {
      try {
        _transport?.send('{"type":"logout"}');
      } catch (_) {}
    }
    _generation++;
    _deadline?.cancel();
    _heartbeat?.cancel();
    _responseDeadline?.cancel();
    _worldDeadline?.cancel();
    unawaited(_subscription?.cancel());
    _subscription = null;
    _transport?.close();
    _transport = null;
    _login = null;
    _hello = false;
    _expectedAccount = '';
    _identityProtocol = 0;
    _worldProtocol = 0;
    _worldWindowProtocol = 0;
    _playerAdmissionAvailable = false;
    _sequence = 0;
    _lastPong = -1;
    replies = 0;
    account = '';
    playerUuid = '';
    playerName = '';
    sessionEpoch = 0;
    playerAttached = false;
    world = null;
    phase = ConnectionPhase.offline;
    status = '尚未連線';
    _notify();
  }

  void _fail(String message) {
    disconnect();
    status = message;
    _notify();
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    disconnect();
    super.dispose();
  }
}
