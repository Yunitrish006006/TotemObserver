import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/world_movement_controls.dart';
import 'package:totem_observer_client/world_pointer_capture.dart';
import 'world_movement_client_test.dart' show connect, response;
import 'connection_test.dart'
    show FakeTransport, hello, authenticated, worldBootstrap, worldState;
import 'package:totem_observer_client/connection.dart';
import 'package:totem_observer_client/world_section_scheduler.dart';

class TestCapture implements WorldPointerCapture {
  TestCapture(this.changed, this.look);
  final void Function(bool) changed;
  final void Function(double, double) look;
  bool requested = false, disposed = false;
  @override
  void Function()? onUse;
  @override
  void Function(bool)? onDestroy;
  @override
  bool get supported => true;
  @override
  void request() {
    requested = true;
  }

  @override
  void release() {
    requested = false;
    changed(false);
  }

  @override
  void dispose() {
    disposed = true;
  }
}

void main() {
  testWidgets(
    'background section and registry progress leave movement capacity',
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
      Map<String, dynamic> page(int seq, int offset) => {
        'type': 'world_registry',
        'protocol': 1,
        'seq': seq,
        'sessionEpoch': 42,
        'fingerprint': hash,
        'offset': offset,
        'total': 1000,
        'states': List.generate(8, (i) => 'minecraft:test_${offset + i}'),
      };
      socket.receive(page(1, 0));
      socket.receive({...worldState, 'seq': 2});
      socket.receive({'type': 'pong', 'seq': 3});
      final scheduler = WorldSectionScheduler(connection);
      scheduler.enqueueHorizontalWindow(4);
      int processed = 5, moves = 0, sections = 0, pages = 0, tick = 0;
      // The first five frames are login, bootstrap, registry, state and ping.
      for (int i = 0; i < 30; i++) {
        await tester.pump(const Duration(milliseconds: 110));
        connection.requestBlockState(16 + i * 8);
        if (connection.sendMovement(
          strafe: 0,
          forward: 1,
          yaw: 0,
          pitch: 0,
          jump: false,
        ))
          moves++;
        while (processed < socket.sent.length) {
          final request =
              jsonDecode(socket.sent[processed++]) as Map<String, dynamic>;
          if (request['type'] == 'world_movement') {
            socket.receive(response(request['seq'] as int, tick: ++tick));
          } else if (request['type'] == 'world_registry') {
            pages++;
            socket.receive(
              page(request['seq'] as int, request['offset'] as int),
            );
          } else if (request['type'] == 'world_section') {
            sections++;
            socket.receive({
              'type': 'world_section_unavailable',
              'protocol': 1,
              'seq': request['seq'],
              'sessionEpoch': 42,
              'subscriptionId': 1,
              'revision': 1,
              'registryFingerprint': hash,
              'dimension': 'minecraft:overworld',
              'chunkX': request['chunkX'],
              'chunkZ': request['chunkZ'],
              'sectionY': request['sectionY'],
              'reason': 'not_loaded',
            });
          }
        }
      }
      expect(connection.canMove, isTrue);
      expect(moves, greaterThanOrEqualTo(25));
      expect(sections, inInclusiveRange(8, 12));
      expect(pages, inInclusiveRange(8, 12));
      expect(
        scheduler.isIdle,
        isFalse,
        reason: 'Movement worked before background window finished',
      );
      scheduler.dispose();
      connection.dispose();
    },
  );

  testWidgets(
    'capture gates input; correction owns camera and release clears keys',
    (tester) async {
      final (connection, socket) = await connect();
      late TestCapture capture;
      await tester.pumpWidget(
        MaterialApp(
          home: WorldMovementControls(
            connection: connection,
            child: const SizedBox(width: 320, height: 180),
            captureFactory: (changed, look) =>
                capture = TestCapture(changed, look),
          ),
        ),
      );
      final before = socket.sent.length;
      await tester.pump(const Duration(milliseconds: 220));
      expect(socket.sent.length, before);
      await tester.tap(find.text('點擊操作世界'));
      await tester.pump();
      expect(capture.requested, isTrue);
      await tester.pump(const Duration(milliseconds: 110));
      expect(
        socket.sent.length,
        before,
        reason: 'requesting lock is not acquiring lock',
      );
      capture.changed(true);
      await tester.pump();
      final originalX = connection.worldX, originalYaw = connection.worldYaw;
      await tester.sendKeyDownEvent(LogicalKeyboardKey.keyW);
      await tester.sendKeyDownEvent(LogicalKeyboardKey.keyA);
      await tester.sendKeyDownEvent(LogicalKeyboardKey.space);
      capture.look(40, 20);
      await tester.pump(const Duration(milliseconds: 110));
      final input = jsonDecode(socket.sent.last) as Map<String, dynamic>;
      expect(input['type'], 'world_movement');
      expect(input['strafe'], 1);
      expect(input['forward'], 1);
      expect(input['jump'], 1);
      expect(input['yaw'], (((originalYaw + 6 + 180) % 360) - 180) * 100);
      expect(connection.worldX, originalX);
      expect(connection.worldYaw, originalYaw);
      socket.receive({
        ...response(input['seq'] as int),
        'yaw': 45.0,
        'pitch': 10.0,
      });
      capture.look(20, -20);
      await tester.pump(const Duration(milliseconds: 110));
      final corrected = jsonDecode(socket.sent.last) as Map<String, dynamic>;
      expect(corrected['yaw'], 4800);
      expect(corrected['pitch'], 700);
      socket.receive(response(corrected['seq'] as int, tick: 12));
      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await tester.pump(const Duration(milliseconds: 110));
      final stopped = jsonDecode(socket.sent.last) as Map<String, dynamic>;
      expect(stopped['strafe'], 0);
      expect(stopped['forward'], 0);
      expect(stopped['jump'], 0);
      await tester.sendKeyUpEvent(LogicalKeyboardKey.keyW);
      await tester.sendKeyUpEvent(LogicalKeyboardKey.keyA);
      await tester.sendKeyUpEvent(LogicalKeyboardKey.space);
      await tester.pumpWidget(const SizedBox());
      expect(capture.disposed, isTrue);
      connection.dispose();
    },
  );

  testWidgets(
    'capture loss and disconnect discard held keys and mouse deltas',
    (tester) async {
      final (connection, socket) = await connect();
      late TestCapture capture;
      await tester.pumpWidget(
        MaterialApp(
          home: WorldMovementControls(
            connection: connection,
            child: const SizedBox(),
            captureFactory: (changed, look) =>
                capture = TestCapture(changed, look),
          ),
        ),
      );
      await tester.tap(find.text('點擊操作世界'));
      await tester.pump();
      capture.changed(true);
      await tester.pump();
      await tester.sendKeyDownEvent(LogicalKeyboardKey.keyW);
      capture.look(100, 100);
      capture.changed(false);
      await tester.pump(const Duration(milliseconds: 110));
      final idle = jsonDecode(socket.sent.last) as Map<String, dynamic>;
      expect(idle['forward'], 0);
      expect(idle['jump'], 0);
      expect(idle['yaw'], (connection.worldYaw * 100).round());
      connection.disconnect();
      final count = socket.sent.length;
      capture.changed(true);
      capture.look(100, 100);
      await tester.pump(const Duration(seconds: 1));
      expect(socket.sent.length, count);
      await tester.sendKeyUpEvent(LogicalKeyboardKey.keyW);
      await tester.pumpWidget(const SizedBox());
      connection.dispose();
    },
  );
}
