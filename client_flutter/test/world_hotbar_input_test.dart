import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/connection.dart';
import 'package:totem_observer_client/world_movement_controls.dart';
import 'world_movement_controls_test.dart' show TestCapture;
import 'world_hotbar_client_test.dart' as hotbar;
import 'world_movement_client_test.dart' as movement;

Future<TestCapture> mount(WidgetTester tester, ObserverConnection c) async {
  late TestCapture capture;
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: WorldMovementControls(
          connection: c,
          child: const SizedBox(height: 50),
          captureFactory: (changed, look) =>
              capture = TestCapture(changed, look),
        ),
      ),
    ),
  );
  return capture;
}

void main() {
  testWidgets('number keys require capture and show only confirmed slot', (
    tester,
  ) async {
    final (c, s) = await hotbar.connect();
    final capture = await mount(tester, c);
    await tester.sendKeyEvent(LogicalKeyboardKey.digit2);
    expect(
      s.sent.where((v) => jsonDecode(v)['type'] == 'world_hotbar'),
      isEmpty,
    );
    await tester.tap(find.text('點擊操作世界'));
    capture.changed(true);
    await tester.pump();
    await tester.sendKeyDownEvent(LogicalKeyboardKey.digit2);
    await tester.pump(const Duration(milliseconds: 110));
    final request = jsonDecode(s.sent.last) as Map<String, dynamic>;
    expect(request['type'], 'world_hotbar');
    expect(request['slot'], 1);
    expect(find.textContaining('快捷列 2/9'), findsNothing);
    await tester.sendKeyRepeatEvent(LogicalKeyboardKey.digit2);
    capture.onUse?.call();
    expect(c.blockUsePreparing, isFalse);
    s.receive(
      hotbar.response(
        seq: request['seq'] as int,
        outcome: 'selected',
        selected: 1,
      ),
    );
    await tester.pump();
    expect(find.text('快捷列 2/9 · 空手'), findsOneWidget);
    await tester.sendKeyUpEvent(LogicalKeyboardKey.digit2);
    await tester.pump(const Duration(milliseconds: 220));
    expect(
      s.sent.where((v) => jsonDecode(v)['type'] == 'world_hotbar'),
      hasLength(1),
    );
    await tester.pumpWidget(const SizedBox());
    c.dispose();
  });

  testWidgets(
    'pending movement drains before selection and capture loss cancels queue',
    (tester) async {
      for (final cancel in [false, true]) {
        final (c, s) = await hotbar.connect();
        final capture = await mount(tester, c);
        await tester.tap(find.text('點擊操作世界'));
        capture.changed(true);
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 110));
        final move = jsonDecode(s.sent.last) as Map<String, dynamic>;
        expect(move['type'], 'world_movement');
        await tester.sendKeyEvent(LogicalKeyboardKey.digit9);
        await tester.pump(const Duration(milliseconds: 110));
        expect(
          s.sent.where((v) => jsonDecode(v)['type'] == 'world_hotbar'),
          isEmpty,
        );
        if (cancel) capture.changed(false);
        s.receive(movement.response(move['seq'] as int));
        await tester.pump(const Duration(milliseconds: 110));
        final requests = s.sent
            .map(jsonDecode)
            .where((m) => m['type'] == 'world_hotbar')
            .toList();
        if (cancel) {
          expect(requests, isEmpty);
        } else {
          expect(requests, hasLength(1));
          expect(requests.single['slot'], 8);
        }
        await tester.pumpWidget(const SizedBox());
        c.dispose();
      }
    },
  );

  testWidgets('queued selection expires without later replay', (tester) async {
    final (c, s) = await hotbar.connect();
    final capture = await mount(tester, c);
    await tester.tap(find.text('點擊操作世界'));
    capture.changed(true);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 110));
    final move = jsonDecode(s.sent.last) as Map<String, dynamic>;
    await tester.sendKeyEvent(LogicalKeyboardKey.digit3);
    await tester.pump(const Duration(milliseconds: 800));
    s.receive(movement.response(move['seq'] as int));
    await tester.pump(const Duration(milliseconds: 110));
    expect(
      s.sent
          .map(jsonDecode)
          .where((m) => m['type'] == 'world_hotbar' && m.containsKey('slot')),
      isEmpty,
    );
    await tester.pumpWidget(const SizedBox());
    c.dispose();
  });
}
