import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/connection.dart';
import 'package:totem_observer_client/world_destroy.dart';
import 'connection_test.dart'
    show FakeTransport, hello, authenticated, worldBootstrap, worldState;

Future<(ObserverConnection, FakeTransport)> connect({
  Object? protocol = 1,
}) async {
  final s = FakeTransport();
  final c = ObserverConnection(open: (_) => s);
  await c.authenticate(
    'ws://127.0.0.1:25580/observer/bridge',
    'alice',
    'local-test-password',
  );
  s.receive({
    ...hello,
    'worldMovementProtocol': 1,
    'worldBlockUseProtocol': 1,
    'worldHotbarProtocol': 1,
    if (protocol != null) 'worldBlockDestroyProtocol': protocol,
  });
  if (c.phase == ConnectionPhase.offline) return (c, s);
  s.receive(authenticated);
  s.receive(worldBootstrap);
  s.receive(worldState);
  s.receive({'type': 'pong', 'seq': 2});
  return (c, s);
}

Map<String, dynamic> response({
  int seq = 3,
  int operation = 3,
  String action = 'start',
  String outcome = 'active',
  double progress = 0.1,
}) => {
  'type': 'world_block_destroy',
  'protocol': 1,
  'seq': seq,
  'sessionEpoch': 42,
  'subscriptionId': 1,
  'revision': 1,
  'dimension': 'minecraft:overworld',
  'operation': operation,
  'action': action,
  'outcome': outcome,
  'progress': progress,
  'worldMayHaveChanged': true,
  'refreshRequired': outcome != 'active',
};

