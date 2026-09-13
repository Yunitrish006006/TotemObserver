import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/connection.dart';
import 'connection_test.dart'
    show FakeTransport, hello, authenticated, worldBootstrap, worldState;

Future<(ObserverConnection, FakeTransport)> connect({bool use = true}) async {
  final socket = FakeTransport();
  final connection = ObserverConnection(open: (_) => socket);
  await connection.authenticate(
    'ws://127.0.0.1:25580/observer/bridge',
    'alice',
    'local-test-password',
  );
  socket.receive({
    ...hello,
    'worldMovementProtocol': 1,
    if (use) 'worldBlockUseProtocol': 1,
  });
  socket.receive(authenticated);
  socket.receive(worldBootstrap);
  socket.receive(worldState);
  socket.receive({'type': 'pong', 'seq': 2});
  return (connection, socket);
}

Map<String, Object> response({String outcome = 'applied'}) => {
  'type': 'world_block_use',
  'protocol': 1,
  'seq': 3,
  'sessionEpoch': 42,
  'subscriptionId': 1,
  'revision': 1,
  'dimension': 'minecraft:overworld',
  'outcome': outcome,
  'refreshRequired': outcome == 'applied',
};

void main() {
  testWidgets('intent has no target and applied response refreshes geometry', (
    tester,
  ) async {
    final (connection, socket) = await connect();
    final geometry = connection.worldGeometryRevision;
    expect(connection.sendBlockUse(), isTrue);
    expect(jsonDecode(socket.sent.last), {
      'type': 'world_block_use',
      'protocol': 1,
      'seq': 3,
      'sessionEpoch': 42,
      'subscriptionId': 1,
      'revision': 1,
      'dimension': 'minecraft:overworld',
    });
    expect(connection.sendBlockUse(), isFalse);
    expect(connection.canSendWorldSectionNow, isFalse);
    expect(
      connection.sendMovement(
        strafe: 0,
        forward: 1,
        yaw: 0,
        pitch: 0,
        jump: false,
      ),
      isFalse,
    );
    connection.resyncWorld();
    expect(jsonDecode(socket.sent.last)['type'], 'world_block_use');
    socket.receive(response());
    expect(connection.worldGeometryRevision, greaterThan(geometry));
    expect(connection.worldWindowRefreshing, isTrue);
    expect(connection.lastBlockUseOutcome, 'applied');
    expect(jsonDecode(socket.sent.last), {'type': 'world_bootstrap', 'seq': 4});
    expect(connection.sendBlockUse(), isFalse);
    socket.receive({...worldBootstrap, 'seq': 4, 'revision': 2});
    await tester.pump(const Duration(milliseconds: 220));
    expect(connection.sendBlockUse(), isTrue);
    expect(jsonDecode(socket.sent.last)['revision'], 2);
    connection.dispose();
  });

  testWidgets('unsupported and denied are nonfatal with bounded cooldown', (
    tester,
  ) async {
    for (final outcome in ['unsupported', 'denied', 'no_target']) {
      final (connection, socket) = await connect();
      final geometry = connection.worldGeometryRevision;
      connection.sendBlockUse();
      socket.receive(response(outcome: outcome));
      expect(connection.phase, ConnectionPhase.connected);
      expect(connection.lastBlockUseOutcome, outcome);
      expect(connection.worldGeometryRevision, geometry);
      expect(connection.worldWindowRefreshing, isFalse);
      expect(connection.sendBlockUse(), isFalse);
      await tester.pump(const Duration(milliseconds: 220));
      expect(connection.sendBlockUse(), isTrue);
      connection.dispose();
    }
  });

  testWidgets(
    'malformed stale unsolicited and inconsistent replies fail closed',
    (tester) async {
      for (final corrupt in <Map<String, Object>>[
        {'seq': 4},
        {'seq': 3.0},
        {'protocol': 2},
        {'sessionEpoch': 43},
        {'sessionEpoch': 42.0},
        {'subscriptionId': 2},
        {'revision': 2},
        {'dimension': 'minecraft:the_nether'},
        {'outcome': 'anything'},
        {'refreshRequired': false},
        {'refreshRequired': 1},
        {'x': 0},
      ]) {
        final (connection, socket) = await connect();
        connection.sendBlockUse();
        socket.receive({...response(), ...corrupt});
        expect(
          connection.phase,
          ConnectionPhase.offline,
          reason: corrupt.toString(),
        );
        expect(socket.closed, isTrue);
        connection.dispose();
      }
      final (connection, socket) = await connect();
      socket.receive(response());
      expect(connection.phase, ConnectionPhase.offline);
      connection.dispose();
    },
  );

  testWidgets(
    'capability absence pending movement timeout and cleanup are safe',
    (tester) async {
      final (legacy, _) = await connect(use: false);
      expect(legacy.canUseBlock, isFalse);
      expect(legacy.sendBlockUse(), isFalse);
      legacy.dispose();
      final (moving, _) = await connect();
      moving.sendMovement(strafe: 0, forward: 1, yaw: 0, pitch: 0, jump: false);
      expect(moving.sendBlockUse(), isFalse);
      moving.dispose();
      final (connection, socket) = await connect();
      connection.sendBlockUse();
      await tester.pump(const Duration(seconds: 5));
      expect(connection.phase, ConnectionPhase.offline);
      expect(socket.closed, isTrue);
      expect(connection.blockUsePending, isFalse);
      expect(connection.canUseBlock, isFalse);
      expect(connection.lastBlockUseOutcome, isNull);
      connection.dispose();
    },
  );
}
