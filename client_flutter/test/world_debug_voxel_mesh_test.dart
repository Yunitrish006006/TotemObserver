import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/connection.dart';
import 'package:totem_observer_client/world_debug_voxel_mesh.dart';

WorldSectionSnapshot section({
  int chunkX = 0,
  int chunkZ = 0,
  int sectionY = 0,
  int revision = 1,
  int subscriptionId = 1,
  Map<(int, int, int), int> cells = const {},
}) {
  final states = List<int>.filled(4096, 0);
  for (final entry in cells.entries) {
    final (x, y, z) = entry.key;
    states[(y * 16 + z) * 16 + x] = entry.value;
  }
  return WorldSectionSnapshot(
    key: WorldSectionKey(
      subscriptionId: subscriptionId,
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
      expectedKey: source.key,
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
      expectedKey: source.key,
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
        expectedKey: source.key,
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
        expectedKey: source.key,
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
      expectedKey: source.key,
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
      expectedKey: source.key,
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
      expectedKey: source.key,
      sectionAt: (_, _, _) => null,
      stateName: stateName,
    );

    expect(mesh.faceCount, 5);
    expect(mesh.unresolvedSourceCells, 1);
    expect(mesh.suppressedUnknownNeighborFaces, 1);
  });

  test('fluid is omitted instead of inventing cube geometry', () {
    final source = section(cells: {(8, 8, 8): 3});
    final mesh = WorldDebugVoxelMesher.buildSection(
      section: source,
      expectedKey: source.key,
      sectionAt: (_, _, _) => null,
      stateName: stateName,
    );

    expect(mesh.faceCount, 0);
    expect(mesh.unsupportedSourceCells, 1);
  });
  test('unsupported models and waterlogged terrain never become cubes', () {
    for (final canonical in [
      'minecraft:oak_stairs[facing=north,half=bottom,waterlogged=false]',
      'minecraft:stone_slab[type=bottom,waterlogged=false]',
      'minecraft:oak_fence[waterlogged=false]',
      'minecraft:glass',
      'minecraft:short_grass',
      'minecraft:oak_leaves',
      'minecraft:lava[level=0]',
      'minecraft:stone[waterlogged=true]',
      'example:stone',
    ]) {
      final source = section(cells: {(8, 8, 8): 1});
      final mesh = WorldDebugVoxelMesher.buildSection(
        section: source,
        expectedKey: source.key,
        sectionAt: (_, _, _) => null,
        stateName: (id) => id == 0 ? 'minecraft:air' : canonical,
      );
      expect(mesh.faces, isEmpty, reason: canonical);
      expect(mesh.unsupportedSourceCells, 1, reason: canonical);
    }
  });

  test(
    'source identity mismatch rejects before touching cache or registry',
    () {
      final source = section(cells: {(8, 8, 8): 1});
      for (final expected in [
        section(revision: 2),
        section(subscriptionId: 2),
        section(chunkX: 1),
        section(chunkZ: 1),
        section(sectionY: 1),
      ]) {
        final mesh = WorldDebugVoxelMesher.buildSection(
          section: source,
          expectedKey: expected.key,
          sectionAt: (_, _, _) => throw StateError('cache touched'),
          stateName: (_) => throw StateError('registry touched'),
        );
        expect(mesh.faces, isEmpty);
        expect(mesh.sourceKey, expected.key);
      }
    },
  );

  test('all six boundaries resolve negative world coordinates', () {
    final offsets = [
      (-1, 0, 0),
      (1, 0, 0),
      (0, -1, 0),
      (0, 1, 0),
      (0, 0, -1),
      (0, 0, 1),
    ];
    for (int i = 0; i < offsets.length; i++) {
      final (dx, dy, dz) = offsets[i];
      final x = dx < 0
          ? 0
          : dx > 0
          ? 15
          : 8;
      final y = dy < 0
          ? 0
          : dy > 0
          ? 15
          : 8;
      final z = dz < 0
          ? 0
          : dz > 0
          ? 15
          : 8;
      final source = section(
        chunkX: -2,
        chunkZ: -2,
        sectionY: -2,
        cells: {(x, y, z): 1},
      );
      for (final mismatch in [false, true]) {
        final adjacent = section(
          chunkX: -2 + dx,
          chunkZ: -2 + dz,
          sectionY: -2 + dy,
          subscriptionId: mismatch ? 2 : 1,
        );
        final mesh = WorldDebugVoxelMesher.buildSection(
          section: source,
          expectedKey: source.key,
          sectionAt: (_, _, _) => adjacent,
          stateName: stateName,
        );
        expect(mesh.faceCount, mismatch ? 5 : 6);
        expect(
          mesh.faces.any(
            (f) => f.direction == WorldVoxelFaceDirection.values[i],
          ),
          !mismatch,
        );
        expect(mesh.faces.first.blockX, -32 + x);
        expect(mesh.faces.first.blockY, -32 + y);
        expect(mesh.faces.first.blockZ, -32 + z);
      }
    }
  });

  test('checkerboard output is bounded and immutable', () {
    final cells = <(int, int, int), int>{};
    for (int y = 0; y < 16; y++) {
      for (int z = 0; z < 16; z++) {
        for (int x = 0; x < 16; x++) {
          if ((x + y + z).isEven) cells[(x, y, z)] = 1;
        }
      }
    }
    final source = section(cells: cells);
    final mesh = WorldDebugVoxelMesher.buildSection(
      section: source,
      expectedKey: source.key,
      sectionAt: (_, _, _) => null,
      stateName: stateName,
    );
    expect(mesh.faceCount, WorldDebugVoxelMesher.maxFaces);
    expect(mesh.truncated, isTrue);
    expect(() => mesh.faces.clear(), throwsUnsupportedError);
  });

  test('raw zero without canonical air never exposes a face', () {
    final source = section(cells: {(8, 8, 8): 1});
    final mesh = WorldDebugVoxelMesher.buildSection(
      section: source,
      expectedKey: source.key,
      sectionAt: (_, _, _) => null,
      stateName: (id) => id == 1 ? 'minecraft:stone' : null,
    );
    expect(mesh.faces, isEmpty);
    expect(mesh.suppressedUnknownNeighborFaces, 6);
  });
  test('wrong neighbor coordinates fail closed', () {
    final source = section(cells: {(15, 8, 8): 1});
    for (final wrong in [
      section(chunkX: 0),
      section(chunkX: 1, chunkZ: 1),
      section(chunkX: 1, sectionY: 1),
    ]) {
      final mesh = WorldDebugVoxelMesher.buildSection(
        section: source,
        expectedKey: source.key,
        sectionAt: (_, _, _) => wrong,
        stateName: stateName,
      );
      expect(mesh.faceCount, 5);
      expect(mesh.suppressedMissingNeighborFaces, 1);
    }
  });

  test('unsupported non-air neighbors never expose stone faces', () {
    for (final name in [
      'minecraft:glass',
      'minecraft:water[level=0]',
      'minecraft:oak_stairs[facing=north]',
    ]) {
      final source = section(cells: {(8, 8, 8): 1, (9, 8, 8): 3});
      final mesh = WorldDebugVoxelMesher.buildSection(
        section: source,
        expectedKey: source.key,
        sectionAt: (_, _, _) => null,
        stateName: (id) => id == 3 ? name : stateName(id),
      );
      expect(mesh.faceCount, 5);
      expect(
        mesh.faces.any((f) => f.direction == WorldVoxelFaceDirection.east),
        isFalse,
      );
    }
  });
}
