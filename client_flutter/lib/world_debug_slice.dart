import 'package:flutter/material.dart';

import 'connection.dart';
import 'world_visible_view.dart';

@immutable
class WorldDebugChunkCoordinate {
  const WorldDebugChunkCoordinate(this.chunkX, this.chunkZ);

  final int chunkX;
  final int chunkZ;

  @override
  bool operator ==(Object other) =>
      other is WorldDebugChunkCoordinate &&
      chunkX == other.chunkX &&
      chunkZ == other.chunkZ;

  @override
  int get hashCode => Object.hash(chunkX, chunkZ);
}

@immutable
class WorldDebugSliceSnapshot {
  WorldDebugSliceSnapshot({
    required this.blockY,
    required this.sectionY,
    required this.localY,
    required this.startChunkX,
    required this.startChunkZ,
    required this.chunkCount,
    required List<int?> stateIds,
    required Set<WorldDebugChunkCoordinate> availableChunks,
    required Set<WorldDebugChunkCoordinate> unavailableChunks,
    required this.playerGridX,
    required this.playerGridZ,
  }) : stateIds = List.unmodifiable(stateIds),
       availableChunks = Set.unmodifiable(availableChunks),
       unavailableChunks = Set.unmodifiable(unavailableChunks) {
    if (chunkCount <= 0 ||
        localY < 0 ||
        localY > 15 ||
        this.stateIds.length != size * size) {
      throw const FormatException();
    }
  }

  final int blockY;
  final int sectionY;
  final int localY;
  final int startChunkX;
  final int startChunkZ;
  final int chunkCount;
  final List<int?> stateIds;
  final Set<WorldDebugChunkCoordinate> availableChunks;
  final Set<WorldDebugChunkCoordinate> unavailableChunks;
  final int? playerGridX;
  final int? playerGridZ;

  int get size => chunkCount * 16;
  int get totalChunkCount => chunkCount * chunkCount;
  int get availableChunkCount => availableChunks.length;
  int get unavailableChunkCount => unavailableChunks.length;
  int get pendingChunkCount =>
      totalChunkCount - availableChunkCount - unavailableChunkCount;

  int? stateAtGrid(int gridX, int gridZ) {
    if (gridX < 0 || gridX >= size || gridZ < 0 || gridZ >= size) {
      throw RangeError('Debug-slice coordinates must be inside the view');
    }
    return stateIds[gridZ * size + gridX];
  }

  WorldDebugChunkCoordinate chunkAt(int chunkOffsetX, int chunkOffsetZ) =>
      WorldDebugChunkCoordinate(
        startChunkX + chunkOffsetX,
        startChunkZ + chunkOffsetZ,
      );

