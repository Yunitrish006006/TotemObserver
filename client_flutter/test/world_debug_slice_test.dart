import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/connection.dart';
import 'package:totem_observer_client/world_debug_slice.dart';
import 'package:totem_observer_client/world_visible_view.dart';

class DebugSliceTestConnection extends ObserverConnection {
  DebugSliceTestConnection() {
    phase = ConnectionPhase.connected;
    playerAttached = true;
    worldDimension = 'minecraft:overworld';
    worldX = 163.2;
    worldY = 70.4;
    worldZ = -42.3;
    bootstrapDimension = 'minecraft:overworld';
    bootstrapSubscriptionId = 1;
    bootstrapRevision = 1;
    bootstrapMinY = -64;
    bootstrapHeight = 384;
    bootstrapCenterChunkX = 10;
    bootstrapCenterChunkZ = -3;
    bootstrapRadius = 1;
    registryFingerprint =
        '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef';
    registryTotal = 32;
  }

  final Map<WorldSectionKey, WorldSectionSnapshot> completed = {};
  final Set<WorldSectionKey> unavailable = {};
  final Map<int, String> names = {
    0: 'minecraft:air',
    7: 'minecraft:stone',
    11: 'minecraft:oak_planks',
  };

  @override
  bool get canRequestWorldSections => true;

  WorldSectionKey _key(int chunkX, int chunkZ, int sectionY) => WorldSectionKey(
    subscriptionId: bootstrapSubscriptionId,
    revision: bootstrapRevision,
    chunkX: chunkX,
    chunkZ: chunkZ,
    sectionY: sectionY,
  );

  @override
  WorldSectionSnapshot? worldSection(int chunkX, int chunkZ, int sectionY) =>
      completed[_key(chunkX, chunkZ, sectionY)];

  @override
  bool worldSectionUnavailable(int chunkX, int chunkZ, int sectionY) =>
      unavailable.contains(_key(chunkX, chunkZ, sectionY));

  @override
  String? blockStateName(int rawId) => names[rawId];

  void addSection(
    int chunkX,
    int chunkZ,
    int sectionY, {
    int stateId = 7,
    int? specialX,
    int? specialZ,
    int specialStateId = 11,
  }) {
    final key = _key(chunkX, chunkZ, sectionY);
    final states = List<int>.filled(4096, stateId);
    if (specialX != null && specialZ != null) {
      const localY = 6;
      states[(localY * 16 + specialZ) * 16 + specialX] = specialStateId;
    }
    completed[key] = WorldSectionSnapshot(key: key, stateIds: states);
  }

  void markUnavailable(int chunkX, int chunkZ, int sectionY) {
    unavailable.add(_key(chunkX, chunkZ, sectionY));
  }
}

VisibleWorldPlan testPlan() => VisibleWorldPlan(
  subscriptionId: 1,
  revision: 1,
  anchorSectionY: 4,
  targets: const [],
);

void main() {
  test('composes available section cells at the authoritative player Y', () {
    final connection = DebugSliceTestConnection();
    connection.addSection(
      10,
      -3,
      4,
      specialX: 3,
      specialZ: 5,
      specialStateId: 11,
    );

    final snapshot = WorldDebugSliceSnapshot.create(connection, testPlan());
    expect(snapshot, isNotNull);
    expect(snapshot!.blockY, 70);
    expect(snapshot.sectionY, 4);
    expect(snapshot.localY, 6);
    expect(snapshot.chunkCount, 3);
    expect(snapshot.size, 48);
    expect(snapshot.availableChunkCount, 1);
    expect(snapshot.unavailableChunkCount, 0);
    expect(snapshot.pendingChunkCount, 8);
    expect(snapshot.playerGridX, 19);
    expect(snapshot.playerGridZ, 21);
    expect(snapshot.stateAtGrid(19, 21), 11);
    expect(snapshot.stateAtGrid(16, 16), 7);
    expect(snapshot.stateAtGrid(0, 0), isNull);

    connection.dispose();
  });

  test('tracks unavailable chunks separately from pending chunks', () {
    final connection = DebugSliceTestConnection();
    connection.addSection(10, -3, 4);
    connection.markUnavailable(9, -4, 4);

    final snapshot = WorldDebugSliceSnapshot.create(connection, testPlan())!;
    expect(snapshot.availableChunkCount, 1);
    expect(snapshot.unavailableChunkCount, 1);
    expect(snapshot.pendingChunkCount, 7);
    expect(
      snapshot.unavailableChunks,
      contains(const WorldDebugChunkCoordinate(9, -4)),
    );

    connection.dispose();
  });

  test('rejects a stale visible-world plan', () {
    final connection = DebugSliceTestConnection();
    final stale = VisibleWorldPlan(
      subscriptionId: 1,
      revision: 2,
      anchorSectionY: 4,
      targets: const [],
    );

    expect(WorldDebugSliceSnapshot.create(connection, stale), isNull);

    connection.dispose();
  });

  test('palette uses registry names for air instead of assuming raw id zero', () {
    expect(
      WorldDebugSlicePalette.colorFor(19, 'minecraft:air'),
      WorldDebugSlicePalette.empty,
    );
    expect(
      WorldDebugSlicePalette.colorFor(19, 'minecraft:cave_air'),
      WorldDebugSlicePalette.empty,
    );
    expect(
      WorldDebugSlicePalette.colorFor(0, 'minecraft:stone'),
      isNot(WorldDebugSlicePalette.empty),
    );
  });

  testWidgets('debug slice renders partial cache and player marker safely', (
    tester,
  ) async {
    final connection = DebugSliceTestConnection();
    connection.addSection(10, -3, 4);
    connection.markUnavailable(9, -4, 4);

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Center(
            child: SizedBox(
              width: 320,
              child: WorldDebugSliceView(
                connection: connection,
                plan: testPlan(),
              ),
            ),
          ),
        ),
      ),
    );

    expect(find.text('偵錯世界切片：Y=70（raw BlockState）'), findsOneWidget);
    expect(find.text('切片區塊：可用 1 / 未載入 1 / 等待 7'), findsOneWidget);
    expect(find.byType(CustomPaint), findsWidgets);
    expect(tester.takeException(), isNull);

    connection.dispose();
  });
}
