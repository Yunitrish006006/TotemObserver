import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/connection.dart';
import 'package:totem_observer_client/world_target_outline.dart';
import 'connection_test.dart'
    show FakeTransport, hello, authenticated, worldBootstrap, worldState;

final fingerprint = 'a' * 64;
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
    'worldRegistryProtocol': 1,
    if (protocol != null) 'worldTargetOutlineProtocol': protocol,
  });
  if (c.phase == ConnectionPhase.offline) return (c, s);
  s.receive(authenticated);
  s.receive(worldBootstrap);
  s.receive({
    'type': 'world_registry',
    'protocol': 1,
    'seq': 1,
    'sessionEpoch': 42,
    'fingerprint': fingerprint,
    'offset': 0,
    'total': 2,
    'states': ['minecraft:air', 'minecraft:stone'],
  });
  s.receive({...worldState, 'seq': 2});
  s.receive({'type': 'pong', 'seq': 3});
  return (c, s);
}

Map<String, dynamic> response() => {
  'type': 'world_target_outline',
  'protocol': 1,
  'seq': 4,
  'sessionEpoch': 42,
  'subscriptionId': 1,
  'revision': 1,
  'dimension': 'minecraft:overworld',
  'registryFingerprint': fingerprint,
  'serverTick': 10,
  'x': 12.25,
  'y': 64.0,
  'z': -3.5,
  'yaw': 90.0,
  'pitch': -15.0,
  'eyeY': 65.62,
  'target': {
    'x': 12,
    'y': 65,
    'z': -4,
    'face': 'north',
    'rawId': 1,
    'boxes': [
      [0.25, 0.2, 0.5, 0.75, 0.8, 1.0],
    ],
  },
};

void main() {
  testWidgets(
    'bound immediate capture and immutable selection exclude conflicting operations',
    (tester) async {
      final (c, s) = await connect();
      expect(c.requestTargetOutline(), isTrue);
      expect(jsonDecode(s.sent.last), {
        'type': 'world_target_outline',
        'protocol': 1,
        'seq': 4,
        'sessionEpoch': 42,
        'subscriptionId': 1,
        'revision': 1,
        'dimension': 'minecraft:overworld',
        'registryFingerprint': fingerprint,
      });
      expect(c.requestTargetOutline(), isFalse);
      expect(c.sendBlockUse(), isFalse);
      expect(c.canSendWorldSectionNow, isFalse);
      expect(
        c.sendMovement(strafe: 0, forward: 1, yaw: 90, pitch: -15, jump: false),
        isFalse,
      );
      s.receive(response());
      expect(c.targetOutline!.target!.boxes.single.minX, 0.25);
      expect(
        () => c.targetOutline!.target!.boxes.clear(),
        throwsUnsupportedError,
      );
      expect(c.requestTargetOutline(), isFalse);
      expect(
        c.sendMovement(strafe: 0, forward: 1, yaw: 90, pitch: -15, jump: false),
        isTrue,
      );
      expect(c.targetOutline, isNull);
      c.dispose();
    },
  );
  testWidgets('no target, older camera and cancelled display stay empty', (
    tester,
  ) async {
    for (final variant in ['null', 'camera', 'clear']) {
      final (c, s) = await connect();
      c.requestTargetOutline();
      final reply = response();
      if (variant == 'null') reply['target'] = null;
      if (variant == 'camera') reply['yaw'] = 91;
      if (variant == 'clear') c.clearTargetOutline();
      s.receive(reply);
      expect(c.phase, ConnectionPhase.connected);
      expect(c.targetOutline, isNull);
      c.dispose();
    }
  });
  testWidgets(
    'hostile bindings, geometry and unsolicited responses close the connection',
    (tester) async {
      for (final corrupt in <Map<String, dynamic>>[
        {'protocol': 1.0},
        {'seq': 5},
        {'sessionEpoch': 43},
        {'revision': 2},
        {'subscriptionId': 2},
        {'registryFingerprint': 'b' * 64},
        {'dimension': 'minecraft:the_nether'},
        {'extra': 0},
        {'serverTick': -1},
        {'eyeY': 9000},
        {
          'target': {...response()['target'] as Map, 'rawId': 2},
        },
        {
          'target': {...response()['target'] as Map, 'x': 100},
        },
        {
          'target': {
            ...response()['target'] as Map,
            'boxes': List.filled(17, [0, 0, 0, 1, 1, 1]),
          },
        },
      ]) {
        final (c, s) = await connect();
        c.requestTargetOutline();
        s.receive({...response(), ...corrupt});
        expect(c.phase, ConnectionPhase.offline, reason: corrupt.toString());
        expect(c.targetOutline, isNull);
        c.dispose();
      }
      final (c, s) = await connect();
      s.receive(response());
      expect(c.phase, ConnectionPhase.offline);
      c.dispose();
    },
  );
  testWidgets(
    'expiry refresh timeout and optional capability have bounded lifecycles',
    (tester) async {
      final (c, s) = await connect();
      c.requestTargetOutline();
      s.receive(response());
      await tester.pump(const Duration(milliseconds: 500));
      expect(c.targetOutline, isNull);
      expect(c.requestTargetOutline(), isTrue);
      s.receive({...response(), 'seq': 5});
      expect(c.targetOutline, isNotNull);
      c.resyncWorld();
      expect(c.targetOutline, isNull);
      c.dispose();
      final (timed, _) = await connect();
      timed.requestTargetOutline();
      await tester.pump(const Duration(seconds: 5));
      expect(timed.phase, ConnectionPhase.offline);
      expect(timed.outlinePending, isFalse);
      timed.dispose();
      for (final protocol in [null, 0, 1.0, 2, '1']) {
        final (legacy, _) = await connect(protocol: protocol);
        expect(legacy.canRequestTargetOutline, isFalse);
        expect(legacy.requestTargetOutline(), isFalse);
        legacy.dispose();
      }
    },
  );
  test('shape decoder rejects invalid geometry and makes immutable copies', () {
    for (final box in [
      [],
      [0, 0, 0, 1, 1],
      [0, 0, 0, 0, 1, 1],
      [-2, 0, 0, 1, 1, 1],
      [0, 0, 0, 3, 1, 1],
      [0, 0, 0, double.infinity, 1, 1],
      [0, 0, 0, '1', 1, 1],
    ]) {
      expect(() => WorldOutlineBox.parse(box), throwsFormatException);
    }
    final raw = response();
    final value = WorldTargetOutlineSnapshot.parse(raw, registryTotal: 2);
    (raw['target']['boxes'] as List).clear();
    expect(value.target!.boxes, hasLength(1));
  });
}