  static WorldDebugSliceSnapshot? create(
    ObserverConnection connection,
    VisibleWorldPlan plan,
  ) {
    if (!connection.hasWorldState ||
        !connection.hasWorldBootstrap ||
        plan.subscriptionId != connection.bootstrapSubscriptionId ||
        plan.revision != connection.bootstrapRevision ||
        plan.anchorSectionY != (connection.worldY / 16).floor() ||
        connection.worldDimension != connection.bootstrapDimension) {
      return null;
    }

    final radius = connection.bootstrapRadius < 2
        ? connection.bootstrapRadius
        : 2;
    if (radius < 0) return null;
    final chunkCount = radius * 2 + 1;
    final size = chunkCount * 16;
    final startChunkX = connection.bootstrapCenterChunkX - radius;
    final startChunkZ = connection.bootstrapCenterChunkZ - radius;
    final blockY = connection.worldY.floor();
    final sectionY = plan.anchorSectionY;
    final localY = blockY - sectionY * 16;
    if (localY < 0 || localY > 15) return null;

    final stateIds = List<int?>.filled(size * size, null);
    final available = <WorldDebugChunkCoordinate>{};
    final unavailable = <WorldDebugChunkCoordinate>{};

    for (int chunkOffsetZ = 0; chunkOffsetZ < chunkCount; chunkOffsetZ++) {
      for (int chunkOffsetX = 0; chunkOffsetX < chunkCount; chunkOffsetX++) {
        final chunkX = startChunkX + chunkOffsetX;
        final chunkZ = startChunkZ + chunkOffsetZ;
        final coordinate = WorldDebugChunkCoordinate(chunkX, chunkZ);
        final section = connection.worldSection(chunkX, chunkZ, sectionY);
        if (section == null) {
          if (connection.worldSectionUnavailable(chunkX, chunkZ, sectionY)) {
            unavailable.add(coordinate);
          }
          continue;
        }
        available.add(coordinate);
        for (int localZ = 0; localZ < 16; localZ++) {
          final gridZ = chunkOffsetZ * 16 + localZ;
          for (int localX = 0; localX < 16; localX++) {
            final gridX = chunkOffsetX * 16 + localX;
            stateIds[gridZ * size + gridX] = section.stateAt(
              localX,
              localY,
              localZ,
            );
          }
        }
      }
    }

    final playerGridX = connection.worldX.floor() - startChunkX * 16;
    final playerGridZ = connection.worldZ.floor() - startChunkZ * 16;
    final playerInside =
        playerGridX >= 0 &&
        playerGridX < size &&
        playerGridZ >= 0 &&
        playerGridZ < size;

    return WorldDebugSliceSnapshot(
      blockY: blockY,
      sectionY: sectionY,
      localY: localY,
      startChunkX: startChunkX,
      startChunkZ: startChunkZ,
      chunkCount: chunkCount,
      stateIds: stateIds,
      availableChunks: available,
      unavailableChunks: unavailable,
      playerGridX: playerInside ? playerGridX : null,
      playerGridZ: playerInside ? playerGridZ : null,
    );
  }
}

abstract final class WorldDebugSlicePalette {
  static const Color empty = Color(0xff151515);
  static const Color pending = Color(0xff242424);
  static const Color unavailable = Color(0xff3a3a3a);

  static Color colorFor(int stateId, String? stateName) {
    if (_isAir(stateName)) return empty;
    final hue = ((stateId * 47) % 360).toDouble();
    return HSVColor.fromAHSV(1, hue, 0.58, 0.82).toColor();
  }

  static bool _isAir(String? stateName) {
    if (stateName == null) return false;
    final property = stateName.indexOf('[');
    final block = property < 0 ? stateName : stateName.substring(0, property);
    return block == 'minecraft:air' ||
        block == 'minecraft:cave_air' ||
        block == 'minecraft:void_air';
  }
}

class WorldDebugSliceView extends StatelessWidget {
  const WorldDebugSliceView({
    super.key,
    required this.connection,
    required this.plan,
  });

  final ObserverConnection connection;
  final VisibleWorldPlan plan;

  @override
  Widget build(BuildContext context) {
    final snapshot = WorldDebugSliceSnapshot.create(connection, plan);
    if (snapshot == null) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          '偵錯世界切片：Y=${snapshot.blockY}（raw BlockState）',
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: 8),
        AspectRatio(
          aspectRatio: 1,
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: WorldDebugSlicePalette.pending,
              border: Border.all(color: const Color(0xff707070)),
            ),
            child: CustomPaint(
              painter: _WorldDebugSlicePainter(
                snapshot: snapshot,
                stateName: connection.blockStateName,
              ),
            ),
          ),
        ),
        const SizedBox(height: 8),
        Text(
          '切片區塊：可用 ${snapshot.availableChunkCount} / '
          '未載入 ${snapshot.unavailableChunkCount} / '
          '等待 ${snapshot.pendingChunkCount}',
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: 4),
        const Text(
          '此圖只用 registry 名稱辨識原版空氣，其餘方塊依 raw state ID 著色；不是正式材質或光照 renderer。',
          textAlign: TextAlign.center,
          style: TextStyle(fontSize: 12, color: Color(0xffbdbdbd)),
        ),
      ],
    );
  }
}

