import 'package:flutter/foundation.dart';

enum WorldBlockRenderHint {
  air,
  fluid,
  cutoutLike,
  translucentLike,
  opaqueLike,
  unknown,
}

enum WorldBlockFluidKind { none, water, lava }

enum WorldBlockClassificationConfidence { exact, heuristic, unknown }

@immutable
class WorldBlockStateDescriptor {
  WorldBlockStateDescriptor._({
    required this.rawId,
    required this.canonical,
    required this.namespace,
    required this.path,
    required Map<String, String> properties,
    required this.renderHint,
    required this.fluidKind,
    required this.classificationConfidence,
  }) : properties = Map.unmodifiable(properties);

  final int rawId;
  final String? canonical;
  final String? namespace;
  final String? path;
  final Map<String, String> properties;
  final WorldBlockRenderHint renderHint;
  final WorldBlockFluidKind fluidKind;
  final WorldBlockClassificationConfidence classificationConfidence;

  String? get blockId =>
      namespace == null || path == null ? null : '$namespace:$path';
  bool get isKnown => blockId != null;
  bool get isAir => renderHint == WorldBlockRenderHint.air;
  bool get isFluid => renderHint == WorldBlockRenderHint.fluid;
  bool get waterlogged => properties['waterlogged'] == 'true';

  static final RegExp _canonicalPattern = RegExp(
    r'^([a-z0-9_.-]+):([a-z0-9_./-]+)(?:\[([a-z0-9_.,=:/-]+)\])?$',
  );

  static WorldBlockStateDescriptor fromCanonical(int rawId, String? canonical) {
    if (rawId < 0 || canonical == null) return _unknown(rawId, canonical);
    final match = _canonicalPattern.firstMatch(canonical);
    if (match == null) return _unknown(rawId, canonical);

    final namespace = match.group(1)!;
    final path = match.group(2)!;
    final propertySource = match.group(3);
    final properties = <String, String>{};
    if (propertySource != null) {
      for (final entry in propertySource.split(',')) {
        final separator = entry.indexOf('=');
        if (separator <= 0 || separator == entry.length - 1) {
          return _unknown(rawId, canonical);
        }
        final key = entry.substring(0, separator);
        final value = entry.substring(separator + 1);
        if (properties.containsKey(key)) return _unknown(rawId, canonical);
        properties[key] = value;
      }
    }

    final blockId = '$namespace:$path';
    if (_airBlocks.contains(blockId)) {
      return WorldBlockStateDescriptor._(
        rawId: rawId,
        canonical: canonical,
        namespace: namespace,
        path: path,
        properties: properties,
        renderHint: WorldBlockRenderHint.air,
        fluidKind: WorldBlockFluidKind.none,
        classificationConfidence: WorldBlockClassificationConfidence.exact,
      );
    }
    if (_waterBlocks.contains(blockId)) {
      return WorldBlockStateDescriptor._(
        rawId: rawId,
        canonical: canonical,
        namespace: namespace,
        path: path,
        properties: properties,
        renderHint: WorldBlockRenderHint.fluid,
        fluidKind: WorldBlockFluidKind.water,
        classificationConfidence: WorldBlockClassificationConfidence.exact,
      );
    }
    if (blockId == 'minecraft:lava') {
      return WorldBlockStateDescriptor._(
        rawId: rawId,
        canonical: canonical,
        namespace: namespace,
        path: path,
        properties: properties,
        renderHint: WorldBlockRenderHint.fluid,
        fluidKind: WorldBlockFluidKind.lava,
        classificationConfidence: WorldBlockClassificationConfidence.exact,
      );
    }

    final hint = _heuristicHint(path);
    return WorldBlockStateDescriptor._(
      rawId: rawId,
      canonical: canonical,
      namespace: namespace,
      path: path,
      properties: properties,
      renderHint: hint,
      fluidKind: WorldBlockFluidKind.none,
      classificationConfidence: WorldBlockClassificationConfidence.heuristic,
    );
  }

  static WorldBlockStateDescriptor _unknown(int rawId, String? canonical) =>
      WorldBlockStateDescriptor._(
        rawId: rawId,
        canonical: canonical,
        namespace: null,
        path: null,
        properties: const {},
        renderHint: WorldBlockRenderHint.unknown,
        fluidKind: WorldBlockFluidKind.none,
        classificationConfidence: WorldBlockClassificationConfidence.unknown,
      );

  static WorldBlockRenderHint _heuristicHint(String path) {
    if (_matchesAny(path, _translucentExact, _translucentSuffixes)) {
      return WorldBlockRenderHint.translucentLike;
    }
    if (_matchesAny(path, _cutoutExact, _cutoutSuffixes)) {
      return WorldBlockRenderHint.cutoutLike;
    }
    return WorldBlockRenderHint.opaqueLike;
  }

  static bool _matchesAny(
    String path,
    Set<String> exact,
    List<String> suffixes,
  ) {
    if (exact.contains(path)) return true;
    for (final suffix in suffixes) {
      if (path.endsWith(suffix)) return true;
    }
    return false;
  }

  static const Set<String> _airBlocks = {
    'minecraft:air',
    'minecraft:cave_air',
    'minecraft:void_air',
  };

  static const Set<String> _waterBlocks = {
    'minecraft:water',
    'minecraft:bubble_column',
  };

  static const Set<String> _translucentExact = {
    'glass',
    'tinted_glass',
    'ice',
    'frosted_ice',
    'honey_block',
    'slime_block',
  };

  static const List<String> _translucentSuffixes = [
    '_stained_glass',
    '_stained_glass_pane',
  ];

  static const Set<String> _cutoutExact = {
    'short_grass',
    'tall_grass',
    'fern',
    'large_fern',
    'dead_bush',
    'sugar_cane',
    'vine',
    'glow_lichen',
    'ladder',
    'chain',
    'iron_bars',
    'torch',
    'wall_torch',
    'redstone_torch',
    'redstone_wall_torch',
    'tripwire',
    'tripwire_hook',
  };

  static const List<String> _cutoutSuffixes = [
    '_leaves',
    '_sapling',
    '_flower',
    '_roots',
    '_mushroom',
    '_crop',
    '_rail',
    '_door',
    '_trapdoor',
    '_fence',
    '_fence_gate',
    '_wall',
    '_pane',
    '_sign',
    '_hanging_sign',
    '_banner',
    '_button',
    '_pressure_plate',
  ];
}
