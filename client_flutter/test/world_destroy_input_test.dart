import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/connection.dart';
import 'connection_test.dart' show FakeTransport, worldBootstrap;
import 'world_destroy_client_test.dart' as mining;
import 'world_hotbar_input_test.dart' show mount;
import 'world_movement_client_test.dart' as movement;
import 'world_movement_controls_test.dart' show TestCapture;

Map<String, dynamic> last(FakeTransport s) => jsonDecode(s.sent.last);
List<dynamic> digs(FakeTransport s) => s.sent
    .map(jsonDecode)
    .where((m) => m['type'] == 'world_block_destroy')
    .toList();

Future<(ObserverConnection, FakeTransport, TestCapture, int)> start(
  WidgetTester tester,
) async {
  final (c, s) = await mining.connect();
  final capture = await mount(tester, c);
  await tester.tap(find.text('點擊操作世界'));
  capture.changed(true);
  await tester.pump();
  capture.onDestroy?.call(true);
  await tester.pump(const Duration(milliseconds: 110));
  expect(last(s)['type'], 'world_movement');
  expect(digs(s), isEmpty);
  s.receive(movement.response(last(s)['seq']));
  await tester.pump(const Duration(milliseconds: 110));
  final request = last(s);
  expect(request['action'], 'start');
  return (c, s, capture, request['seq'] as int);
}

void main() {
  testWidgets(
    'rapid release and press after refresh retains new hold ownership',
    (tester) async {
      final (c, s, capture, operation) = await start(tester);
      s.receive(
        mining.response(
          seq: operation,
          operation: operation,
          outcome: 'changed',
          progress: 1,
        ),
      );
      s.receive({...worldBootstrap, 'seq': last(s)['seq'], 'revision': 2});
      // Deliberately no timer sample between the terminal and new physical press.
      capture.onDestroy?.call(false);
      capture.onDestroy?.call(true);
      await tester.pump(const Duration(milliseconds: 110));
      s.receive({
        ...movement.response(last(s)['seq'], tick: 11),
        'revision': 2,
      });
      await tester.pump(const Duration(milliseconds: 110));
      final next = last(s)['seq'] as int;
      expect(last(s)['action'], 'start');
      s.receive({
        ...mining.response(seq: next, operation: next),
        'revision': 2,
      });
      await tester.pump(const Duration(milliseconds: 110));
      expect(last(s)['type'], 'world_movement');
      s.receive({
        ...movement.response(last(s)['seq'], tick: 12),
        'revision': 2,
      });
      await tester.pump(const Duration(milliseconds: 110));
      expect(last(s)['action'], 'hold');
      expect(last(s)['operation'], next);
      await tester.pumpWidget(const SizedBox());
      c.dispose();
    },
  );
  testWidgets(
    'held mining alternates movement and confirmation without local progress',
    (tester) async {
      final (c, s, capture, operation) = await start(tester);
      expect(c.destroySnapshot, isNull);
      s.receive(mining.response(seq: operation, operation: operation));
      await tester.pump();
      expect(find.text('挖掘 10%'), findsOneWidget);
      await tester.sendKeyDownEvent(LogicalKeyboardKey.keyW);
      await tester.pump(const Duration(milliseconds: 110));
      expect(last(s)['type'], 'world_movement');
      expect(last(s)['forward'], 1);
      s.receive(movement.response(last(s)['seq'], tick: 11));
      await tester.pump(const Duration(milliseconds: 110));
      expect(last(s)['action'], 'hold');
      expect(last(s)['operation'], operation);
      expect(c.destroySnapshot!.progress, .1);
      capture.onDestroy?.call(false); // Pending HOLD must drain before CANCEL.
      final holdSeq = last(s)['seq'] as int;
      s.receive(
        mining.response(
          seq: holdSeq,
          operation: operation,
          action: 'hold',
          progress: .4,
        ),
      );
      await tester.pump(const Duration(milliseconds: 110));
      expect(last(s)['action'], 'cancel');
      s.receive(
        mining.response(
          seq: last(s)['seq'],
          operation: operation,
          action: 'cancel',
          outcome: 'cancelled',
          progress: 0,
        ),
      );
      expect(last(s)['type'], 'world_bootstrap');
      expect(c.destroySnapshot, isNull);
      await tester.sendKeyUpEvent(LogicalKeyboardKey.keyW);
      await tester.pumpWidget(const SizedBox());
      c.dispose();
    },
  );

  testWidgets('capture loss drains pending START and never sends HOLD', (
    tester,
  ) async {
    final (c, s, capture, operation) = await start(tester);
    capture.changed(false);
    s.receive(mining.response(seq: operation, operation: operation));
    await tester.pump(const Duration(milliseconds: 110));
    expect(last(s)['action'], 'cancel');
    expect(digs(s).map((m) => m['action']), ['start', 'cancel']);
    await tester.pumpWidget(const SizedBox());
    c.dispose();
  });

  testWidgets(
    'capture and fresh look gate START; release discards preparation',
    (tester) async {
      for (final expiredLook in [false, true]) {
        final (c, s) = await mining.connect();
        final capture = await mount(tester, c);
        capture.onDestroy?.call(true);
        expect(c.destroyPreparing, isFalse);
        capture.onDestroy?.call(false);
        await tester.tap(find.text('點擊操作世界'));
        capture.changed(true);
        await tester.pump();
        capture.onDestroy?.call(true);
        capture.onUse?.call();
        expect(c.blockUsePreparing, isFalse);
        await tester.pump(const Duration(milliseconds: 110));
        final move = last(s);
        if (!expiredLook) capture.onDestroy?.call(false);
        s.receive({...movement.response(move['seq']), 'applied': !expiredLook});
        await tester.pump(const Duration(milliseconds: 110));
        expect(digs(s), isEmpty);
        expect(c.destroyPreparing, isFalse);
        await tester.pumpWidget(const SizedBox());
        c.dispose();
      }
    },
  );

  testWidgets('terminal refresh cannot automatically start a second block', (
    tester,
  ) async {
    final (c, s, capture, operation) = await start(tester);
    s.receive(
      mining.response(
        seq: operation,
        operation: operation,
        outcome: 'changed',
        progress: 1,
      ),
    );
    final bootstrap = last(s);
    expect(bootstrap['type'], 'world_bootstrap');
    s.receive({...worldBootstrap, 'seq': bootstrap['seq'], 'revision': 2});
    capture.onDestroy?.call(true); // Duplicate down is not a new press.
    await tester.pump(const Duration(milliseconds: 110));
    expect(digs(s), hasLength(1));
    expect(c.destroyPreparing, isFalse);
    capture.onDestroy?.call(false);
    capture.onDestroy?.call(true);
    expect(c.destroyPreparing, isTrue);
    await tester.pumpWidget(const SizedBox());
    c.dispose();
  });

  testWidgets(
    'replacement input owner cancels an inherited pending operation',
    (tester) async {
      final (c, s, _, operation) = await start(tester);
      await tester.pumpWidget(const SizedBox());
      await mount(tester, c);
      s.receive(mining.response(seq: operation, operation: operation));
      await tester.pump(const Duration(milliseconds: 110));
      expect(last(s)['action'], 'cancel');
      await tester.pumpWidget(const SizedBox());
      c.dispose();
    },
  );
}