class _WorldDebugSlicePainter extends CustomPainter {
  const _WorldDebugSlicePainter({
    required this.snapshot,
    required this.stateName,
  });

  final WorldDebugSliceSnapshot snapshot;
  final String? Function(int rawId) stateName;

  @override
  void paint(Canvas canvas, Size size) {
    if (size.width <= 0 || size.height <= 0) return;
    final cellWidth = size.width / snapshot.size;
    final cellHeight = size.height / snapshot.size;
    final chunkWidth = size.width / snapshot.chunkCount;
    final chunkHeight = size.height / snapshot.chunkCount;
    final paint = Paint();

    paint.color = WorldDebugSlicePalette.pending;
    canvas.drawRect(Offset.zero & size, paint);

    for (int chunkZ = 0; chunkZ < snapshot.chunkCount; chunkZ++) {
      for (int chunkX = 0; chunkX < snapshot.chunkCount; chunkX++) {
        final coordinate = snapshot.chunkAt(chunkX, chunkZ);
        if (!snapshot.unavailableChunks.contains(coordinate)) continue;
        final rect = Rect.fromLTWH(
          chunkX * chunkWidth,
          chunkZ * chunkHeight,
          chunkWidth,
          chunkHeight,
        );
        paint.color = WorldDebugSlicePalette.unavailable;
        paint.style = PaintingStyle.fill;
        canvas.drawRect(rect, paint);
        paint.color = const Color(0xff666666);
        paint.style = PaintingStyle.stroke;
        paint.strokeWidth = 1;
        canvas.drawLine(rect.topLeft, rect.bottomRight, paint);
        canvas.drawLine(rect.topRight, rect.bottomLeft, paint);
      }
    }

    paint.style = PaintingStyle.fill;
    for (int gridZ = 0; gridZ < snapshot.size; gridZ++) {
      for (int gridX = 0; gridX < snapshot.size; gridX++) {
        final stateId = snapshot.stateIds[gridZ * snapshot.size + gridX];
        if (stateId == null) continue;
        paint.color = WorldDebugSlicePalette.colorFor(
          stateId,
          stateName(stateId),
        );
        canvas.drawRect(
          Rect.fromLTWH(
            gridX * cellWidth,
            gridZ * cellHeight,
            cellWidth + 0.2,
            cellHeight + 0.2,
          ),
          paint,
        );
      }
    }

    paint.style = PaintingStyle.stroke;
    paint.strokeWidth = 1;
    paint.color = const Color(0x66999999);
    for (int index = 0; index <= snapshot.chunkCount; index++) {
      final x = index * chunkWidth;
      final z = index * chunkHeight;
      canvas.drawLine(Offset(x, 0), Offset(x, size.height), paint);
      canvas.drawLine(Offset(0, z), Offset(size.width, z), paint);
    }

    final playerX = snapshot.playerGridX;
    final playerZ = snapshot.playerGridZ;
    if (playerX != null && playerZ != null) {
      final center = Offset(
        (playerX + 0.5) * cellWidth,
        (playerZ + 0.5) * cellHeight,
      );
      final radius = (cellWidth < cellHeight ? cellWidth : cellHeight) * 1.25;
      paint.style = PaintingStyle.fill;
      paint.color = const Color(0xffffffff);
      canvas.drawCircle(center, radius < 2 ? 2 : radius, paint);
      paint.style = PaintingStyle.stroke;
      paint.strokeWidth = 1.5;
      paint.color = const Color(0xff000000);
      canvas.drawCircle(center, radius < 2 ? 2 : radius, paint);
    }
  }

  @override
  bool shouldRepaint(covariant _WorldDebugSlicePainter oldDelegate) => true;
}
