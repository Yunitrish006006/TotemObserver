import 'dart:math' as math;

import 'package:flutter/material.dart';

import 'connection.dart';
import 'world_debug_voxel_mesh.dart';
import 'world_visible_view.dart';

@immutable
class WorldDebugCamera {
  const WorldDebugCamera({
    required this.x,
    required this.y,
    required this.z,
    required this.yaw,
    required this.pitch,
  });
  final double x, y, z, yaw, pitch;

  bool get valid =>
      [x, y, z, yaw, pitch].every((v) => v.isFinite) &&
      pitch >= -90 &&
      pitch <= 90;

  // Minecraft yaw 0 looks south (+Z), yaw 90 west (-X);
  // positive pitch looks down. The snapshot position is the player's feet.
  _Point transform(_Point p) {
    final a = yaw * math.pi / 180;
    final b = pitch * math.pi / 180;
    final dx = p.x - x, dy = p.y - (y + 1.62), dz = p.z - z;
    final forward = -math.sin(a) * dx + math.cos(a) * dz;
    return _Point(
      math.cos(a) * dx + math.sin(a) * dz,
      math.cos(b) * dy + math.sin(b) * forward,
      -math.sin(b) * dy + math.cos(b) * forward,
    );
  }
}

@immutable
class WorldDebugProjectedFace {
  WorldDebugProjectedFace(List<Offset> points, this.depth, this.color)
    : points = List.unmodifiable(points);
  final List<Offset> points;
  final double depth;
  final Color color;
}

/// Pure cache-to-scene extraction. No retained mesh, network or gameplay state.
abstract final class WorldDebugScene {
  static const maxFaces = 16384;
  static const near = 0.08;
  static const far = 64.0;

  static List<WorldDebugVoxelFace> collect(
    ObserverConnection connection,
    VisibleWorldPlan plan,
  ) {
    if (connection.phase != ConnectionPhase.connected ||
        !connection.playerAttached ||
        !connection.hasWorldRegistry ||
        !connection.hasWorldState ||
        !connection.hasWorldBootstrap ||
        connection.worldDimension != connection.bootstrapDimension ||
        plan.subscriptionId != connection.bootstrapSubscriptionId ||
        plan.revision != connection.bootstrapRevision ||
        plan.anchorSectionY != (connection.worldY / 16).floor() ||
        plan.targets.length > WorldVisibleViewPolicy.maxTargets) {
      return const [];
    }
    final faces = <WorldDebugVoxelFace>[];
    for (final target in plan.targets) {
      final section = connection.worldSection(
        target.chunkX,
        target.chunkZ,
        target.sectionY,
      );
      if (section == null) continue;
      final mesh = WorldDebugVoxelMesher.buildSection(
        section: section,
        expectedKey: WorldSectionKey(
          subscriptionId: plan.subscriptionId,
          revision: plan.revision,
          chunkX: target.chunkX,
          chunkZ: target.chunkZ,
          sectionY: target.sectionY,
        ),
        sectionAt: connection.worldSection,
        stateName: connection.blockStateName,
      );
      faces.addAll(mesh.faces.take(maxFaces - faces.length));
      if (faces.length == maxFaces) break;
    }
    return List.unmodifiable(faces);
  }

  static Color colorFor(String blockId, WorldVoxelFaceDirection direction) {
    int hash = 0;
    for (final unit in blockId.codeUnits) {
      hash = (hash * 31 + unit) & 0x7fffffff;
    }
    final brightness = switch (direction) {
      WorldVoxelFaceDirection.up => 0.85,
      WorldVoxelFaceDirection.down => 0.38,
      WorldVoxelFaceDirection.east || WorldVoxelFaceDirection.west => 0.62,
      _ => 0.72,
    };
    return HSVColor.fromAHSV(
      1,
      (hash % 360).toDouble(),
      0.42,
      brightness,
    ).toColor();
  }

