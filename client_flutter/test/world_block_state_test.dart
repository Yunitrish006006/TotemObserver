import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/world_block_state.dart';

void main() {
  test('parses canonical block id and properties without losing values', () {
    final descriptor = WorldBlockStateDescriptor.fromCanonical(
      17,
      'minecraft:oak_stairs[facing=north,half=bottom,waterlogged=true]',
    );

    expect(descriptor.rawId, 17);
    expect(descriptor.blockId, 'minecraft:oak_stairs');
    expect(descriptor.namespace, 'minecraft');
    expect(descriptor.path, 'oak_stairs');
    expect(descriptor.properties['facing'], 'north');
    expect(descriptor.properties['half'], 'bottom');
    expect(descriptor.waterlogged, isTrue);
    expect(descriptor.renderHint, WorldBlockRenderHint.opaqueLike);
    expect(
      descriptor.classificationConfidence,
      WorldBlockClassificationConfidence.heuristic,
    );
  });

  test('air and vanilla fluids use exact classifications', () {
    final air = WorldBlockStateDescriptor.fromCanonical(
      5,
      'minecraft:cave_air',
    );
    final water = WorldBlockStateDescriptor.fromCanonical(
      9,
      'minecraft:water[level=4]',
    );
    final lava = WorldBlockStateDescriptor.fromCanonical(
      12,
      'minecraft:lava[level=0]',
    );
    final bubble = WorldBlockStateDescriptor.fromCanonical(
      13,
      'minecraft:bubble_column[drag=true]',
    );

    expect(air.isAir, isTrue);
    expect(
      air.classificationConfidence,
      WorldBlockClassificationConfidence.exact,
    );
    expect(water.renderHint, WorldBlockRenderHint.fluid);
    expect(water.fluidKind, WorldBlockFluidKind.water);
    expect(lava.fluidKind, WorldBlockFluidKind.lava);
    expect(bubble.fluidKind, WorldBlockFluidKind.water);
    expect(
      water.classificationConfidence,
      WorldBlockClassificationConfidence.exact,
    );
  });

  test('visual hints remain explicitly heuristic for model-like blocks', () {
    final leaves = WorldBlockStateDescriptor.fromCanonical(
      21,
      'minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]',
    );
    final glass = WorldBlockStateDescriptor.fromCanonical(
      22,
      'minecraft:blue_stained_glass',
    );
    final stone = WorldBlockStateDescriptor.fromCanonical(
      23,
      'minecraft:stone',
    );

    expect(leaves.renderHint, WorldBlockRenderHint.cutoutLike);
    expect(glass.renderHint, WorldBlockRenderHint.translucentLike);
    expect(stone.renderHint, WorldBlockRenderHint.opaqueLike);
    expect(
      leaves.classificationConfidence,
      WorldBlockClassificationConfidence.heuristic,
    );
    expect(
      glass.classificationConfidence,
      WorldBlockClassificationConfidence.heuristic,
    );
    expect(
      stone.classificationConfidence,
      WorldBlockClassificationConfidence.heuristic,
    );
  });

  test('missing or malformed registry entries stay unknown', () {
    final missing = WorldBlockStateDescriptor.fromCanonical(99, null);
    final malformed = WorldBlockStateDescriptor.fromCanonical(
      100,
      'minecraft:oak_stairs[waterlogged=]',
    );
    final duplicate = WorldBlockStateDescriptor.fromCanonical(
      101,
      'minecraft:test[a=1,a=2]',
    );

    for (final descriptor in [missing, malformed, duplicate]) {
      expect(descriptor.isKnown, isFalse);
      expect(descriptor.renderHint, WorldBlockRenderHint.unknown);
      expect(
        descriptor.classificationConfidence,
        WorldBlockClassificationConfidence.unknown,
      );
    }
  });

  test('raw id zero is never treated as air without the registry name', () {
    final unknownZero = WorldBlockStateDescriptor.fromCanonical(0, null);
    final stoneZero = WorldBlockStateDescriptor.fromCanonical(
      0,
      'minecraft:stone',
    );

    expect(unknownZero.isAir, isFalse);
    expect(stoneZero.isAir, isFalse);
    expect(stoneZero.renderHint, WorldBlockRenderHint.opaqueLike);
  });
}
