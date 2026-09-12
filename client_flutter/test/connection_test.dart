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
  'worldProtocol': 1,
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
  'sessionEpoch': 42,
  'dimension': 'minecraft:overworld',
  'x': -5.5,
  'y': 142.0,
  'z': -5.5,
  'yaw': 90.0,
  'pitch': 0.0,
  'gameTime': 1200,
  'defaultClockTime': 1200,
  'play': false,
};

void main() {
  testWidgets('login clears password, accepts world bootstrap and logs out', (
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
    expect(connection.status, '玩家已接入 Minecraft，等待世界核心同步…');
    socket.receive(worldBootstrap);
    socket.receive({'type': 'pong', 'seq': 0});
    await tester.pump();
    expect(find.text('登入帳號：alice'), findsOneWidget);
    expect(find.text('玩家身分：obs_123456781234'), findsOneWidget);
    expect(
      find.text('角色 UUID：12345678-1234-1234-1234-123456789abc'),
      findsOneWidget,
    );
    expect(find.text('角色狀態：已接入 Minecraft 玩家清單'), findsOneWidget);
    expect(find.text('世界維度：minecraft:overworld'), findsOneWidget);
    expect(find.text('初始位置：-5.50, 142.00, -5.50'), findsOneWidget);
    expect(connection.sessionEpoch, 42);
    expect(connection.playerAttached, isTrue);
    expect(connection.world?.dimension, 'minecraft:overworld');
    expect(connection.world?.defaultClockTime, 1200);
    expect(connection.status, '玩家已接入 Minecraft，世界核心已同步');
    expect(find.text('已收到 1 次連線回應'), findsOneWidget);
    await tester.tap(find.widgetWithText(ElevatedButton, '登出'));
    await tester.pump();
    expect(socket.closed, isTrue);
    expect(socket.sent.last, '{"type":"logout"}');
    expect(connection.account, isEmpty);
    expect(connection.playerUuid, isEmpty);
    expect(connection.playerAttached, isFalse);
    expect(connection.world, isNull);
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
      await tester.pump(const Duration(seconds: 13));
      expect(connection.phase, ConnectionPhase.offline);
      expect(connection.status, '伺服器未回應，連線已結束');
      expect(socket.closed, isTrue);
      connection.dispose();
    },
  );

  testWidgets('advertised world bootstrap must arrive promptly', (
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
    await tester.pump(const Duration(seconds: 6));
    expect(connection.phase, ConnectionPhase.offline);
    expect(connection.status, '世界核心同步逾時，請重新連線');
    connection.dispose();
  });

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

  testWidgets('rejects world bootstrap from a stale session epoch', (
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
    socket.receive({...worldBootstrap, 'sessionEpoch': 41});
    expect(connection.phase, ConnectionPhase.offline);
    expect(connection.status, '伺服器回應不相容，請重新連線');
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
