import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/connection.dart';
import 'package:totem_observer_client/main.dart';

class FakeTransport implements BridgeTransport {
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

const hello = {
  'type': 'hello',
  'protocol': 1,
  'authentication': true,
  'registration': true,
  'playerIdentityProtocol': 1,
  'playerAdmission': true,
  'worldStateProtocol': 1,
  'worldBootstrapProtocol': 1,
  'play': false,
};
const authenticated = {
  'type': 'authenticated',
  'username': 'alice',
  'expiresInSeconds': 900,
  'playerUuid': '12345678-1234-1234-1234-123456789abc',
  'playerName': 'obs_123456781234',
  'sessionEpoch': 42,
  'playerAttached': true,
  'play': false,
};
const worldBootstrap = {
  'type': 'world_bootstrap',
  'protocol': 1,
  'seq': 0,
  'sessionEpoch': 42,
  'subscriptionId': 1,
  'revision': 1,
  'dimension': 'minecraft:overworld',
  'minY': -64,
  'height': 384,
  'centerChunkX': 0,
  'centerChunkZ': -1,
  'radius': 2,
};
const worldState = {
  'type': 'world_state',
  'protocol': 1,
  'seq': 1,
  'sessionEpoch': 42,
  'dimension': 'minecraft:overworld',
  'x': 12.25,
  'y': 64.0,
  'z': -3.5,
  'yaw': 90.0,
  'pitch': -15.0,
};

void main() {
  testWidgets('login reads world bootstrap, resyncs and logs out', (
    tester,
  ) async {
    final socket = FakeTransport();
    final connection = ObserverConnection(open: (_) => socket);
    await tester.pumpWidget(ObserverApp(connection: connection));
    final fields = find.byType(TextField);
    await tester.enterText(fields.at(1), 'alice');
    await tester.enterText(fields.at(2), 'local-test-password');
    await tester.tap(find.widgetWithText(ElevatedButton, '登入'));
    await tester.pump();
    expect(tester.widget<TextField>(fields.at(2)).controller!.text, isEmpty);
    expect(socket.sent, isEmpty);
    socket.receive(hello);
    expect(jsonDecode(socket.sent.single)['password'], 'local-test-password');
    socket.receive(authenticated);
    expect(jsonDecode(socket.sent[1]), {'type': 'world_bootstrap', 'seq': 0});
    expect(jsonDecode(socket.sent[2]), {'type': 'world_state', 'seq': 1});
    expect(jsonDecode(socket.sent[3]), {'type': 'ping', 'seq': 2});
    socket.receive(worldBootstrap);
    socket.receive(worldState);
    socket.receive({'type': 'pong', 'seq': 2});
    await tester.pump();
    expect(find.text('登入帳號：alice'), findsOneWidget);
    expect(find.text('玩家身分：obs_123456781234'), findsOneWidget);
    expect(
      find.text('角色 UUID：12345678-1234-1234-1234-123456789abc'),
      findsOneWidget,
    );
    expect(find.text('角色狀態：已接入 Minecraft 玩家清單'), findsOneWidget);
    expect(find.text('世界維度：minecraft:overworld'), findsOneWidget);
    expect(find.text('角色位置：12.25, 64.00, -3.50'), findsOneWidget);
    expect(find.text('角色朝向：yaw 90.0 / pitch -15.0'), findsOneWidget);
    expect(connection.sessionEpoch, 42);
    expect(connection.playerAttached, isTrue);
    expect(connection.hasWorldState, isTrue);
    expect(connection.hasWorldBootstrap, isTrue);
    expect(connection.bootstrapSubscriptionId, 1);
    expect(connection.bootstrapRevision, 1);
    expect(connection.bootstrapMinY, -64);
    expect(connection.bootstrapHeight, 384);
    expect(connection.bootstrapCenterChunkX, 0);
    expect(connection.bootstrapCenterChunkZ, -1);
    expect(connection.bootstrapRadius, 2);
    expect(find.text('已收到 1 次連線回應'), findsOneWidget);
    expect(find.text('已取得伺服器權威角色快照；區塊同步、畫面與遊戲操作仍未啟用。'), findsOneWidget);

    connection.resyncWorld();
    expect(jsonDecode(socket.sent.last), {'type': 'world_bootstrap', 'seq': 3});
    socket.receive({
      ...worldBootstrap,
      'seq': 3,
      'revision': 2,
      'centerChunkX': 1,
    });
    expect(connection.bootstrapSubscriptionId, 1);
    expect(connection.bootstrapRevision, 2);
    expect(connection.bootstrapCenterChunkX, 1);

    await tester.tap(find.widgetWithText(ElevatedButton, '登出'));
    await tester.pump();
    expect(socket.closed, isTrue);
    expect(socket.sent.last, '{"type":"logout"}');
    expect(connection.account, isEmpty);
    expect(connection.playerUuid, isEmpty);
    expect(connection.playerAttached, isFalse);
    expect(connection.hasWorldState, isFalse);
    expect(connection.hasWorldBootstrap, isFalse);
    connection.dispose();
  });

  testWidgets(
    'stalled session leaves connected state after response deadline',
    (tester) async {
      final socket = FakeTransport();
      final connection = ObserverConnection(open: (_) => socket);
      await connection.authenticate(
        'ws://127.0.0.1:25580/observer/bridge',
        'alice',
        'local-test-password',
      );
      socket.receive(hello);
      socket.receive(authenticated);
      socket.receive(worldBootstrap);
      socket.receive(worldState);
      await tester.pump(const Duration(seconds: 13));
      expect(connection.phase, ConnectionPhase.offline);
      expect(connection.status, '伺服器未回應，連線已結束');
      expect(socket.closed, isTrue);
      connection.dispose();
    },
  );

  testWidgets('old connection cannot authenticate a replacement', (
    tester,
  ) async {
    final first = FakeTransport(), second = FakeTransport();
    int attempts = 0;
    final connection = ObserverConnection(
      open: (_) => attempts++ == 0 ? first : second,
    );
    for (int i = 0; i < 2; i++) {
      await connection.authenticate(
        'ws://127.0.0.1:25580/observer/bridge',
        'alice',
        'local-test-password',
      );
    }
    first.receive(hello);
    first.receive(authenticated);
    expect(connection.phase, ConnectionPhase.connecting);
    expect(second.sent, isEmpty);
    second.receive(hello);
    second.receive(authenticated);
    expect(connection.phase, ConnectionPhase.connected);
    connection.dispose();
  });

  testWidgets('rejects malformed player identity contract', (tester) async {
    final socket = FakeTransport();
    final connection = ObserverConnection(open: (_) => socket);
    await connection.authenticate(
      'ws://127.0.0.1:25580/observer/bridge',
      'alice',
      'local-test-password',
    );
    socket.receive(hello);
    socket.receive({...authenticated, 'playerUuid': 'client-chosen'});
    expect(connection.phase, ConnectionPhase.offline);
    expect(connection.status, '伺服器回應不相容，請重新連線');
    connection.dispose();
  });

  testWidgets('rejects missing player admission after advertised capability', (
    tester,
  ) async {
    final socket = FakeTransport();
    final connection = ObserverConnection(open: (_) => socket);
    await connection.authenticate(
      'ws://127.0.0.1:25580/observer/bridge',
      'alice',
      'local-test-password',
    );
    socket.receive(hello);
    socket.receive({...authenticated, 'playerAttached': false});
    expect(connection.phase, ConnectionPhase.offline);
    expect(connection.status, '伺服器回應不相容，請重新連線');
    connection.dispose();
  });

  testWidgets('rejects world capability without identity contract', (
    tester,
  ) async {
    final socket = FakeTransport();
    final connection = ObserverConnection(open: (_) => socket);
    await connection.authenticate(
      'ws://127.0.0.1:25580/observer/bridge',
      'alice',
      'local-test-password',
    );
    socket.receive({...hello, 'playerIdentityProtocol': null});
    expect(connection.phase, ConnectionPhase.offline);
    expect(socket.sent, isEmpty);
    expect(connection.status, '伺服器回應不相容，請重新連線');
    connection.dispose();
  });

  testWidgets('rejects world snapshot for the wrong request or session', (
    tester,
  ) async {
    final socket = FakeTransport();
    final connection = ObserverConnection(open: (_) => socket);
    await connection.authenticate(
      'ws://127.0.0.1:25580/observer/bridge',
      'alice',
      'local-test-password',
    );
    socket.receive(hello);
    socket.receive(authenticated);
    socket.receive({...worldState, 'sessionEpoch': 41});
    expect(connection.phase, ConnectionPhase.offline);
    expect(connection.hasWorldState, isFalse);
    expect(connection.status, '伺服器回應不相容，請重新連線');
    connection.dispose();
  });

  testWidgets('rejects invalid initial world bootstrap revision', (
    tester,
  ) async {
    final socket = FakeTransport();
    final connection = ObserverConnection(open: (_) => socket);
    await connection.authenticate(
      'ws://127.0.0.1:25580/observer/bridge',
      'alice',
      'local-test-password',
    );
    socket.receive(hello);
    socket.receive(authenticated);
    socket.receive({...worldBootstrap, 'revision': 2});
    expect(connection.phase, ConnectionPhase.offline);
    expect(connection.hasWorldBootstrap, isFalse);
    expect(connection.status, '伺服器回應不相容，請重新連線');
    connection.dispose();
  });

  testWidgets(
    'rejects external URL and failed credentials without showing server payload',
    (tester) async {
      final socket = FakeTransport();
      final connection = ObserverConnection(open: (_) => socket);
      await connection.authenticate(
        'ws://example.com/observer/bridge',
        'alice',
        'local-test-password',
      );
      expect(connection.phase, ConnectionPhase.offline);
      expect(socket.sent, isEmpty);
      await connection.authenticate(
        'ws://127.0.0.1:25580/observer/bridge',
        'alice',
        'local-test-password',
      );
      socket.receive(hello);
      socket.receive({'type': 'auth_failed', 'detail': 'must-not-appear'});
      expect(connection.phase, ConnectionPhase.offline);
      expect(connection.status, isNot(contains('must-not-appear')));
      connection.dispose();
    },
  );

  testWidgets('login remains usable on narrow screen and enlarged text', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.textScaleFactorTestValue = 1.5;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await tester.pumpWidget(const ObserverApp());
    await tester.ensureVisible(find.text('建立帳號'));
    await tester.pump();
    expect(tester.takeException(), isNull);
    expect(find.text('建立帳號'), findsOneWidget);
  });
}
