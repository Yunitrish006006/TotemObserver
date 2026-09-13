import 'package:flutter/foundation.dart';

import 'connection.dart';
import 'world_section_scheduler.dart';

@immutable
class VisibleWorldPlan {
  VisibleWorldPlan({
    required this.subscriptionId,
    required this.revision,
    required this.anchorSectionY,
    required List<WorldSectionCoordinate> targets,
  }) : targets = List.unmodifiable(targets);

  final int subscriptionId;
  final int revision;
  final int anchorSectionY;
  final List<WorldSectionCoordinate> targets;
}

/// Chooses a small world volume around the authoritative Observer player.
///
/// The normal radius-2 bootstrap produces at most 43 section targets:
/// 3x3 on the player's section, 3x3 below, 3x3 above, then the remaining
/// radius-2 ring on the player's section. Vertical edge layers are omitted.
class WorldVisibleViewPolicy {
  static const int maxTargets = 43;

  static VisibleWorldPlan? create(ObserverConnection connection) {
    if (!connection.hasWorldState ||
        !connection.hasWorldBootstrap ||
        !connection.canRequestWorldSections ||
        connection.worldDimension != connection.bootstrapDimension) {
      return null;
    }

    final minSectionY = _sectionYForBlockY(connection.bootstrapMinY);
    final maxSectionY = _sectionYForBlockY(
      connection.bootstrapMinY + connection.bootstrapHeight - 1,
    );
    final anchorSectionY = (connection.worldY / 16).floor();
    if (anchorSectionY < minSectionY || anchorSectionY > maxSectionY) {
      return null;
    }

    final horizontalRadius = connection.bootstrapRadius < 2
        ? connection.bootstrapRadius
        : 2;
    final localRadius = horizontalRadius < 1 ? horizontalRadius : 1;
    final targets = <WorldSectionCoordinate>[];
    final seen = <WorldSectionCoordinate>{};

    void addSquare(int sectionY, int radius, {bool outerOnly = false}) {
      for (final coordinate in _square(
        connection.bootstrapCenterChunkX,
        connection.bootstrapCenterChunkZ,
        sectionY,
        radius,
      )) {
        if (outerOnly &&
            (coordinate.chunkX - connection.bootstrapCenterChunkX).abs() <=
                localRadius &&
            (coordinate.chunkZ - connection.bootstrapCenterChunkZ).abs() <=
                localRadius) {
          continue;
        }
        if (seen.add(coordinate)) targets.add(coordinate);
      }
    }

    addSquare(anchorSectionY, localRadius);
    if (anchorSectionY > minSectionY) {
      addSquare(anchorSectionY - 1, localRadius);
    }
    if (anchorSectionY < maxSectionY) {
      addSquare(anchorSectionY + 1, localRadius);
    }
    if (horizontalRadius > localRadius) {
      addSquare(anchorSectionY, horizontalRadius, outerOnly: true);
    }

    if (targets.length > maxTargets) throw StateError('Visible-world budget');
    return VisibleWorldPlan(
      subscriptionId: connection.bootstrapSubscriptionId,
      revision: connection.bootstrapRevision,
      anchorSectionY: anchorSectionY,
      targets: targets,
    );
  }

  static List<WorldSectionCoordinate> _square(
    int centerChunkX,
    int centerChunkZ,
    int sectionY,
    int radius,
  ) {
    final values = <WorldSectionCoordinate>[];
    for (int dz = -radius; dz <= radius; dz++) {
      for (int dx = -radius; dx <= radius; dx++) {
        values.add(
          WorldSectionCoordinate(
            chunkX: centerChunkX + dx,
            chunkZ: centerChunkZ + dz,
            sectionY: sectionY,
          ),
        );
      }
    }
    values.sort((a, b) {
      final aDistance =
          (a.chunkX - centerChunkX).abs() + (a.chunkZ - centerChunkZ).abs();
      final bDistance =
          (b.chunkX - centerChunkX).abs() + (b.chunkZ - centerChunkZ).abs();
      final distance = aDistance.compareTo(bDistance);
      if (distance != 0) return distance;
      final z = a.chunkZ.compareTo(b.chunkZ);
      return z != 0 ? z : a.chunkX.compareTo(b.chunkX);
    });
    return values;
  }

  static int _sectionYForBlockY(int y) => y >= 0 ? y ~/ 16 : -((-y + 15) ~/ 16);
}

/// Applies [WorldVisibleViewPolicy] to the current connection and keeps the
/// section scheduler filled only when the bootstrap/player-section context
/// changes. Snapshot and unavailable progress is read directly from the
/// revision-bound caches owned by [ObserverConnection].
class WorldVisibleViewController extends ChangeNotifier {
  WorldVisibleViewController(this.connection, this.scheduler) {
    connection.addListener(_onConnectionChanged);
    scheduler.addListener(_onSchedulerChanged);
    _refresh();
  }

  final ObserverConnection connection;
  final WorldSectionScheduler scheduler;
  VisibleWorldPlan? _plan;
  bool _disposed = false;

  VisibleWorldPlan? get plan => _plan;
  bool get hasPlan => _plan != null;
  int get targetCount => _plan?.targets.length ?? 0;
  int get availableCount => _count((target) => _snapshot(target));
  int get unavailableCount => _count((target) => _unavailable(target));
  int get resolvedCount => availableCount + unavailableCount;

  int _count(bool Function(WorldSectionCoordinate) predicate) {
    final current = _plan;
    if (current == null) return 0;
    int value = 0;
    for (final target in current.targets) {
      if (predicate(target)) value++;
    }
    return value;
  }

  bool _snapshot(WorldSectionCoordinate target) =>
      connection.worldSection(target.chunkX, target.chunkZ, target.sectionY) !=
      null;

  bool _unavailable(WorldSectionCoordinate target) => connection
      .worldSectionUnavailable(target.chunkX, target.chunkZ, target.sectionY);

  bool _sameContext(VisibleWorldPlan a, VisibleWorldPlan b) =>
      a.subscriptionId == b.subscriptionId &&
      a.revision == b.revision &&
      a.anchorSectionY == b.anchorSectionY;

  void _onConnectionChanged() {
    if (!_disposed) _refresh();
  }

  void _onSchedulerChanged() {
    if (!_disposed) notifyListeners();
  }

  void _refresh() {
    final next = WorldVisibleViewPolicy.create(connection);
    final previous = _plan;
    if (next == null) {
      if (previous != null) {
        _plan = null;
        scheduler.clear();
      }
      if (!_disposed) notifyListeners();
      return;
    }
    if (previous != null && _sameContext(previous, next)) {
      if (!_disposed) notifyListeners();
      return;
    }

    _plan = next;
    scheduler.clear();
    for (final target in next.targets) {
      scheduler.enqueue(target);
    }
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    connection.removeListener(_onConnectionChanged);
    scheduler.removeListener(_onSchedulerChanged);
    _plan = null;
    super.dispose();
  }
}
