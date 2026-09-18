import 'dart:io';
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/world_block_state.dart';
import 'package:totem_observer_client/world_debug_scene.dart';
import 'package:totem_observer_client/world_debug_voxel_mesh.dart';
import 'package:totem_observer_client/world_visible_view.dart';
import 'package:totem_observer_client/world_section_scheduler.dart';

import 'world_debug_slice_test.dart' show DebugSliceTestConnection;

class GenerationConnection extends DebugSliceTestConnection {
  int generation = 0;
  @override
  int get worldGeometryRevision => generation;
}

WorldDebugVoxelFace face(
  int z, {
  int x = 0,
  int y = 1,
  WorldVoxelFaceDirection direction = WorldVoxelFaceDirection.north,
}) => WorldDebugVoxelFace(
  chunkX: 0,
  chunkZ: 0,
  sectionY: 0,
  localX: x,
  localY: y,
  localZ: z,
  direction: direction,
  stateId: 7,
  blockId: 'minecraft:stone',
  renderHint: WorldBlockRenderHint.opaqueLike,
);

const camera = WorldDebugCamera(x: 0.5, y: 0, z: 0, yaw: 0, pitch: 0);
const size = Size(640, 360);

void main() {
  test('perspective makes distant faces smaller and orders far to near', () {
    final result = WorldDebugScene.project([face(2), face(4)], camera, size);
    expect(result.length, 2);
    expect(result.first.depth, greaterThan(result.last.depth));
    double width(WorldDebugProjectedFace f) =>
        f.points.map((p) => p.dx).reduce((a, b) => a > b ? a : b) -
        f.points.map((p) => p.dx).reduce((a, b) => a < b ? a : b);
    expect(width(result.first), closeTo(width(result.last) / 2, 0.001));
  });

  test('rear, far and back-facing surfaces disappear', () {
    expect(
      WorldDebugScene.project(
        [face(-2), face(65), face(2, direction: WorldVoxelFaceDirection.south)],
        camera,
        size,
      ),
      isEmpty,
    );
  });

  test('near and side clipping produce finite viewport coordinates', () {
    final result = WorldDebugScene.project(
      [face(0, x: -1, direction: WorldVoxelFaceDirection.east)],
      camera,
      size,
    );
    expect(result, isNotEmpty);
    for (final p in result.single.points) {
      expect(p.dx.isFinite && p.dy.isFinite, isTrue);
      expect(p.dx, inInclusiveRange(-0.0001, 640.0001));
      expect(p.dy, inInclusiveRange(-0.0001, 360.0001));
    }
  });

  test('yaw 90 looks west and pitch 90 looks down', () {
    expect(
      WorldDebugScene.project(
        [face(0, x: -3, direction: WorldVoxelFaceDirection.east)],
        const WorldDebugCamera(x: 0.5, y: 0, z: 0.5, yaw: 90, pitch: 0),
        size,
      ),
      isNotEmpty,
    );
    expect(
      WorldDebugScene.project(
        [face(0, y: -1, direction: WorldVoxelFaceDirection.up)],
        const WorldDebugCamera(x: 0.5, y: 0, z: 0.5, yaw: 0, pitch: 90),
        size,
      ),
      isNotEmpty,
    );
  });

  test('invalid camera and empty viewport fail closed; colors are stable', () {
    expect(
      WorldDebugScene.project(
        [face(2)],
        const WorldDebugCamera(x: double.nan, y: 0, z: 0, yaw: 0, pitch: 0),
        size,
      ),
      isEmpty,
    );
    expect(WorldDebugScene.project([face(2)], camera, Size.zero), isEmpty);
    expect(
      WorldDebugScene.colorFor('minecraft:stone', WorldVoxelFaceDirection.up),
      WorldDebugScene.colorFor('minecraft:stone', WorldVoxelFaceDirection.up),
    );
  });

  test(
    'scene reads current cache only and drops stale plans and evictions',
    () {
      final connection = DebugSliceTestConnection();
      final plan = VisibleWorldPlan(
        subscriptionId: 1,
        revision: 1,
        anchorSectionY: 4,
        targets: const [
          WorldSectionCoordinate(chunkX: 10, chunkZ: -3, sectionY: 4),
        ],
      );
      connection.addSection(
        10,
        -3,
        4,
        stateId: 0,
        specialStateId: 7,
        specialX: 3,
        specialZ: 5,
      );
      expect(WorldDebugScene.collect(connection, plan).length, 6);
      connection.completed.clear();
      expect(WorldDebugScene.collect(connection, plan), isEmpty);
      connection.addSection(
        10,
        -3,
        4,
        stateId: 0,
        specialStateId: 7,
        specialX: 3,
        specialZ: 5,
      );
      connection.bootstrapRevision++;
      expect(WorldDebugScene.collect(connection, plan), isEmpty);
      connection.bootstrapRevision--;
      connection.names[7] = 'minecraft:water[level=0]';
      expect(WorldDebugScene.collect(connection, plan), isEmpty);
      connection.names[7] = 'minecraft:stone';
      expect(WorldDebugScene.collect(connection, plan).length, 6);
      connection.disconnect();
      expect(WorldDebugScene.collect(connection, plan), isEmpty);
      connection.dispose();
    },
  );

  testWidgets('mesh reuse respects generation and current world validity', (
    tester,
  ) async {
    final connection = GenerationConnection();
    connection.addSection(
      10,
      -3,
      4,
      stateId: 0,
      specialStateId: 7,
      specialX: 3,
      specialZ: 5,
    );
    final plan = WorldVisibleViewPolicy.create(connection)!;
    Future<WorldDebugScenePainter> render() async {
      await tester.pumpWidget(
        MaterialApp(
          home: WorldDebugSceneView(connection: connection, plan: plan),
        ),
      );
      return tester
              .widget<CustomPaint>(
                find.byKey(const ValueKey('world-debug-scene')),
              )
              .painter!
          as WorldDebugScenePainter;
    }

    final initial = await render();
    expect(initial.faces, hasLength(6));
    connection.worldYaw = 30;
    final moved = await render();
    expect(identical(initial.faces, moved.faces), isTrue);
    expect(moved.camera.yaw, 30);
    connection.completed.clear();
    connection.generation++;
    expect((await render()).faces, isEmpty);
    connection.addSection(
      10,
      -3,
      4,
      stateId: 0,
      specialStateId: 7,
      specialX: 3,
      specialZ: 5,
    );
    connection.generation++;
    expect((await render()).faces, hasLength(6));
    connection.worldDimension = 'minecraft:the_nether';
    expect((await render()).faces, isEmpty);
    connection.worldDimension = connection.bootstrapDimension;
    expect((await render()).faces, hasLength(6));
    connection.worldY += 16;
    expect((await render()).faces, isEmpty);
    connection.dispose();
  });

  testWidgets('partial-cache scene paints and resizes without an exception', (
    tester,
  ) async {
    final connection = DebugSliceTestConnection();
    final plan = VisibleWorldPlan(
      subscriptionId: 1,
      revision: 1,
      anchorSectionY: 4,
      targets: const [],
    );
    for (final width in [320.0, 640.0]) {
      await tester.pumpWidget(
        MaterialApp(
          home: Center(
            child: SizedBox(
              width: width,
              child: WorldDebugSceneView(connection: connection, plan: plan),
            ),
          ),
        ),
      );
      expect(find.byKey(const ValueKey('world-debug-scene')), findsOneWidget);
      expect(tester.takeException(), isNull);
    }
    connection.dispose();
  });
  testWidgets(
    'renders reproducible terrain evidence through production painter',
    (tester) async {
      final terrain = <WorldDebugVoxelFace>[];
      for (int z = 1; z < 24; z++) {
        for (int x = -12; x < 12; x++) {
          final height = x > 3 && z > 6 ? 2 : 0;
          terrain.add(
            face(z, x: x, y: height, direction: WorldVoxelFaceDirection.up),
          );
          if (height > 0 && z == 7) {
            for (int y = 1; y <= height; y++) {
              terrain.add(face(z, x: x, y: y));
            }
          }
          if (height > 0 && x == 4) {
            for (int y = 1; y <= height; y++) {
              terrain.add(
                face(z, x: x, y: y, direction: WorldVoxelFaceDirection.west),
              );
            }
          }
        }
      }
      final recorder = ui.PictureRecorder();
      WorldDebugScenePainter(
        terrain,
        const WorldDebugCamera(x: 0.5, y: 1, z: 0, yaw: 0, pitch: 15),
      ).paint(Canvas(recorder), size);
      final picture = recorder.endRecording();
      await tester.runAsync(() async {
        final image = await picture.toImage(640, 360);
        final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
        final file = File(
          '../build/account-browser-evidence/debug-scene-fixture.png',
        );
        await file.parent.create(recursive: true);
        await file.writeAsBytes(bytes!.buffer.asUint8List());
        image.dispose();
      });
      picture.dispose();
    },
  );
}
