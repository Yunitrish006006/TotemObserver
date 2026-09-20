import 'dart:async';

import 'connection.dart';
import 'world_section_scheduler.dart';
import 'world_visible_view.dart';

/// Resolves canonical names used by the current visible cache, never by drawing.
class WorldRegistryHydrator {
  WorldRegistryHydrator(this.connection, this.visible) {
    _timer = Timer.periodic(const Duration(milliseconds: 100), (_) => _pump());
  }

  static const maxPages = 256;
  final ObserverConnection connection;
  final WorldVisibleViewController visible;
  Timer? _timer;
  VisibleWorldPlan? _plan;
  final Map<WorldSectionCoordinate, WorldSectionSnapshot> _sections = {};
  final Set<int> _pages = {};

  void _pump() {
    final plan = visible.plan;
    if (connection.phase != ConnectionPhase.connected ||
        !connection.playerAttached ||
        !connection.hasWorldRegistry ||
        plan == null ||
        plan.subscriptionId != connection.bootstrapSubscriptionId ||
        plan.revision != connection.bootstrapRevision ||
        connection.worldDimension != connection.bootstrapDimension ||
        plan.targets.length > WorldVisibleViewPolicy.maxTargets) {
      _plan = null;
      _sections.clear();
      _pages.clear();
      return;
    }
    final current = <WorldSectionCoordinate, WorldSectionSnapshot>{};
    for (final target in plan.targets) {
      final snapshot = connection.worldSection(
        target.chunkX,
        target.chunkZ,
        target.sectionY,
      );
      if (snapshot != null &&
          snapshot.key ==
              WorldSectionKey(
                subscriptionId: plan.subscriptionId,
                revision: plan.revision,
                chunkX: target.chunkX,
                chunkZ: target.chunkZ,
                sectionY: target.sectionY,
              )) {
        current[target] = snapshot;
      }
    }
    if (!identical(plan, _plan) ||
        current.length != _sections.length ||
        current.entries.any(
          (entry) => !identical(entry.value, _sections[entry.key]),
        )) {
      _plan = plan;
      _sections
        ..clear()
        ..addAll(current);
      _pages
        ..clear()
        ..add(0);
      // Center-first plan order, then immutable section order. Once full, omit
      // remaining unknown geometry instead of evicting/re-requesting in a loop.
      outer:
      for (final snapshot in current.values) {
        for (final rawId in snapshot.stateIds) {
          if (rawId < 0 || rawId >= connection.registryTotal) continue;
          final page = (rawId ~/ 8) * 8;
          if (_pages.contains(page)) continue;
          if (_pages.length == maxPages) break outer;
          _pages.add(page);
        }
      }
      connection.retainBlockStatePages(_pages);
    }
    if (connection.worldRegistryPending) return;
    for (final offset in _pages) {
      for (
        int rawId = offset;
        rawId < offset + 8 && rawId < connection.registryTotal;
        rawId++
      ) {
        if (connection.blockStateName(rawId) == null) {
          connection.requestBlockState(rawId);
          return;
        }
      }
    }
  }

  void dispose() {
    _timer?.cancel();
    _timer = null;
    _plan = null;
    _sections.clear();
    _pages.clear();
  }
}
