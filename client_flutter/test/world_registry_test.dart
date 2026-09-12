import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/connection.dart';

class RegistryFakeTransport implements BridgeTransport {
  final input = StreamController<dynamic>.broadcast(sync: true);
  final sent = <String>[];
  bool closed = false;

  @override
  Stream<dynamic> get messages => input.stream;
  @override
  Future<void> get ready async {}
  @override
  void send(String message) => sent.add(message);
  @override
  void close() {
    closed = true;
  }

  void receive(Map<String, dynamic> value) => input.add(jsonEncode(value));
}

const registryHello = {
  'type': 'hello',
  'protocol': 1,
  'authentication': true,
  'registration': true,
  'playerIdentityProtocol': 1,
  'playerAdmission': true,
  'worldStateProtocol': 0,
  'worldBootstrapProtocol': 0,
  'worldRegistryProtocol': 1,
  'play': false,
};

const registryAuthenticated = {
  'type': 'authenticated',
  'username': 'alice',
  'expiresInSeconds': 900,
  'playerUuid': '12345678-1234-1234-1234-123456789abc',
  'playerName': 'obs_123456781234',
  'sessionEpoch': 42,
  'playerAttached': true,
  'play': false,
};

const fingerprint =
    '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef';

Map<String, dynamic> registryPage({
  required int seq,
  required int offset,
  String hash = fingerprint,
}) => {
  'type': 'world_registry',
  'protocol': 1,
  'seq': seq,
  'sessionEpoch': 42,
  'fingerprint': hash,
  'offset': offset,
  'total': 24,
  'states': List.generate(8, (index) => 'minecraft:test_${offset + index}'),
};

Future<(ObserverConnection, RegistryFakeTransport)> connectRegistry() async {
  final socket = RegistryFakeTransport();
  final connection = ObserverConnection(open: (_) => socket);
  await connection.authenticate(
    'ws://127.0.0.1:25580/observer/bridge',
    'alice',
    'local-test-password',
  );
  socket.receive(registryHello);
  socket.receive(registryAuthenticated);
  return (connection, socket);
}

void main() {
  test(
    'loads registry page zero and lazily requests missing state pages',
    () async {
      final (connection, socket) = await connectRegistry();
      expect(jsonDecode(socket.sent[1]), {
        'type': 'world_registry',
        'seq': 0,
        'offset': 0,
      });
      expect(jsonDecode(socket.sent[2]), {'type': 'ping', 'seq': 1});

      socket.receive(registryPage(seq: 0, offset: 0));
      socket.receive({'type': 'pong', 'seq': 1});
      expect(connection.hasWorldRegistry, isTrue);
      expect(connection.registryFingerprint, fingerprint);
      expect(connection.registryTotal, 24);
      expect(connection.blockStateName(0), 'minecraft:test_0');
      expect(connection.blockStateName(7), 'minecraft:test_7');

      connection.requestBlockState(3);
      expect(
        socket.sent.length,
        3,
        reason: 'cached ids must not request again',
      );
      connection.requestBlockState(9);
      expect(jsonDecode(socket.sent.last), {
        'type': 'world_registry',
        'seq': 2,
        'offset': 8,
      });
      socket.receive(registryPage(seq: 2, offset: 8));
      expect(connection.blockStateName(9), 'minecraft:test_9');

      connection.disconnect();
      expect(connection.hasWorldRegistry, isFalse);
      expect(connection.registryFingerprint, isEmpty);
      expect(connection.blockStateName(0), isNull);
      connection.dispose();
    },
  );

  test('rejects registry pages from a different fingerprint', () async {
    final (connection, socket) = await connectRegistry();
    socket.receive(registryPage(seq: 0, offset: 0));
    connection.requestBlockState(9);
    socket.receive(
      registryPage(
        seq: 2,
        offset: 8,
        hash:
            'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
      ),
    );
    expect(connection.phase, ConnectionPhase.offline);
    expect(connection.status, '伺服器回應不相容，請重新連線');
    expect(socket.closed, isTrue);
    connection.dispose();
  });
}
