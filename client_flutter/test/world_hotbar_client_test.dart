import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/connection.dart';
import 'package:totem_observer_client/world_hotbar.dart';
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
    if (protocol != null) 'worldHotbarProtocol': protocol,
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
  String outcome = 'snapshot',
  int selected = 0,
}) => {
  'type': 'world_hotbar',
  'protocol': 1,
  'seq': seq,
  'sessionEpoch': 42,
  'subscriptionId': 1,
  'revision': 1,
  'dimension': 'minecraft:overworld',
  'outcome': outcome,
  'selected': outcome == 'denied' ? null : selected,
  'slots': outcome == 'denied'
      ? null
      : List.generate(
          9,
          (i) => <String, dynamic>{
            'item': i == 0 ? 'minecraft:apple' : 'minecraft:air',
            'count': i == 0 ? 3 : 0,
          },
        ),
};

void main() {
  testWidgets('read and selection never optimistically alter inventory', (
    tester,
  ) async {
    final (c, s) = await connect();
    expect(c.requestHotbar(slot: -1), isFalse);
    expect(c.requestHotbar(slot: 9), isFalse);
    expect(c.requestHotbar(), isTrue);
    expect(jsonDecode(s.sent.last), {
      'type': 'world_hotbar',
      'protocol': 1,
      'seq': 3,
      'sessionEpoch': 42,
      'subscriptionId': 1,
      'revision': 1,
      'dimension': 'minecraft:overworld',
    });
    expect(c.hotbarSnapshot, isNull);
    expect(c.requestHotbar(), isFalse);
    expect(c.sendBlockUse(), isFalse);
    expect(c.canSendWorldSectionNow, isFalse);
    expect(
      c.sendMovement(strafe: 0, forward: 1, yaw: 0, pitch: 0, jump: false),
      isFalse,
    );
    c.resyncWorld();
    expect(jsonDecode(s.sent.last)['type'], 'world_hotbar');
    s.receive(response());
    expect(c.hotbarSnapshot!.selected, 0);
    expect(c.hotbarSnapshot!.slots![0].count, 3);
    expect(() => c.hotbarSnapshot!.slots!.clear(), throwsUnsupportedError);
    expect(c.requestHotbar(slot: 2), isFalse);
    await tester.pump(const Duration(milliseconds: 250));
    expect(c.requestHotbar(slot: 2), isTrue);
    expect(jsonDecode(s.sent.last)['slot'], 2);
    expect(c.hotbarSnapshot!.selected, 0);
    // A server hook may change selection; the resulting server state wins.
    s.receive(response(seq: 4, outcome: 'selected', selected: 3));
    expect(c.hotbarSnapshot!.selected, 3);
    c.resyncWorld();
    expect(c.hotbarSnapshot, isNull);
    c.dispose();
  });

  testWidgets('denied has no inventory and remains connected', (tester) async {
    final (c, s) = await connect();
    c.requestHotbar();
    s.receive(response(outcome: 'denied'));
    expect(c.phase, ConnectionPhase.connected);
    expect(c.hotbarSnapshot!.outcome, 'denied');
    expect(c.hotbarSnapshot!.slots, isNull);
    expect(c.hotbarSnapshot!.selected, isNull);
    c.dispose();
  });

  testWidgets(
    'malformed stale unsolicited and mismatched replies fail closed',
    (tester) async {
      for (final corrupt in <Map<String, dynamic>>[
        {'seq': 4},
        {'seq': 3.0},
        {'protocol': 1.0},
        {'sessionEpoch': 43},
        {'subscriptionId': 2},
        {'revision': 2},
        {'dimension': 'minecraft:the_nether'},
        {'outcome': 'selected'},
        {'outcome': 'other'},
        {'selected': 9},
        {'selected': 0.0},
        {'slots': []},
        {
          'slots': List.filled(10, {'item': 'minecraft:air', 'count': 0}),
        },
        {'outcome': 'denied'},
        {'extra': 0},
      ]) {
        final (c, s) = await connect();
        c.requestHotbar();
        s.receive({...response(), ...corrupt});
        expect(c.phase, ConnectionPhase.offline, reason: corrupt.toString());
        expect(c.hotbarSnapshot, isNull);
        expect(c.hotbarPending, isFalse);
        c.dispose();
      }
      final (c, s) = await connect();
      s.receive(response());
      expect(c.phase, ConnectionPhase.offline);
      c.dispose();
    },
  );

  testWidgets('capability timeout movement and use exclusions clean up', (
    tester,
  ) async {
    for (final protocol in [null, 0, 1.0, 2, '1']) {
      final (c, _) = await connect(protocol: protocol);
      expect(c.canRequestHotbar, isFalse);
      expect(c.requestHotbar(), isFalse);
      c.dispose();
    }
    final (c, s) = await connect();
    c.requestHotbar();
    await tester.pump(const Duration(seconds: 5));
    expect(c.phase, ConnectionPhase.offline);
    expect(s.closed, isTrue);
    expect(c.hotbarPending, isFalse);
    expect(c.hotbarSnapshot, isNull);
    c.dispose();
    final (moving, _) = await connect();
    moving.sendMovement(strafe: 0, forward: 1, yaw: 0, pitch: 0, jump: false);
    expect(moving.requestHotbar(), isFalse);
    moving.dispose();
    final (using, _) = await connect();
    using.prepareBlockUse();
    expect(using.requestHotbar(), isFalse);
    using.dispose();
  });

  testWidgets('later denial and block use discard previous inventory', (
    tester,
  ) async {
    final (c, s) = await connect();
    c.requestHotbar();
    s.receive(response());
    await tester.pump(const Duration(milliseconds: 250));
    c.requestHotbar();
    s.receive(response(seq: 4, outcome: 'denied'));
    expect(c.hotbarSnapshot!.slots, isNull);
    await tester.pump(const Duration(milliseconds: 250));
    c.requestHotbar();
    s.receive(response(seq: 5));
    expect(c.hotbarSnapshot!.slots, hasLength(9));
    expect(c.sendBlockUse(), isTrue);
    s.receive({
      'type': 'world_block_use',
      'protocol': 1,
      'seq': 6,
      'sessionEpoch': 42,
      'subscriptionId': 1,
      'revision': 1,
      'dimension': 'minecraft:overworld',
      'outcome': 'denied',
      'refreshRequired': false,
    });
    expect(c.hotbarSnapshot, isNull);
    c.dispose();
  });

  testWidgets('response byte budget is enforced before retaining inventory', (
    tester,
  ) async {
    final (c, s) = await connect();
    c.requestHotbar();
    // Legal JSON whitespace exceeds the wire ceiling without changing the schema.
    s.input.add(' ' * 4096 + jsonEncode(response()));
    await tester.pump();
    expect(c.phase, ConnectionPhase.offline);
    expect(c.hotbarSnapshot, isNull);
    c.dispose();
  });

  test('decoder bounds item data and owns immutable copies', () {
    for (final stack in [
      {'item': 'minecraft:apple', 'count': -1},
      {'item': 'minecraft:apple', 'count': 1000},
      {'item': 'minecraft:apple', 'count': 1.0},
      {'item': 'bad item', 'count': 1},
      {'item': 'minecraft:${'a' * 256}', 'count': 1},
      {'item': 'minecraft:apple', 'count': 1, 'nbt': {}},
    ]) {
      expect(() => WorldHotbarStack.parse(stack), throwsFormatException);
    }
    final raw = response();
    final value = WorldHotbarSnapshot.parse(raw);
    raw['slots'][0]['count'] = 99;
    (raw['slots'] as List).clear();
    expect(value.slots, hasLength(9));
    expect(value.slots![0].count, 3);
  });
}
