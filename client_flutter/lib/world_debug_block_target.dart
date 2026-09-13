import 'dart:math' as math;

import 'package:flutter/foundation.dart';

import 'connection.dart';
import 'world_block_state.dart';
import 'world_debug_voxel_mesh.dart';

/// Immutable render suggestion. It confers no permission or authoritative hit.
@immutable
class WorldDebugBlockTarget {
  const WorldDebugBlockTarget({
    required this.key,
    required this.x,
    required this.y,
    required this.z,
    required this.face,
    required this.distance,
    required this.stateId,
  });

  final WorldSectionKey key;
  final int x, y, z, stateId;
  final WorldVoxelFaceDirection face;
  final double distance;
}

/// Bounded voxel traversal through known air, using the mesher's debug policy.
/// Missing, stale, unknown or unsupported cells stop traversal; no network owner.
abstract final class WorldDebugBlockRaycast {
  static const maxReach = 6.0;
  static const maxSteps = 32;

  static WorldDebugBlockTarget? cast({
    required double eyeX,
    required double eyeY,
    required double eyeZ,
    required double yaw,
    required double pitch,
    required int subscriptionId,
    required int revision,
    required WorldSectionSnapshot? Function(int, int, int) sectionAt,
    required String? Function(int) stateName,
    double reach = maxReach,
  }) {
    if (![eyeX, eyeY, eyeZ, yaw, pitch, reach].every((v) => v.isFinite) ||
        [eyeX, eyeY, eyeZ].any((v) => v.abs() > 30000000) ||
        pitch.abs() > 90 ||
        yaw.abs() > 360 ||
        reach <= 0 ||
        reach > maxReach ||
        subscriptionId <= 0 ||
        revision <= 0) {
      return null;
    }
    final a = yaw * math.pi / 180, b = pitch * math.pi / 180;
    final direction = [
      -math.sin(a) * math.cos(b),
      -math.sin(b),
      math.cos(a) * math.cos(b),
    ];
    final origin = [eyeX, eyeY, eyeZ];
    final cell = origin.map((v) => v.floor()).toList();
    final step = direction
        .map((v) => v.abs() < 1e-12 ? 0 : (v > 0 ? 1 : -1))
        .toList();
    // A ray lying on a voxel boundary has two possible adjacent cells. Avoid
    // choosing one side and overlooking unknown or blocking geometry on the other.
    for (int i = 0; i < 3; i++) {
      if (step[i] == 0 && origin[i] == cell[i]) return null;
    }
    final delta = [
      for (int i = 0; i < 3; i++)
        step[i] == 0 ? double.infinity : 1 / direction[i].abs(),
    ];
    final next = [
      for (int i = 0; i < 3; i++)
        step[i] == 0
            ? double.infinity
            : ((step[i] > 0 ? cell[i] + 1 : cell[i]) - origin[i]) /
                  direction[i],
    ];
    double distance = 0;
    WorldVoxelFaceDirection? entered;
    for (int count = 0; count < maxSteps; count++) {
      final x = cell[0], y = cell[1], z = cell[2];
      final key = WorldSectionKey(
        subscriptionId: subscriptionId,
        revision: revision,
        chunkX: (x / 16).floor(),
        chunkZ: (z / 16).floor(),
        sectionY: (y / 16).floor(),
      );
      final section = sectionAt(key.chunkX, key.chunkZ, key.sectionY);
      if (section == null || section.key != key) return null;
      final rawId = section.stateAt(x % 16, y % 16, z % 16);
      final descriptor = WorldBlockStateDescriptor.fromCanonical(
        rawId,
        stateName(rawId),
      );
      if (!descriptor.isKnown) return null;
      if (!descriptor.isAir) {
        if (entered == null || !WorldDebugVoxelMesher.supports(descriptor))
          return null;
        return WorldDebugBlockTarget(
          key: key,
          x: x,
          y: y,
          z: z,
          face: entered,
          distance: distance,
          stateId: rawId,
        );
      }
      final nearest = math.min(next[0], math.min(next[1], next[2]));
      if (!nearest.isFinite || nearest > reach) return null;
      // Exact edges/corners have ambiguous entry faces. Do not skip a potentially
      // blocking side cell or invent a target through a zero-width gap.
      final axes = [
        for (int i = 0; i < 3; i++)
          if ((next[i] - nearest).abs() < 1e-10) i,
      ];
      if (axes.length != 1) return null;
      final axis = axes.single;
      entered = switch (axis) {
        0 =>
          step[axis] > 0
              ? WorldVoxelFaceDirection.west
              : WorldVoxelFaceDirection.east,
        1 =>
          step[axis] > 0
              ? WorldVoxelFaceDirection.down
              : WorldVoxelFaceDirection.up,
        _ =>
          step[axis] > 0
              ? WorldVoxelFaceDirection.north
              : WorldVoxelFaceDirection.south,
      };
      cell[axis] += step[axis];
      distance = nearest;
      next[axis] += delta[axis];
    }
    return null;
  }
}
