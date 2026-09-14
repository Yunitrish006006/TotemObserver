import 'package:flutter/foundation.dart';

import 'connection.dart';

@immutable
class WorldSectionCoordinate {
  const WorldSectionCoordinate({
    required this.chunkX,
    required this.chunkZ,
    required this.sectionY,
  });

  final int chunkX;
  final int chunkZ;
  final int sectionY;

  @override
  bool operator ==(Object other) =>
      other is WorldSectionCoordinate &&
      chunkX == other.chunkX &&
      chunkZ == other.chunkZ &&
      sectionY == other.sectionY;

  @override
  int get hashCode => Object.hash(chunkX, chunkZ, sectionY);
}

/// Serializes bounded section requests for the current bootstrap revision.
///
/// [WorldSectionScheduler] owns section request sequencing for its connection:
/// callers enqueue desired coordinates here instead of calling
/// `ObserverConnection.requestWorldSection` concurrently.
class WorldSectionScheduler extends ChangeNotifier {
  WorldSectionScheduler(this.connection) {
    _captureContext();
    connection.addListener(_onConnectionChanged);
  }

  static const int maxQueuedSections = 64;

  final ObserverConnection connection;
  final List<WorldSectionCoordinate> _queue = [];
  final Set<WorldSectionCoordinate> _queued = {};
  WorldSectionCoordinate? _active;
  int _subscriptionId = 0;
  int _revision = 0;
  bool _disposed = false;

  WorldSectionCoordinate? get active => _active;
  int get queuedCount => _queue.length;
  bool get isIdle => _active == null && _queue.isEmpty;

  bool enqueue(WorldSectionCoordinate coordinate) {
    if (!_eligible(coordinate) ||
        _active == coordinate ||
        _queued.contains(coordinate) ||
        _isResolved(coordinate) ||
        _queue.length >= maxQueuedSections) {
      return false;
    }
    _queue.add(coordinate);
    _queued.add(coordinate);
    _pump();
    _notify();
    return true;
  }

  /// Queues the current bootstrap's horizontal chunk window center-first for
  /// one vertical section. The queue is bounded and deterministic.
  int enqueueHorizontalWindow(int sectionY) {
    if (!connection.hasWorldBootstrap) return 0;
    final candidates = <WorldSectionCoordinate>[];
    final radius = connection.bootstrapRadius;
    for (int dz = -radius; dz <= radius; dz++) {
      for (int dx = -radius; dx <= radius; dx++) {
        candidates.add(
          WorldSectionCoordinate(
            chunkX: connection.bootstrapCenterChunkX + dx,
            chunkZ: connection.bootstrapCenterChunkZ + dz,
            sectionY: sectionY,
          ),
        );
      }
    }
    candidates.sort((a, b) {
      final aDistance =
          (a.chunkX - connection.bootstrapCenterChunkX).abs() +
          (a.chunkZ - connection.bootstrapCenterChunkZ).abs();
      final bDistance =
          (b.chunkX - connection.bootstrapCenterChunkX).abs() +
          (b.chunkZ - connection.bootstrapCenterChunkZ).abs();
      final distance = aDistance.compareTo(bDistance);
      if (distance != 0) return distance;
      final z = a.chunkZ.compareTo(b.chunkZ);
      return z != 0 ? z : a.chunkX.compareTo(b.chunkX);
    });

    int accepted = 0;
    for (final coordinate in candidates) {
      if (enqueue(coordinate)) accepted++;
    }
    return accepted;
  }

  void clear() {
    if (_active == null && _queue.isEmpty) return;
    _active = null;
    _queue.clear();
    _queued.clear();
    _notify();
  }

  bool _eligible(WorldSectionCoordinate coordinate) {
    if (!connection.canRequestWorldSections ||
        connection.phase != ConnectionPhase.connected ||
        !connection.playerAttached ||
        (coordinate.chunkX - connection.bootstrapCenterChunkX).abs() >
            connection.bootstrapRadius ||
        (coordinate.chunkZ - connection.bootstrapCenterChunkZ).abs() >
            connection.bootstrapRadius) {
      return false;
    }
    final minSectionY = _sectionYForBlockY(connection.bootstrapMinY);
    final maxSectionY = _sectionYForBlockY(
      connection.bootstrapMinY + connection.bootstrapHeight - 1,
    );
    return coordinate.sectionY >= minSectionY &&
        coordinate.sectionY <= maxSectionY;
  }

  bool _isResolved(WorldSectionCoordinate coordinate) =>
      connection.worldSection(
            coordinate.chunkX,
            coordinate.chunkZ,
            coordinate.sectionY,
          ) !=
          null ||
      connection.worldSectionUnavailable(
        coordinate.chunkX,
        coordinate.chunkZ,
        coordinate.sectionY,
      );

  void _captureContext() {
    _subscriptionId = connection.bootstrapSubscriptionId;
    _revision = connection.bootstrapRevision;
  }

  bool _contextChanged() =>
      connection.bootstrapSubscriptionId != _subscriptionId ||
      connection.bootstrapRevision != _revision;

  void _onConnectionChanged() {
    if (_disposed) return;
    if (connection.phase != ConnectionPhase.connected ||
        !connection.hasWorldBootstrap ||
        _contextChanged()) {
      _active = null;
      _queue.clear();
      _queued.clear();
      _captureContext();
      _notify();
      return;
    }

    final active = _active;
    if (active != null && _isResolved(active)) {
      _active = null;
      _pump();
      _notify();
    } else if (active == null) {
      _pump();
      _notify();
    }
  }

  void _pump() {
    if (_active != null ||
        !connection.canSendWorldSectionNow ||
        connection.phase != ConnectionPhase.connected ||
        !connection.canRequestWorldSections ||
        _contextChanged()) {
      return;
    }
    while (_queue.isNotEmpty) {
      final next = _queue.removeAt(0);
      _queued.remove(next);
      if (!_eligible(next) || _isResolved(next)) continue;
      _active = next;
      connection.requestWorldSection(next.chunkX, next.chunkZ, next.sectionY);
      return;
    }
  }

  static int _sectionYForBlockY(int y) => y >= 0 ? y ~/ 16 : -((-y + 15) ~/ 16);

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    connection.removeListener(_onConnectionChanged);
    _queue.clear();
    _queued.clear();
    _active = null;
    super.dispose();
  }
}
