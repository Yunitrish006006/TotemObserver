import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/connection.dart';
import 'package:totem_observer_client/world_registry_hydrator.dart';
import 'package:totem_observer_client/world_section_scheduler.dart';
import 'package:totem_observer_client/world_visible_view.dart';
import 'world_debug_slice_test.dart' show DebugSliceTestConnection;

class HydrationConnection extends DebugSliceTestConnection {
  HydrationConnection() {
    registryTotal = 1000000;
    for (int i = 0; i < 8; i++) {
      names[i] = 'minecraft:test_$i';
    }
  }
  @override
  void requestWorldSection(int chunkX, int chunkZ, int sectionY) {}
  final requested = <int>[];
  Set<int> retained = {};
  bool pending = false;
  @override
  bool get worldRegistryPending => pending;
  @override
  void requestBlockState(int rawId) {
    final offset = (rawId ~/ 8) * 8;
    requested.add(offset);
    for (int i = 0; i < 8; i++) {
      names[offset + i] = 'minecraft:test_${offset + i}';
    }
  }

  @override
  void retainBlockStatePages(Set<int> offsets) {
    retained = Set.of(offsets);
    names.removeWhere((id, _) => !offsets.contains((id ~/ 8) * 8));
  }
}

void main() {
  testWidgets('visible snapshots hydrate only needed pages with no repeat', (
    tester,
  ) async {
    final connection = HydrationConnection()
      ..addSection(10, -3, 4, stateId: 65);
    final scheduler = WorldSectionScheduler(connection);
    final visible = WorldVisibleViewController(connection, scheduler);
    final hydration = WorldRegistryHydrator(connection, visible);
    await tester.pump(const Duration(milliseconds: 100));
    expect(connection.requested, [64]);
    expect(connection.retained, {0, 64});
    await tester.pump(const Duration(seconds: 1));
    expect(connection.requested, [64]);
    connection.addSection(10, -3, 4, stateId: 129);
    await tester.pump(const Duration(milliseconds: 100));
    expect(connection.requested, [64, 128]);
    expect(connection.names.containsKey(65), isFalse);
    connection.worldDimension = 'minecraft:the_nether';
    connection.addSection(10, -3, 4, stateId: 257);
    await tester.pump(const Duration(milliseconds: 100));
    expect(connection.requested, [64, 128]);
    hydration.dispose();
    visible.dispose();
    scheduler.dispose();
    connection.dispose();
  });

  testWidgets(
    'page budget is stable under hostile diversity and pending response',
    (tester) async {
      final connection = HydrationConnection()..addSection(10, -3, 4);
      final key = connection.completed.keys.single;
      connection.completed[key] = WorldSectionSnapshot(
        key: key,
        stateIds: List.generate(4096, (i) => (i + 1) * 8),
      );
      final scheduler = WorldSectionScheduler(connection);
      final visible = WorldVisibleViewController(connection, scheduler);
      final hydration = WorldRegistryHydrator(connection, visible);
      connection.pending = true;
      await tester.pump(const Duration(milliseconds: 100));
      expect(connection.requested, isEmpty);
      connection.pending = false;
      for (int i = 0; i < 300; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      expect(connection.retained.length, 256);
      expect(connection.requested.length, 255);
      expect(connection.requested.toSet().length, 255);
      expect(connection.names.length, 2048);
      hydration.dispose();
      connection.addSection(10, -3, 4, stateId: 999999);
      await tester.pump(const Duration(seconds: 1));
      expect(connection.requested.length, 255);
      visible.dispose();
      scheduler.dispose();
      connection.dispose();
    },
  );
}