void main() {
  testWidgets(
    'mining sends intent, serializes work and refreshes only on confirmation',
    (tester) async {
      final (c, s) = await connect();
      expect(c.sendBlockDestroy(WorldDestroyAction.hold), isFalse);
      expect(c.sendBlockDestroy(WorldDestroyAction.start), isTrue);
      expect(jsonDecode(s.sent.last), {
        'type': 'world_block_destroy',
        'protocol': 1,
        'seq': 3,
        'sessionEpoch': 42,
        'subscriptionId': 1,
        'revision': 1,
        'dimension': 'minecraft:overworld',
        'operation': 3,
        'action': 'start',
      });
      expect(c.destroySnapshot, isNull);
      expect(c.sendBlockDestroy(WorldDestroyAction.cancel), isFalse);
      expect(c.sendBlockUse(), isFalse);
      expect(c.requestHotbar(), isFalse);
      expect(c.canSendWorldSectionNow, isFalse);
      expect(
        c.sendMovement(strafe: 0, forward: 1, yaw: 0, pitch: 0, jump: false),
        isFalse,
      );
      c.resyncWorld();
      expect(jsonDecode(s.sent.last)['type'], 'world_block_destroy');
      s.receive(response());
      expect(c.destroySnapshot!.progress, 0.1);
      expect(c.worldWindowRefreshing, isFalse);
      expect(c.sendBlockDestroy(WorldDestroyAction.start), isFalse);
      expect(c.sendBlockDestroy(WorldDestroyAction.hold), isFalse);
      await tester.pump(const Duration(milliseconds: 100));
      expect(c.sendBlockDestroy(WorldDestroyAction.hold), isTrue);
      expect(jsonDecode(s.sent.last)['operation'], 3);
      expect(c.destroySnapshot!.progress, 0.1);
      s.receive(
        response(seq: 4, action: 'hold', outcome: 'changed', progress: 1),
      );
      expect(c.worldWindowRefreshing, isTrue);
      expect(c.destroySnapshot, isNull);
      expect(jsonDecode(s.sent.last)['type'], 'world_bootstrap');
      expect(c.sendBlockDestroy(WorldDestroyAction.start), isFalse);
      s.receive({...worldBootstrap, 'seq': 5, 'revision': 2});
      expect(c.destroyActive, isFalse);
      expect(c.worldWindowRefreshing, isFalse);
      await tester.pump(const Duration(milliseconds: 100));
      expect(c.sendBlockDestroy(WorldDestroyAction.start), isTrue);
      expect(jsonDecode(s.sent.last)['operation'], 6);
      expect(jsonDecode(s.sent.last)['revision'], 2);
      c.dispose();
    },
  );

  testWidgets(
    'cancel is immediate after ack and terminal denial also consumes owner',
    (tester) async {
      final (c, s) = await connect();
      c.sendBlockDestroy(WorldDestroyAction.start);
      s.receive(response());
      expect(c.sendBlockDestroy(WorldDestroyAction.cancel), isTrue);
      s.receive(
        response(seq: 4, action: 'cancel', outcome: 'cancelled', progress: 0),
      );
      expect(c.worldWindowRefreshing, isTrue);
      expect(jsonDecode(s.sent.last)['type'], 'world_bootstrap');
      c.dispose();
      final (denied, ds) = await connect();
      denied.sendBlockDestroy(WorldDestroyAction.start);
      ds.receive({
        ...response(outcome: 'denied', progress: 0),
        'worldMayHaveChanged': false,
      });
      expect(denied.phase, ConnectionPhase.connected);
      expect(denied.worldWindowRefreshing, isTrue);
      denied.dispose();
    },
  );

  testWidgets(
    'malformed unsolicited and mismatched mining confirmations fail closed',
    (tester) async {
      for (final corrupt in <Map<String, dynamic>>[
        {'seq': 4},
        {'seq': 3.0},
        {'protocol': 1.0},
        {'operation': 2},
        {'operation': 3.0},
        {'sessionEpoch': 43},
        {'subscriptionId': 2},
        {'revision': 2},
        {'dimension': 'minecraft:the_nether'},
        {'action': 'hold'},
        {'action': 'finish'},
        {'outcome': 'other'},
        {'progress': -0.1},
        {'progress': 0.0},
        {'progress': 1.1},
        {'progress': '0.1'},
        {'worldMayHaveChanged': 1},
        {'worldMayHaveChanged': false},
        {'refreshRequired': true},
        {'extra': 0},
        {'outcome': 'changed', 'refreshRequired': true},
      ]) {
        final (c, s) = await connect();
        c.sendBlockDestroy(WorldDestroyAction.start);
        s.receive({...response(), ...corrupt});
        expect(c.phase, ConnectionPhase.offline, reason: corrupt.toString());
        expect(c.destroyPending, isFalse);
        expect(c.destroyActive, isFalse);
        expect(c.destroySnapshot, isNull);
        c.dispose();
      }
      for (final outcome in ['cancelled', 'denied']) {
        final (c, s) = await connect();
        c.sendBlockDestroy(WorldDestroyAction.start);
        s.receive(response());
        expect(c.sendBlockDestroy(WorldDestroyAction.cancel), isTrue);
        s.receive({
          ...response(seq: 4, action: 'cancel', outcome: outcome, progress: 0),
          'worldMayHaveChanged': false,
        });
        expect(c.phase, ConnectionPhase.offline);
        expect(c.destroySnapshot, isNull);
        expect(c.destroyActive, isFalse);
        c.dispose();
      }
      final (c, s) = await connect();
      s.receive(response());
      expect(c.phase, ConnectionPhase.offline);
      c.dispose();
    },
  );

  testWidgets('capability and deadline failures do not retain operations', (
    tester,
  ) async {
    for (final protocol in [null, 0, 1.0, 2, '1']) {
      final (c, _) = await connect(protocol: protocol);
      expect(c.canDestroyBlock, isFalse);
      expect(c.sendBlockDestroy(WorldDestroyAction.start), isFalse);
      c.dispose();
    }
    final (c, s) = await connect();
    c.sendBlockDestroy(WorldDestroyAction.start);
    await tester.pump(const Duration(seconds: 5));
    expect(c.phase, ConnectionPhase.offline);
    expect(s.closed, isTrue);
    expect(c.destroyActive, isFalse);
    expect(c.destroySnapshot, isNull);
    c.dispose();
  });

  testWidgets('preparation expires and does not manufacture holds', (
    tester,
  ) async {
    final (c, s) = await connect();
    expect(c.prepareBlockDestroy(), isTrue);
    expect(c.prepareBlockUse(), isFalse);
    expect(c.requestHotbar(), isFalse);
    expect(c.canSendWorldSectionNow, isFalse);
    await tester.pump(const Duration(milliseconds: 751));
    expect(c.destroyPreparing, isFalse);
    expect(c.canSendWorldSectionNow, isTrue);
    c.sendBlockDestroy(WorldDestroyAction.start);
    s.receive(response());
    final sent = s.sent.length;
    await tester.pump(const Duration(seconds: 1));
    expect(
      s.sent.length,
      sent,
      reason: 'Connection must never renew held input by itself',
    );
    expect(
      c.sendMovement(strafe: 0, forward: 1, yaw: 0, pitch: 0, jump: false),
      isTrue,
    );
    c.dispose();
  });

  testWidgets(
    'active resync clears old identity and later old reply is rejected',
    (tester) async {
      final (c, s) = await connect();
      c.sendBlockDestroy(WorldDestroyAction.start);
      s.receive(response());
      c.resyncWorld();
      expect(c.destroyActive, isFalse);
      expect(c.destroySnapshot, isNull);
      s.receive({...worldBootstrap, 'seq': 4, 'revision': 2});
      await tester.pump(const Duration(milliseconds: 100));
      c.sendBlockDestroy(WorldDestroyAction.start);
      s.receive(response(seq: 5, operation: 5));
      expect(c.phase, ConnectionPhase.offline);
      c.dispose();
    },
  );

  testWidgets('reply byte bound is checked before retaining progress', (
    tester,
  ) async {
    final (c, s) = await connect();
    c.sendBlockDestroy(WorldDestroyAction.start);
    s.input.add(' ' * 1024 + jsonEncode(response()));
    await tester.pump();
    expect(c.phase, ConnectionPhase.offline);
    c.dispose();
  });

  test('decoder rejects nonfinite progress and owns scalar confirmation', () {
    expect(
      () => WorldDestroySnapshot.parse({...response(), 'progress': double.nan}),
      throwsFormatException,
    );
    expect(
      () => WorldDestroySnapshot.parse({
        ...response(),
        'progress': double.infinity,
      }),
      throwsFormatException,
    );
    final raw = response();
    final snapshot = WorldDestroySnapshot.parse(raw);
    raw['progress'] = 0.9;
    expect(snapshot.progress, 0.1);
  });
}
