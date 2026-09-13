import 'package:flutter/foundation.dart';

import 'connection.dart';
import 'world_block_state.dart';

enum WorldVoxelFaceDirection { west, east, down, up, north, south }

@immutable
class WorldDebugVoxelFace {
  const WorldDebugVoxelFace({
    required this.chunkX,
    required this.chunkZ,
    required this.sectionY,
    required this.localX,
    required this.localY,
    required this.localZ,
    required this.direction,
    required this.stateId,
    required this.blockId,
    required this.renderHint,
  });

  final int chunkX;
  final int chunkZ;
  final int sectionY;
  final int localX;
  final int localY;
  final int localZ;
  final WorldVoxelFaceDirection direction;
  final int stateId;
  final String blockId;
  final WorldBlockRenderHint renderHint;

  int get blockX => chunkX * 16 + localX;
  int get blockY => sectionY * 16 + localY;
  int get blockZ => chunkZ * 16 + localZ;
}

@immutable
class WorldDebugVoxelMesh {
  WorldDebugVoxelMesh({
    required List<WorldDebugVoxelFace> faces,
    required this.unresolvedSourceCells,
    required this.suppressedUnknownNeighbors,
    required this.suppressedMissingNeighborSections,
  }) : faces = List.unmodifiable(faces);

  final List<WorldDebugVoxelFace> faces;
  final int unresolvedSourceCells;
  final int suppressedUnknownNeighbors;
  final int suppressedMissingNeighborSections;

  int get faceCount => faces.length;
}

abstract final class WorldDebugVoxelMesher {
  static WorldDebugVoxelMesh buildSection({
    required WorldSectionSnapshot section,
    required WorldSectionSnapshot? Function(
      int chunkX,
      int chunkZ,
      int sectionY,
    )
    sectionAt,
    required String? Function(int rawId) stateName,
  }) {
    final faces = <WorldDebugVoxelFace>[];
    int unresolvedSourceCells = 0;
    int suppressedUnknownNeighbors = 0;
    int suppressedMissingNeighborSections = 0;

    for (int localY = 0; localY < 16; localY++) {
      for (int localZ = 0; localZ < 16; localZ++) {
        for (int localX = 0; localX < 16; localX++) {
          final stateId = section.stateAt(localX, localY, localZ);
          final descriptor = WorldBlockStateDescriptor.fromCanonical(
            stateId,
            stateName(stateId),
          );
          if (!descriptor.isKnown) {
            unresolvedSourceCells++;
            continue;
          }
          if (descriptor.isAir) continue;

          for (final direction in WorldVoxelFaceDirection.values) {
            final neighbor = _neighbor(
              section: section,
              localX: localX,
              localY: localY,
              localZ: localZ,
              direction: direction,
              sectionAt: sectionAt,
            );
            if (neighbor.missingSection) {
              suppressedMissingNeighborSections++;
              continue;
            }
            final neighborStateId = neighbor.stateId;
            if (neighborStateId == null) {
              suppressedMissingNeighborSections++;
              continue;
            }
            final neighborDescriptor = WorldBlockStateDescriptor.fromCanonical(
              neighborStateId,
              stateName(neighborStateId),
            );
            if (!neighborDescriptor.isKnown) {
              suppressedUnknownNeighbors++;
              continue;
            }
            if (!neighborDescriptor.isAir) continue;

            faces.add(
              WorldDebugVoxelFace(
                chunkX: section.key.chunkX,
                chunkZ: section.key.chunkZ,
                sectionY: section.key.sectionY,
                localX: localX,
                localY: localY,
                localZ: localZ,
                direction: direction,
                stateId: stateId,
                blockId: descriptor.blockId!,
                renderHint: descriptor.renderHint,
              ),
            );
          }
        }
      }
    }

    return WorldDebugVoxelMesh(
      faces: faces,
      unresolvedSourceCells: unresolvedSourceCells,
      suppressedUnknownNeighbors: suppressedUnknownNeighbors,
      suppressedMissingNeighborSections: suppressedMissingNeighborSections,
    );
  }

  static _NeighborResult _neighbor({
    required WorldSectionSnapshot section,
    required int localX,
    required int localY,
    required int localZ,
    required WorldVoxelFaceDirection direction,
    required WorldSectionSnapshot? Function(
      int chunkX,
      int chunkZ,
      int sectionY,
    )
    sectionAt,
  }) {
    final (dx, dy, dz) = switch (direction) {
      WorldVoxelFaceDirection.west => (-1, 0, 0),
      WorldVoxelFaceDirection.east => (1, 0, 0),
      WorldVoxelFaceDirection.down => (0, -1, 0),
      WorldVoxelFaceDirection.up => (0, 1, 0),
      WorldVoxelFaceDirection.north => (0, 0, -1),
      WorldVoxelFaceDirection.south => (0, 0, 1),
    };
    int x = localX + dx;
    int y = localY + dy;
    int z = localZ + dz;
    int chunkX = section.key.chunkX;
    int chunkZ = section.key.chunkZ;
    int sectionY = section.key.sectionY;

    if (x < 0) {
      chunkX--;
      x = 15;
    } else if (x > 15) {
      chunkX++;
      x = 0;
    }
    if (z < 0) {
      chunkZ--;
      z = 15;
    } else if (z > 15) {
      chunkZ++;
      z = 0;
    }
    if (y < 0) {
      sectionY--;
      y = 15;
    } else if (y > 15) {
      sectionY++;
      y = 0;
    }

    if (chunkX == section.key.chunkX &&
        chunkZ == section.key.chunkZ &&
        sectionY == section.key.sectionY) {
      return _NeighborResult(stateId: section.stateAt(x, y, z));
    }

    final neighborSection = sectionAt(chunkX, chunkZ, sectionY);
    if (neighborSection == null) {
      return const _NeighborResult(missingSection: true);
    }
    if (neighborSection.key.subscriptionId != section.key.subscriptionId ||
        neighborSection.key.revision != section.key.revision ||
        neighborSection.key.chunkX != chunkX ||
        neighborSection.key.chunkZ != chunkZ ||
        neighborSection.key.sectionY != sectionY) {
      return const _NeighborResult(missingSection: true);
    }
    return _NeighborResult(stateId: neighborSection.stateAt(x, y, z));
  }
}

@immutable
class _NeighborResult {
  const _NeighborResult({this.stateId, this.missingSection = false});

  final int? stateId;
  final bool missingSection;
}
