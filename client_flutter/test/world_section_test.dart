import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/connection.dart';

class SectionFakeTransport implements BridgeTransport {
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

const sectionFingerprint =
    '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef';

const sectionHello = {
  'type': 'hello',
  'protocol': 1,
  'authentication': true,
  'registration': true,
  'playerIdentityProtocol': 1,
  'playerAdmission': true,
  'worldStateProtocol': 0,
  'worldBootstrapProtocol': 1,
  'worldRegistryProtocol': 1,
  'worldSectionProtocol': 1,
  'play': false,
};

const sectionAuthenticated = {
  'type': 'authenticated',
  'username': 'alice',
  'expiresInSeconds': 900,
  'playerUuid': '12345678-1234-1234-1234-123456789abc',
  'playerName': 'obs_123456781234',
  'sessionEpoch': 42,
  'playerAttached': true,
  'play': false,
};

Map<String, dynamic> bootstrap({required int seq, required int revision}) => {
  'type': 'world_bootstrap',
  'protocol': 1,
  'seq': seq,
  'sessionEpoch': 42,
  'subscriptionId': 1,
  'revision': revision,
  'dimension': 'minecraft:overworld',
  'minY': -64,
  'height': 384,
  'centerChunkX': 10,
  'centerChunkZ': -3,
  'radius': 2,
};

Map<String, dynamic> registryPage() => {
  'type': 'world_registry',
  'protocol': 1,
  'seq': 1,
  'sessionEpoch': 42,
  'fingerprint': sectionFingerprint,
  'offset': 0,
  'total': 200,
  'states': List.generate(8, (index) => 'minecraft:test_$index'),
};

String encodePart(int part) {
  final bytes = <int>[];
  final start = part * 1024;
  for (int index = start; index < start + 1024; index++) {
    int value = index % 200;
    while ((value & ~0x7f) != 0) {
      bytes.add((value & 0x7f) | 0x80);
      value >>= 7;
    }
    bytes.add(value);
  }
  return base64Encode(bytes);
}

Map<String, dynamic> sectionPart(int part, {String? fingerprint}) => {
  'type': 'world_section',
  'protocol': 1,
  'seq': 3,
  'sessionEpoch': 42,
  'subscriptionId': 1,
  'revision': 1,
  'registryFingerprint': fingerprint ?? sectionFingerprint,
  'dimension': 'minecraft:overworld',
  'chunkX': 10,
  'chunkZ': -3,
  'sectionY': -4,
  'part': part,
  'parts': 4,
  'stateCount': 1024,
  'data': encodePart(part),
};

Future<(ObserverConnection, SectionFakeTransport)> connectSection() async {
  final socket = SectionFakeTransport();
  final connection = ObserverConnection(open: (_) => socket);
  await connection.authenticate(
    'ws://127.0.0.1:25580/observer/bridge',
    'alice',
    'local-test-password',
  );
  socket.receive(sectionHello);
  socket.receive(sectionAuthenticated);
  expect(jsonDecode(socket.sent[1]), {'type': 'world_bootstrap', 'seq': 0});
  expect(jsonDecode(socket.sent[2]), {
    'type': 'world_registry',
    'seq': 1,
    'offset': 0,
  });
  expect(jsonDecode(socket.sent[3]), {'type': 'ping', 'seq': 2});
  socket.receive(bootstrap(seq: 0, revision: 1));
  socket.receive(registryPage());
  socket.receive({'type': 'pong', 'seq': 2});
  return (connection, socket);
}

void main() {
  test('assembles four section parts and invalidates them on resync', () async {
    final (connection, socket) = await connectSection();
    expect(connection.canRequestWorldSections, isTrue);

    connection.requestWorldSection(10, -3, -4);
    expect(jsonDecode(socket.sent.last), {
      'type': 'world_section',
      'seq': 3,
      'subscriptionId': 1,
      'revision': 1,
      'chunkX': 10,
      'chunkZ': -3,
      'sectionY': -4,
    });

    socket.receive(sectionPart(2));
    socket.receive(sectionPart(0));
    socket.receive(sectionPart(3));
    expect(connection.worldSection(10, -3, -4), isNull);
    socket.receive(sectionPart(1));

    final snapshot = connection.worldSection(10, -3, -4);
    expect(snapshot, isNotNull);
    expect(snapshot!.stateIds.length, 4096);
    expect(snapshot.stateAt(0, 0, 0), 0);
    expect(snapshot.stateAt(3, 4, 5), 107);
    expect(snapshot.stateAt(15, 15, 15), 95);

    connection.requestWorldSection(10, -3, -4);
    expect(socket.sent.length, 5, reason: 'cached section must not request again');
    connection.resyncWorld();
    expect(jsonDecode(socket.sent.last), {'type': 'world_bootstrap', 'seq': 4});
    socket.receive(bootstrap(seq: 4, revision: 2));
    expect(connection.worldSection(10, -3, -4), isNull);

    connection.dispose();
  });

  test('rejects a section part with a mismatched registry fingerprint', () async {
    final (connection, socket) = await connectSection();
    connection.requestWorldSection(10, -3, -4);
    socket.receive(
      sectionPart(
        0,
        fingerprint:
            'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
      ),
    );
    expect(connection.phase, ConnectionPhase.offline);
    expect(connection.status, '伺服器回應不相容，請重新連線');
    expect(socket.closed, isTrue);
    connection.dispose();
  });

  test('does not request sections outside bootstrap bounds', () async {
    final (connection, socket) = await connectSection();
    final before = socket.sent.length;
    connection.requestWorldSection(13, -3, -4);
    connection.requestWorldSection(10, -3, -5);
    connection.requestWorldSection(10, -3, 20);
    expect(socket.sent.length, before);
    connection.dispose();
  });
}
