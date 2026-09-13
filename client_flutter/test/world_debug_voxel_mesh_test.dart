import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/connection.dart';
import 'package:totem_observer_client/world_block_state.dart';
import 'package:totem_observer_client/world_debug_voxel_mesh.dart';

WorldSectionSnapshot section({
  int chunkX = 0,
  int chunkZ = 0,
  int sectionY = 0,
  int revision = 1,
  Map<(int, int, int), int> cells = const {},
}) {
  final states = List<int>.filled(4096, 0);
  for (final entry in cells.entries) {
    final (x, y, z) = entry.key;
    states[(y * 16 + z) * 16 + x] = entry.value;
  }
  return WorldSectionSnapshot(
    key: WorldSectionKey(
      subscriptionId: 1,
      revision: revision,
      chunkX: chunkX,
      chunkZ: chunkZ,
      sectionY: sectionY,
    ),
    stateIds: states,
  );
}

String? stateName(int rawId) => switch (rawId) {
  0 => 'minecraft:air',
  1 => 'minecraft:stone',
  2 => null,
  3 => 'minecraft:water[level=0]',
  _ => null,
};

void main() {
  test('isolated known block emits six air-exposed faces', () {
    final source = section(cells: {(8, 8, 8): 1});
    final mesh = WorldDebugVoxelMesher.buildSection(
      section: source,
      sectionAt: (_, _, _) => null,
      stateName: stateName,
    );

    expect(mesh.faceCount, 6);
    expect(mesh.unresolvedSourceCells, 0);
    expect(mesh.suppressedUnknownNeighborFaces, 0);
    expect(mesh.suppressedMissingNeighborFaces, 0);
    expect(
      mesh.faces.map((face) => face.direction).toSet(),
      WorldVoxelFaceDirection.values.toSet(),
    );
    expect(mesh.faces.first.blockX, 8);
    expect(mesh.faces.first.blockY, 8);
    expect(mesh.faces.first.blockZ, 8);
    expect(mesh.faces.first.blockId, 'minecraft:stone');
  });

  test('adjacent known blocks suppress their shared internal faces', () {
    final source = section(cells: {(8, 8, 8): 1, (9, 8, 8): 1});
    final mesh = WorldDebugVoxelMesher.buildSection(
      section: source,
      sectionAt: (_, _, _) => null,
      stateName: stateName,
    );

    expect(mesh.faceCount, 10);
  });

  test(
    'missing neighbor section suppresses boundary face instead of guessing',
    () {
      final source = section(cells: {(15, 8, 8): 1});
      final mesh = WorldDebugVoxelMesher.buildSection(
        section: source,
        sectionAt: (_, _, _) => null,
        stateName: stateName,
      );

      expect(mesh.faceCount, 5);
      expect(mesh.suppressedMissingNeighborFaces, 1);
      expect(
        mesh.faces.any(
          (face) => face.direction == WorldVoxelFaceDirection.east,
        ),
        isFalse,
      );
    },
  );

  test(
    'matching neighboring section can expose a cross-section boundary face',
    () {
      final source = section(cells: {(15, 8, 8): 1});
      final east = section(chunkX: 1);
      final mesh = WorldDebugVoxelMesher.buildSection(
        section: source,
        sectionAt: (chunkX, chunkZ, sectionY) =>
            chunkX == 1 && chunkZ == 0 && sectionY == 0 ? east : null,
        stateName: stateName,
      );

      expect(mesh.faceCount, 6);
      expect(mesh.suppressedMissingNeighborFaces, 0);
      expect(
        mesh.faces.any(
          (face) => face.direction == WorldVoxelFaceDirection.east,
        ),
        isTrue,
      );
    },
  );

  test('stale neighboring section is rejected at the revision boundary', () {
    final source = section(cells: {(15, 8, 8): 1});
    final staleEast = section(chunkX: 1, revision: 2);
    final mesh = WorldDebugVoxelMesher.buildSection(
      section: source,
      sectionAt: (_, _, _) => staleEast,
      stateName: stateName,
    );

    expect(mesh.faceCount, 5);
    expect(mesh.suppressedMissingNeighborFaces, 1);
  });

  test('unknown source state never becomes guessed cube geometry', () {
    final source = section(cells: {(8, 8, 8): 2});
    final mesh = WorldDebugVoxelMesher.buildSection(
      section: source,
      sectionAt: (_, _, _) => null,
      stateName: stateName,
    );

    expect(mesh.faceCount, 0);
    expect(mesh.unresolvedSourceCells, 1);
  });

  test('unknown neighbor suppresses only the uncertain face', () {
    final source = section(cells: {(8, 8, 8): 1, (9, 8, 8): 2});
    final mesh = WorldDebugVoxelMesher.buildSection(
      section: source,
      sectionAt: (_, _, _) => null,
      stateName: stateName,
    );

    expect(mesh.faceCount, 5);
    expect(mesh.unresolvedSourceCells, 1);
    expect(mesh.suppressedUnknownNeighborFaces, 1);
  });

  test('known fluid is meshed only where exact air is adjacent', () {
    final source = section(cells: {(8, 8, 8): 3});
    final mesh = WorldDebugVoxelMesher.buildSection(
      section: source,
      sectionAt: (_, _, _) => null,
      stateName: stateName,
    );

    expect(mesh.faceCount, 6);
    expect(mesh.faces.first.blockId, 'minecraft:water');
    expect(mesh.faces.first.renderHint, WorldBlockRenderHint.fluid);
  });
}
