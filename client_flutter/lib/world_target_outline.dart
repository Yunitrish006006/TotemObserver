import 'package:flutter/foundation.dart';

@immutable
class WorldOutlineBox {
  const WorldOutlineBox._(
    this.minX,
    this.minY,
    this.minZ,
    this.maxX,
    this.maxY,
    this.maxZ,
  );
  final double minX, minY, minZ, maxX, maxY, maxZ;

  static WorldOutlineBox parse(Object? value) {
    if (value is! List || value.length != 6) throw const FormatException();
    final v = value.map((n) => _number(n, -1, 2)).toList(growable: false);
    if (v[0] >= v[3] || v[1] >= v[4] || v[2] >= v[5])
      throw const FormatException();
    return WorldOutlineBox._(v[0], v[1], v[2], v[3], v[4], v[5]);
  }
}

/// Vanilla selection shape, not a model, collision guarantee or interaction grant.
@immutable
class WorldOutlineTarget {
  WorldOutlineTarget._(
    this.x,
    this.y,
    this.z,
    this.face,
    this.rawId,
    List<WorldOutlineBox> boxes,
  ) : boxes = List.unmodifiable(boxes);
  final int x, y, z, rawId;
  final String face;
  final List<WorldOutlineBox> boxes;

  static WorldOutlineTarget parse(Object? value, int registryTotal) {
    final m = _map(value, {'x', 'y', 'z', 'face', 'rawId', 'boxes'});
    final boxes = m['boxes'];
    final face = m['face'];
    if (boxes is! List ||
        boxes.isEmpty ||
        boxes.length > 16 ||
        !{'down', 'up', 'north', 'south', 'west', 'east'}.contains(face))
      throw const FormatException();
    return WorldOutlineTarget._(
      _integer(m['x'], -32000000, 32000000),
      _integer(m['y'], -8192, 8192),
      _integer(m['z'], -32000000, 32000000),
      face as String,
      _integer(m['rawId'], 0, registryTotal - 1),
      boxes.map(WorldOutlineBox.parse).toList(growable: false),
    );
  }
}

/// A single immutable response. The connection owns request/session/camera binding.
@immutable
class WorldTargetOutlineSnapshot {
  const WorldTargetOutlineSnapshot._(
    this.seq,
    this.sessionEpoch,
    this.subscriptionId,
    this.revision,
    this.dimension,
    this.registryFingerprint,
    this.serverTick,
    this.x,
    this.y,
    this.z,
    this.yaw,
    this.pitch,
    this.eyeY,
    this.target,
  );
  final int seq, sessionEpoch, subscriptionId, revision, serverTick;
  final String dimension, registryFingerprint;
  final double x, y, z, yaw, pitch, eyeY;
  final WorldOutlineTarget? target;

  static WorldTargetOutlineSnapshot parse(
    Object? value, {
    required int registryTotal,
  }) {
    final m = _map(value, {
      'type',
      'protocol',
      'seq',
      'sessionEpoch',
      'subscriptionId',
      'revision',
      'dimension',
      'registryFingerprint',
      'serverTick',
      'x',
      'y',
      'z',
      'yaw',
      'pitch',
      'eyeY',
      'target',
    });
    final dimension = m['dimension'], fingerprint = m['registryFingerprint'];
    if (m['type'] != 'world_target_outline' ||
        m['protocol'] is! int ||
        m['protocol'] != 1 ||
        dimension is! String ||
        dimension.length > 128 ||
        !RegExp(r'^[a-z0-9_.-]+:[a-z0-9_./-]+$').hasMatch(dimension) ||
        fingerprint is! String ||
        !RegExp(r'^[0-9a-f]{64}$').hasMatch(fingerprint) ||
        registryTotal <= 0) {
      throw const FormatException();
    }
    return WorldTargetOutlineSnapshot._(
      _integer(m['seq'], 0, 999999999),
      _integer(m['sessionEpoch'], 1, 9007199254740991),
      _integer(m['subscriptionId'], 1, 999999999),
      _integer(m['revision'], 1, 999999999),
      dimension,
      fingerprint,
      _integer(m['serverTick'], 0, 2147483647),
      _number(m['x'], -32000000, 32000000),
      _number(m['y'], -8192, 8192),
      _number(m['z'], -32000000, 32000000),
      _number(m['yaw'], -double.maxFinite, double.maxFinite),
      _number(m['pitch'], -90, 90),
      _number(m['eyeY'], -8192, 8192),
      m['target'] == null
          ? null
          : WorldOutlineTarget.parse(m['target'], registryTotal),
    );
  }
}

Map<String, dynamic> _map(Object? value, Set<String> keys) {
  if (value is! Map<String, dynamic> ||
      value.length != keys.length ||
      !value.keys.every(keys.contains)) {
    throw const FormatException();
  }
  return value;
}

int _integer(Object? value, int min, int max) {
  if (value is! int || value < min || value > max)
    throw const FormatException();
  return value;
}

double _number(Object? value, double min, double max) {
  if (value is! num || !value.toDouble().isFinite || value < min || value > max)
    throw const FormatException();
  return value.toDouble();
}
