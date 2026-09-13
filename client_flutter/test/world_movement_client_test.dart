import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/connection.dart';
import 'connection_test.dart'
    show FakeTransport, hello, authenticated, worldBootstrap, worldState;

Future<(ObserverConnection, FakeTransport)> connect({
  bool movement = true,
}) async {
  final socket = FakeTransport();
  final connection = ObserverConnection(open: (_) => socket);
  await connection.authenticate(
    'ws://127.0.0.1:25580/observer/bridge',
    'alice',
    'local-test-password',
  );
  socket.receive({...hello, if (movement) 'worldMovementProtocol': 1});
  socket.receive(authenticated);
  socket.receive(worldBootstrap);
  socket.receive(worldState);
  socket.receive({'type': 'pong', 'seq': 2});
  return (connection, socket);
}

Map<String, Object> response(int seq, {int tick = 10}) => {
  'type': 'world_movement',
  'protocol': 1,
  'seq': seq,
  'sessionEpoch': 42,
  'subscriptionId': 1,
  'revision': 1,
  'serverTick': tick,
  'applied': true,
  'onGround': false,
  'dimension': 'minecraft:overworld',
  'x': 12.5,
  'y': 64.4,
  'z': -3.5,
  'yaw': -90.0,
  'pitch': 90.0,
};

void main() {
  testWidgets('intent is bounded, single-pending and response owns position', (
    tester,
  ) async {
    final (connection, socket) = await connect();
    expect(connection.canMove, isTrue);
    final geometry = connection.worldGeometryRevision;
    expect(
      connection.sendMovement(
        strafe: 1,
        forward: 1,
        yaw: 270,
        pitch: 120,
        jump: true,
      ),
      isTrue,
    );
    final sent = jsonDecode(socket.sent.last) as Map<String, dynamic>;
    expect(sent, {
      'type': 'world_movement',
      'protocol': 1,
      'seq': 3,
      'sessionEpoch': 42,
      'subscriptionId': 1,
      'revision': 1,
      'dimension': 'minecraft:overworld',
      'strafe': 1,
      'forward': 1,
      'yaw': -9000,
      'pitch': 9000,
      'jump': 1,
    });
    expect(connection.worldX, 12.25);
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
    socket.receive(response(3));
    expect(connection.worldX, 12.5);
    expect(connection.worldY, 64.4);
    expect(connection.worldYaw, -90);
    expect(connection.worldOnGround, isFalse);
    expect(connection.worldGeometryRevision, geometry);
    expect(
      connection.sendMovement(
        strafe: 0,
        forward: 0,
        yaw: 0,
        pitch: 0,
        jump: false,
      ),
      isFalse,
    );
    await tester.pump(const Duration(milliseconds: 100));
    expect(
      connection.sendMovement(
        strafe: 0,
        forward: 0,
        yaw: 0,
        pitch: 0,
        jump: false,
      ),
      isTrue,
    );
    socket.receive(response(4, tick: 12));
    connection.dispose();
  });

  testWidgets('unknown, stale and malformed corrections fail closed', (
    tester,
  ) async {
    for (final corrupt in <Map<String, Object>>[
      {'seq': 4},
      {'seq': 3.0},
      {'sessionEpoch': 42.0},
      {'subscriptionId': 1.0},
      {'revision': 1.0},
      {'protocol': 1.0},
      {'sessionEpoch': 43},
      {'revision': 2},
      {'subscriptionId': 2},
      {'dimension': 'minecraft:the_nether'},
      {'protocol': 2},
      {'serverTick': -1},
      {'serverTick': 1.5},
      {'x': 32000001},
      {'pitch': 91},
      {'applied': 1},
      {'onGround': 'true'},
      {'unexpected': 0},
    ]) {
      final (connection, socket) = await connect();
      connection.sendMovement(
        strafe: 0,
        forward: 1,
        yaw: 0,
        pitch: 0,
        jump: false,
      );
      socket.receive({...response(3), ...corrupt});
      expect(
        connection.phase,
        ConnectionPhase.offline,
        reason: corrupt.toString(),
      );
      expect(socket.closed, isTrue);
      connection.dispose();
    }
  });

  testWidgets('server tick cannot regress and disconnect clears timers', (
    tester,
  ) async {
    final (connection, socket) = await connect();
    connection.sendMovement(
      strafe: 0,
      forward: 1,
      yaw: 0,
      pitch: 0,
      jump: false,
    );
    socket.receive(response(3, tick: 20));
    await tester.pump(const Duration(milliseconds: 100));
    connection.sendMovement(
      strafe: 0,
      forward: 1,
      yaw: 0,
      pitch: 0,
      jump: false,
    );
    socket.receive(response(4, tick: 20));
    expect(connection.phase, ConnectionPhase.offline);
    await tester.pump(const Duration(seconds: 6));
    expect(connection.canMove, isFalse);
    connection.dispose();
  });

  testWidgets('capability absence and invalid local input send no movement', (
    tester,
  ) async {
    final (legacy, socket) = await connect(movement: false);
    final count = socket.sent.length;
    expect(
      legacy.sendMovement(strafe: 0, forward: 1, yaw: 0, pitch: 0, jump: false),
      isFalse,
    );
    expect(socket.sent.length, count);
    legacy.dispose();
    final (connection, enabled) = await connect();
    final before = enabled.sent.length;
    expect(
      connection.sendMovement(
        strafe: 2,
        forward: 0,
        yaw: 0,
        pitch: 0,
        jump: false,
      ),
      isFalse,
    );
    expect(
      connection.sendMovement(
        strafe: 0,
        forward: 0,
        yaw: double.nan,
        pitch: 0,
        jump: false,
      ),
      isFalse,
    );
    expect(enabled.sent.length, before);
    connection.dispose();
  });

  testWidgets('unanswered input closes at its bounded deadline', (
    tester,
  ) async {
    final (connection, socket) = await connect();
    connection.sendMovement(
      strafe: 0,
      forward: 1,
      yaw: 0,
      pitch: 0,
      jump: false,
    );
    await tester.pump(const Duration(seconds: 5));
    expect(connection.phase, ConnectionPhase.offline);
    expect(socket.closed, isTrue);
    connection.dispose();
  });
}
