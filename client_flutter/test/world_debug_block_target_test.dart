import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/connection.dart';
import 'package:totem_observer_client/world_debug_block_target.dart';
import 'package:totem_observer_client/world_debug_voxel_mesh.dart';

void main() {
  final names = <int, String>{
    7: 'minecraft:air',
    0: 'minecraft:stone',
    2: 'minecraft:water[level=0]',
    3: 'minecraft:oak_slab[type=bottom,waterlogged=false]',
    4: 'minecraft:glass',
    5: 'minecraft:stone[waterlogged=true]',
    6: 'minecraft:oak_leaves[persistent=true]',
  };
  Map<(int, int, int), int> cells = {};
  (int, int, int)? missing;
  int storedRevision = 1, storedSubscription = 1, reads = 0;
  WorldSectionSnapshot? sectionAt(int cx, int cz, int sy) {
    reads++;
    if ((cx, cz, sy) == missing) return null;
    final states = List.filled(4096, 7);
    for (final entry in cells.entries) {
      final (x, y, z) = entry.key;
      if ((x / 16).floor() != cx ||
          (z / 16).floor() != cz ||
          (y / 16).floor() != sy)
        continue;
      states[((y % 16) * 16 + z % 16) * 16 + x % 16] = entry.value;
    }
    return WorldSectionSnapshot(
      key: WorldSectionKey(
        subscriptionId: storedSubscription,
        revision: storedRevision,
        chunkX: cx,
        chunkZ: cz,
        sectionY: sy,
      ),
      stateIds: states,
    );
  }

  WorldDebugBlockTarget? cast({
    double x = 0.5,
    double y = 0.5,
    double z = 0.5,
    double yaw = 0,
    double pitch = 0,
    double reach = 6,
  }) => WorldDebugBlockRaycast.cast(
    eyeX: x,
    eyeY: y,
    eyeZ: z,
    yaw: yaw,
    pitch: pitch,
    reach: reach,
    subscriptionId: 1,
    revision: 1,
    sectionAt: sectionAt,
    stateName: (id) => names[id],
  );
  setUp(() {
    cells = {};
    missing = null;
    storedRevision = 1;
    storedSubscription = 1;
    reads = 0;
  });

  test('nearest debug block and entry face, raw zero is stone', () {
    cells = {(0, 0, 2): 0, (0, 0, 3): 0};
    final hit = cast()!;
    expect((hit.x, hit.y, hit.z), (0, 0, 2));
    expect(hit.face, WorldVoxelFaceDirection.north);
    expect(hit.distance, 1.5);
    expect(hit.stateId, 0);
  });
  test('all six Minecraft look directions', () {
    for (final (yaw, pitch, target, face) in [
      (0.0, 0.0, (0, 0, 2), WorldVoxelFaceDirection.north),
      (180.0, 0.0, (0, 0, -2), WorldVoxelFaceDirection.south),
      (90.0, 0.0, (-2, 0, 0), WorldVoxelFaceDirection.east),
      (-90.0, 0.0, (2, 0, 0), WorldVoxelFaceDirection.west),
      (0.0, 90.0, (0, -2, 0), WorldVoxelFaceDirection.up),
      (0.0, -90.0, (0, 2, 0), WorldVoxelFaceDirection.down),
    ]) {
      cells = {target: 0};
      expect(cast(yaw: yaw, pitch: pitch)?.face, face);
    }
  });
  test('negative chunk and vertical section boundaries retain exact key', () {
    cells = {(-17, 0, 0): 0};
    expect(cast(x: -15.5, yaw: 90)?.key.chunkX, -2);
    cells = {(0, 16, 0): 0};
    expect(cast(y: 15.5, pitch: -90)?.key.sectionY, 1);
  });
  test('missing and stale sections stop traversal before farther target', () {
    cells = {(0, 0, 17): 0};
    missing = (0, 1, 0);
    expect(cast(z: 15.5), isNull);
    missing = null;
    storedRevision = 2;
    expect(cast(z: 15.5), isNull);
    storedRevision = 1;
    storedSubscription = 2;
    expect(cast(z: 15.5), isNull);
  });
  test(
    'unknown fluid partial translucent cutout waterlogged never penetrated',
    () {
      for (final id in [99, 2, 3, 4, 5, 6]) {
        cells = {(0, 0, 1): id, (0, 0, 2): 0};
        expect(cast(), isNull, reason: 'unsupported state $id');
      }
    },
  );
  test(
    'reach is inclusive and bounded, malformed camera does no cache work',
    () {
      cells = {(0, 0, 6): 0};
      expect(cast(reach: 5.5), isNotNull);
      expect(cast(reach: 5.49), isNull);
      reads = 0;
      expect(cast(reach: 7), isNull);
      expect(cast(x: double.nan), isNull);
      expect(cast(pitch: 91), isNull);
      expect(cast(yaw: double.infinity), isNull);
      expect(reads, 0);
    },
  );
  test('inside a block and ambiguous corner return no invented face', () {
    cells = {(0, 0, 0): 0};
    expect(cast(), isNull);
    cells = {(2, 0, 2): 0};
    expect(cast(yaw: -45), isNull);
  });
  test('empty ray traversal has constant work bound', () {
    expect(cast(yaw: 13, pitch: 17), isNull);
    expect(reads, lessThanOrEqualTo(WorldDebugBlockRaycast.maxSteps));
  });

  test('ray along a boundary plane does not choose an arbitrary side', () {
    cells = {(0, 0, 2): 0};
    expect(cast(x: 0), isNull);
    expect(reads, 0);
  });
}
