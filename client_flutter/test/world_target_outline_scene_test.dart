import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/world_debug_scene.dart';
import 'package:totem_observer_client/world_movement_controls.dart';
import 'package:totem_observer_client/world_target_outline.dart';
import 'package:totem_observer_client/world_visible_view.dart';
import 'world_target_outline_client_test.dart' show connect, response;
import 'world_movement_controls_test.dart' show TestCapture;

void main() {
  test('partial selection edges are bounded, clipped and camera-bound', () {
    final reply = response()
      ..addAll({'x': 12.5, 'y': 64.0, 'z': -6.0, 'yaw': 0.0, 'pitch': 0.0});
    final outline = WorldTargetOutlineSnapshot.parse(reply, registryTotal: 2);
    const camera = WorldDebugCamera(x: 12.5, y: 64, z: -6, yaw: 0, pitch: 0);
    final edges = WorldDebugScene.projectOutline(
      outline,
      camera,
      const Size(640, 360),
    );
    expect(edges, hasLength(12));
    for (final (a, b) in edges) {
      for (final p in [a, b]) {
        expect(p.dx, inInclusiveRange(0, 640));
        expect(p.dy, inInclusiveRange(0, 360));
      }
    }
    expect(() => edges.clear(), throwsUnsupportedError);
    expect(
      WorldDebugScene.projectOutline(
        outline,
        const WorldDebugCamera(x: 12.5, y: 64, z: -6, yaw: 1, pitch: 0),
        const Size(640, 360),
      ),
      isEmpty,
    );
    expect(WorldDebugScene.projectOutline(outline, camera, Size.zero), isEmpty);
    reply['target'] = <String, dynamic>{
      ...reply['target'] as Map<String, dynamic>,
      'z': -10,
    };
    expect(
      WorldDebugScene.projectOutline(
        WorldTargetOutlineSnapshot.parse(reply, registryTotal: 2),
        camera,
        const Size(640, 360),
      ),
      isEmpty,
    );
    reply['target'] = <String, dynamic>{
      ...reply['target'] as Map<String, dynamic>,
      'z': -6,
    };
    final clipped = WorldDebugScene.projectOutline(
      WorldTargetOutlineSnapshot.parse(reply, registryTotal: 2),
      camera,
      const Size(640, 360),
    );
    expect(clipped.length, lessThanOrEqualTo(12));
    for (final (a, b) in clipped) {
      expect(a.dx.isFinite && b.dy.isFinite, isTrue);
    }
  });

  testWidgets(
    'selection semantics require projected edges at the actual viewport',
    (tester) async {
      final semantics = tester.ensureSemantics();
      try {
        for (final variant in ['visible', 'eye', 'behind']) {
          final (c, s) = await connect();
          c.worldX = 12.5;
          c.worldZ = -6;
          c.worldYaw = 0;
          c.worldPitch = 0;
          final reply = response()
            ..addAll({'x': 12.5, 'z': -6.0, 'yaw': 0.0, 'pitch': 0.0});
          if (variant == 'eye') reply['eyeY'] = 66.0;
          if (variant == 'behind')
            reply['target'] = <String, dynamic>{
              ...reply['target'] as Map<String, dynamic>,
              'z': -8,
            };
          await tester.pumpWidget(
            MaterialApp(
              home: SizedBox(
                width: 640,
                height: 360,
                child: ListenableBuilder(
                  listenable: c,
                  builder: (context, _) => WorldDebugSceneView(
                    connection: c,
                    plan: VisibleWorldPlan(
                      subscriptionId: 1,
                      revision: 1,
                      anchorSectionY: 4,
                      targets: const [],
                    ),
                  ),
                ),
              ),
            ),
          );
          c.requestTargetOutline();
          s.receive(reply);
          await tester.pump();
          expect(
            find.bySemanticsLabel('3D 偵錯地形，伺服器選取輪廓'),
            variant == 'visible' ? findsOneWidget : findsNothing,
          );
          await tester.pumpWidget(const SizedBox());
          c.dispose();
        }
      } finally {
        semantics.dispose();
      }
    },
  );

  testWidgets(
    'idle polling yields to keys and focus loss retires display ownership',
    (tester) async {
      final (c, s) = await connect();
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
      await tester.tap(find.text('點擊操作世界'));
      capture.changed(true);
      await tester.pump();
      int consumed = s.sent.length, tick = 10;
      Future<void> step() async {
        await tester.pump(const Duration(milliseconds: 110));
        for (final text in s.sent.skip(consumed).toList()) {
          consumed++;
          final m = jsonDecode(text);
          if (m['type'] == 'world_movement') {
            s.receive({
              'type': 'world_movement',
              'protocol': 1,
              'seq': m['seq'],
              'sessionEpoch': 42,
              'subscriptionId': 1,
              'revision': 1,
              'serverTick': tick++,
              'applied': true,
              'onGround': true,
              'dimension': 'minecraft:overworld',
              'x': 12.25,
              'y': 64.0,
              'z': -3.5,
              'yaw': 90.0,
              'pitch': -15.0,
            });
          }
        }
      }

      await tester.sendKeyDownEvent(LogicalKeyboardKey.keyW);
      for (int i = 0; i < 6; i++) {
        await step();
      }
      expect(
        s.sent.where((v) => jsonDecode(v)['type'] == 'world_target_outline'),
        isEmpty,
      );
      await tester.sendKeyUpEvent(LogicalKeyboardKey.keyW);
      for (int i = 0; i < 5; i++) {
        await step();
      }
      final queries = s.sent
          .map(jsonDecode)
          .where((v) => v['type'] == 'world_target_outline')
          .toList();
      expect(queries, hasLength(1));
      expect(c.outlinePending, isTrue);
      capture.changed(false);
      s.receive({
        ...response(),
        'seq': queries.single['seq'],
        'serverTick': tick,
      });
      expect(c.targetOutline, isNull);
      expect(c.outlinePending, isFalse);
      await tester.pumpWidget(const SizedBox());
      c.dispose();
    },
  );
}
