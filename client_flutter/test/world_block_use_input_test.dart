import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/world_movement_controls.dart';
import 'world_movement_controls_test.dart' show TestCapture;
import 'world_block_use_client_test.dart' show connect;
import 'world_movement_client_test.dart' show response;

void main() {
  testWidgets('rejected look correction cancels use', (tester) async {
    final (connection, socket) = await connect();
    late TestCapture capture;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: WorldMovementControls(
            connection: connection,
            child: const SizedBox(height: 50),
            captureFactory: (changed, look) =>
                capture = TestCapture(changed, look),
          ),
        ),
      ),
    );
    await tester.tap(find.text('點擊操作世界'));
    capture.changed(true);
    await tester.pump();
    await tester.sendKeyDownEvent(LogicalKeyboardKey.keyE);
    await tester.pump(const Duration(milliseconds: 110));
    final sent = jsonDecode(socket.sent.last) as Map<String, dynamic>;
    expect(sent['type'], 'world_movement');
    socket.receive({...response(sent['seq'] as int), 'applied': false});
    await tester.pump(const Duration(milliseconds: 110));
    expect(connection.blockUsePreparing, isFalse);
    expect(
      socket.sent.where((v) => jsonDecode(v)['type'] == 'world_block_use'),
      isEmpty,
    );
    await tester.sendKeyUpEvent(LogicalKeyboardKey.keyE);
    await tester.pumpWidget(const SizedBox());
    connection.dispose();
  });

  testWidgets('E use flushes idle look before a single authoritative intent', (
    tester,
  ) async {
    final (connection, socket) = await connect();
    late TestCapture capture;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: WorldMovementControls(
            connection: connection,
            child: const SizedBox(height: 50),
            captureFactory: (changed, look) =>
                capture = TestCapture(changed, look),
          ),
        ),
      ),
    );
    await tester.sendKeyEvent(LogicalKeyboardKey.keyE);
    expect(socket.sent.length, 4);
    await tester.tap(find.text('點擊操作世界'));
    capture.changed(true);
    await tester.pump();
    capture.look(20, 10);
    await tester.sendKeyDownEvent(LogicalKeyboardKey.keyW);
    await tester.sendKeyDownEvent(LogicalKeyboardKey.keyE);
    expect(connection.blockUsePreparing, isTrue);
    expect(connection.canSendWorldSectionNow, isFalse);
    await tester.pump(const Duration(milliseconds: 110));
    var sent = jsonDecode(socket.sent.last) as Map<String, dynamic>;
    expect(sent['type'], 'world_movement');
    expect(sent['forward'], 0);
    expect(sent['jump'], 0);
    expect(sent['yaw'], 9300);
    expect(sent['pitch'], -1350);
    socket.receive(response(sent['seq'] as int));
    await tester.pump(const Duration(milliseconds: 110));
    sent = jsonDecode(socket.sent.last) as Map<String, dynamic>;
    expect(sent['type'], 'world_block_use');
    expect(connection.blockUsePreparing, isFalse);
    expect(connection.blockUsePending, isTrue);
    await tester.pump(const Duration(milliseconds: 220));
    expect(
      socket.sent
          .where((v) => jsonDecode(v)['type'] == 'world_block_use')
          .length,
      1,
    );
    await tester.sendKeyUpEvent(LogicalKeyboardKey.keyE);
    await tester.sendKeyUpEvent(LogicalKeyboardKey.keyW);
    await tester.pumpWidget(const SizedBox());
    connection.dispose();
  });

  testWidgets('capture loss and bounded preparation cancel unsent use', (
    tester,
  ) async {
    for (final expire in [false, true]) {
      final (connection, socket) = await connect();
      late TestCapture capture;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: WorldMovementControls(
              connection: connection,
              child: const SizedBox(height: 50),
              captureFactory: (changed, look) =>
                  capture = TestCapture(changed, look),
            ),
          ),
        ),
      );
      await tester.tap(find.text('點擊操作世界'));
      capture.changed(true);
      await tester.pump();
      await tester.sendKeyDownEvent(LogicalKeyboardKey.keyE);
      expect(connection.blockUsePreparing, isTrue);
      if (expire) {
        await tester.pump(const Duration(milliseconds: 800));
      } else {
        capture.changed(false);
        await tester.pump();
      }
      expect(connection.blockUsePreparing, isFalse);
      expect(
        socket.sent.where((v) => jsonDecode(v)['type'] == 'world_block_use'),
        isEmpty,
      );
      await tester.sendKeyUpEvent(LogicalKeyboardKey.keyE);
      await tester.pumpWidget(const SizedBox());
      connection.dispose();
    }
  });
}