  static List<WorldDebugProjectedFace> project(
    Iterable<WorldDebugVoxelFace> faces,
    WorldDebugCamera camera,
    Size size,
  ) {
    if (!camera.valid ||
        !size.width.isFinite ||
        !size.height.isFinite ||
        size.width <= 0 ||
        size.height <= 0)
      return const [];
    final aspect = size.width / size.height;
    final tanHalfFov = math.tan(70 * math.pi / 360);
    final result = <WorldDebugProjectedFace>[];
    for (final face in faces.take(maxFaces)) {
      final x = face.blockX.toDouble(),
          y = face.blockY.toDouble(),
          z = face.blockZ.toDouble();
      final (normal, vertices) = switch (face.direction) {
        WorldVoxelFaceDirection.west => (
          const _Point(-1, 0, 0),
          [
            _Point(x, y, z),
            _Point(x, y, z + 1),
            _Point(x, y + 1, z + 1),
            _Point(x, y + 1, z),
          ],
        ),
        WorldVoxelFaceDirection.east => (
          const _Point(1, 0, 0),
          [
            _Point(x + 1, y, z),
            _Point(x + 1, y + 1, z),
            _Point(x + 1, y + 1, z + 1),
            _Point(x + 1, y, z + 1),
          ],
        ),
        WorldVoxelFaceDirection.down => (
          const _Point(0, -1, 0),
          [
            _Point(x, y, z),
            _Point(x + 1, y, z),
            _Point(x + 1, y, z + 1),
            _Point(x, y, z + 1),
          ],
        ),
        WorldVoxelFaceDirection.up => (
          const _Point(0, 1, 0),
          [
            _Point(x, y + 1, z),
            _Point(x, y + 1, z + 1),
            _Point(x + 1, y + 1, z + 1),
            _Point(x + 1, y + 1, z),
          ],
        ),
        WorldVoxelFaceDirection.north => (
          const _Point(0, 0, -1),
          [
            _Point(x, y, z),
            _Point(x, y + 1, z),
            _Point(x + 1, y + 1, z),
            _Point(x + 1, y, z),
          ],
        ),
        WorldVoxelFaceDirection.south => (
          const _Point(0, 0, 1),
          [
            _Point(x, y, z + 1),
            _Point(x + 1, y, z + 1),
            _Point(x + 1, y + 1, z + 1),
            _Point(x, y + 1, z + 1),
          ],
        ),
      };
      final origin = vertices.first;
      if (normal.x * (camera.x - origin.x) +
              normal.y * (camera.y + 1.62 - origin.y) +
              normal.z * (camera.z - origin.z) <=
          0)
        continue;
      var polygon = vertices.map(camera.transform).toList();
      // Clip in camera space before division, including all viewport planes.
      for (final plane in <double Function(_Point)>[
        (p) => p.z - near,
        (p) => far - p.z,
        (p) => p.z * tanHalfFov * aspect + p.x,
        (p) => p.z * tanHalfFov * aspect - p.x,
        (p) => p.z * tanHalfFov + p.y,
        (p) => p.z * tanHalfFov - p.y,
      ]) {
        polygon = _clip(polygon, plane);
        if (polygon.isEmpty) break;
      }
      if (polygon.length < 3) continue;
      final points = polygon
          .map(
            (p) => Offset(
              size.width / 2 + p.x / p.z * size.height / (2 * tanHalfFov),
              size.height / 2 - p.y / p.z * size.height / (2 * tanHalfFov),
            ),
          )
          .toList();
      result.add(
        WorldDebugProjectedFace(
          points,
          polygon.fold(0.0, (sum, p) => sum + p.z) / polygon.length,
          colorFor(face.blockId, face.direction),
        ),
      );
    }
    // Bounded painter ordering for diagnostic, opaque, non-intersecting cubes.
    // This is not a depth-buffered resource-pack renderer.
    result.sort((a, b) => b.depth.compareTo(a.depth));
    return List.unmodifiable(result);
  }

  static List<_Point> _clip(List<_Point> input, double Function(_Point) plane) {
    if (input.isEmpty) return input;
    final output = <_Point>[];
    var previous = input.last;
    var previousDistance = plane(previous);
    for (final current in input) {
      final distance = plane(current);
      if ((distance >= 0) != (previousDistance >= 0)) {
        final t = previousDistance / (previousDistance - distance);
        output.add(
          _Point(
            previous.x + t * (current.x - previous.x),
            previous.y + t * (current.y - previous.y),
            previous.z + t * (current.z - previous.z),
          ),
        );
      }
      if (distance >= 0) output.add(current);
      previous = current;
      previousDistance = distance;
    }
    return output;
  }
}

class _Point {
  const _Point(this.x, this.y, this.z);
  final double x, y, z;
}

class WorldDebugSceneView extends StatelessWidget {
  const WorldDebugSceneView({
    super.key,
    required this.connection,
    required this.plan,
  });
  final ObserverConnection connection;
  final VisibleWorldPlan plan;

  @override
  Widget build(BuildContext context) {
    final faces = WorldDebugScene.collect(connection, plan);
    final camera = WorldDebugCamera(
      x: connection.worldX,
      y: connection.worldY,
      z: connection.worldZ,
      yaw: connection.worldYaw,
      pitch: connection.worldPitch,
    );
    return Semantics(
      label: '3D 偵錯地形，視角來自伺服器角色位置與朝向',
      child: AspectRatio(
        aspectRatio: 16 / 9,
        child: ClipRect(
          child: CustomPaint(
            key: const ValueKey('world-debug-scene'),
            painter: WorldDebugScenePainter(faces, camera),
          ),
        ),
      ),
    );
  }
}

class WorldDebugScenePainter extends CustomPainter {
  const WorldDebugScenePainter(this.faces, this.camera);
  final List<WorldDebugVoxelFace> faces;
  final WorldDebugCamera camera;

  @override
  void paint(Canvas canvas, Size size) {
    if (size.isEmpty) return;
    final paint = Paint()..isAntiAlias = false;
    canvas.save();
    canvas.clipRect(Offset.zero & size);
    canvas.drawRect(Offset.zero & size, paint..color = const Color(0xff7894ad));
    for (final face in WorldDebugScene.project(faces, camera, size)) {
      final path = Path()..addPolygon(face.points, true);
      canvas.drawPath(path, paint..color = face.color);
      canvas.drawPath(
        path,
        paint
          ..color = const Color(0x22404040)
          ..style = PaintingStyle.stroke
          ..strokeWidth = 0.5,
      );
      paint.style = PaintingStyle.fill;
    }
    paint
      ..color = Colors.white
      ..strokeWidth = 1;
    final center = Offset(size.width / 2, size.height / 2);
    canvas.drawLine(
      center - const Offset(4, 0),
      center + const Offset(4, 0),
      paint,
    );
    canvas.drawLine(
      center - const Offset(0, 4),
      center + const Offset(0, 4),
      paint,
    );
    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant WorldDebugScenePainter oldDelegate) => true;
}
