import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/connection.dart';
import 'world_movement_client_test.dart' show connect, response;
import 'connection_test.dart'
    show FakeTransport, hello, authenticated, worldBootstrap, worldState;
import 'world_section_test.dart'
    show connectSection, bootstrap, sectionPart, unavailableSection;

void main() {
  testWidgets('stalled refresh and section requests close at their deadline', (
    tester,
  ) async {
    final (moving, movementSocket) = await connect();
    moving.sendMovement(strafe: 0, forward: 1, yaw: 0, pitch: 0, jump: false);
    movementSocket.receive({...response(3), 'x': 16.1});
    await tester.pump(const Duration(seconds: 5));
    expect(moving.phase, ConnectionPhase.offline);
    expect(moving.status, '世界視窗回應逾時，連線已結束');
    moving.dispose();
    final (section, _) = await connectSection();
    section.requestWorldSection(10, -3, -4);
    await tester.pump(const Duration(seconds: 5));
    expect(section.phase, ConnectionPhase.offline);
    expect(section.status, '區塊回應逾時，連線已結束');
    section.dispose();
  });

  testWidgets(
    'only a chunk crossing refreshes the window and new input uses its revision',
    (tester) async {
      final (connection, socket) = await connect();
      connection.sendMovement(
        strafe: 0,
        forward: 1,
        yaw: 0,
        pitch: 0,
        jump: false,
      );
      socket.receive({...response(3), 'x': 15.9});
      expect(connection.worldWindowRefreshing, isFalse);
      await tester.pump(const Duration(milliseconds: 100));
      connection.sendMovement(
        strafe: 0,
        forward: 1,
        yaw: 0,
        pitch: 0,
        jump: false,
      );
      socket.receive({...response(4, tick: 11), 'x': 16.1});
      expect(jsonDecode(socket.sent.last), {
        'type': 'world_bootstrap',
        'seq': 5,
      });
      expect(connection.worldWindowRefreshing, isTrue);
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
      socket.receive({
        ...worldBootstrap,
        'seq': 5,
        'revision': 2,
        'centerChunkX': 1,
      });
      await tester.pump(const Duration(milliseconds: 100));
      expect(
        connection.sendMovement(
          strafe: 0,
          forward: 1,
          yaw: 0,
          pitch: 0,
          jump: false,
        ),
        isTrue,
      );
      final next = jsonDecode(socket.sent.last) as Map<String, dynamic>;
      expect(next['revision'], 2);
      socket.receive({
        ...response(next['seq'] as int, tick: 12),
        'revision': 2,
        'x': 17.0,
      });
      expect(connection.worldWindowRefreshing, isFalse);
      connection.dispose();
    },
  );

  testWidgets(
    'window refresh drains a pending section before changing revision',
    (tester) async {
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
        'worldRegistryProtocol': 1,
        'worldSectionProtocol': 1,
      });
      socket.receive(authenticated);
      socket.receive(worldBootstrap);
      const hash =
          '0000000000000000000000000000000000000000000000000000000000000000';
      socket.receive({
        'type': 'world_registry',
        'protocol': 1,
        'seq': 1,
        'sessionEpoch': 42,
        'fingerprint': hash,
        'offset': 0,
        'total': 8,
        'states': List.generate(8, (i) => 'minecraft:test_$i'),
      });
      socket.receive({...worldState, 'seq': 2});
      socket.receive({'type': 'pong', 'seq': 3});
      connection.requestWorldSection(0, -1, 4);
      connection.sendMovement(
        strafe: 0,
        forward: 1,
        yaw: 0,
        pitch: 0,
        jump: false,
      );
      socket.receive({...response(5), 'x': 16.1});
      expect(connection.worldWindowRefreshing, isTrue);
      expect(connection.canSendWorldSectionNow, isFalse);
      expect((jsonDecode(socket.sent.last) as Map)['type'], 'world_movement');
      await tester.pump(const Duration(milliseconds: 100));
      final sentBeforeRetry = socket.sent.length;
      expect(
        connection.sendMovement(
          strafe: 0,
          forward: 1,
          yaw: 0,
          pitch: 0,
          jump: false,
        ),
        isFalse,
        reason: 'pending section drain blocks movement after cooldown expires',
      );
      expect(socket.sent.length, sentBeforeRetry);
      final terminal = {
        'type': 'world_section_unavailable',
        'protocol': 1,
        'seq': 4,
        'sessionEpoch': 42,
        'subscriptionId': 1,
        'revision': 1,
        'registryFingerprint': hash,
        'dimension': 'minecraft:overworld',
        'chunkX': 0,
        'chunkZ': -1,
        'sectionY': 4,
        'reason': 'not_loaded',
      };
      socket.receive(terminal);
      expect(jsonDecode(socket.sent.last), {
        'type': 'world_bootstrap',
        'seq': 6,
      });
      socket.receive({
        ...worldBootstrap,
        'seq': 6,
        'revision': 2,
        'centerChunkX': 1,
      });
      expect(connection.cachedWorldEntryCount, 0);
      socket.receive(terminal);
      expect(
        connection.phase,
        ConnectionPhase.offline,
        reason: 'old section cannot re-enter new revision',
      );
      connection.dispose();
    },
  );

  testWidgets('vertical retention discards old and late sections', (
    tester,
  ) async {
    final (connection, socket) = await connectSection();
    connection.requestWorldSection(10, -3, -4);
    final wanted = WorldSectionKey(
      subscriptionId: 1,
      revision: 1,
      chunkX: 10,
      chunkZ: -3,
      sectionY: -3,
    );
    connection.retainWorldSections({wanted});
    for (int part = 0; part < 4; part++) {
      socket.receive(sectionPart(part));
    }
    expect(connection.cachedWorldEntryCount, 0);
    connection.requestWorldSection(10, -3, -3);
    final seq = (jsonDecode(socket.sent.last) as Map)['seq'];
    socket.receive({...unavailableSection(), 'seq': seq, 'sectionY': -3});
    expect(connection.cachedWorldEntryCount, 1);
    connection.retainWorldSections({});
    expect(connection.cachedWorldEntryCount, 0);
    connection.dispose();
  });

  testWidgets(
    'world cache has a hard combined bound and revision clears markers',
    (tester) async {
      final (connection, socket) = await connectSection();
      for (int i = 0; i < 45; i++) {
        await tester.pump(const Duration(milliseconds: 100));
        final x = 10 + i ~/ 24, y = -4 + i % 24;
        connection.requestWorldSection(x, -3, y);
        final seq = (jsonDecode(socket.sent.last) as Map)['seq'];
        socket.receive({
          ...unavailableSection(),
          'seq': seq,
          'chunkX': x,
          'sectionY': y,
        });
        expect(connection.cachedWorldEntryCount, lessThanOrEqualTo(43));
      }
      expect(connection.worldSectionUnavailable(10, -3, -4), isFalse);
      connection.resyncWorld();
      final seq = (jsonDecode(socket.sent.last) as Map)['seq'] as int;
      socket.receive(bootstrap(seq: seq, revision: 2));
      expect(connection.cachedWorldEntryCount, 0);
      connection.dispose();
    },
  );
}
