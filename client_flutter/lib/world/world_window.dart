class ChunkKey {
  const ChunkKey({
    required this.dimension,
    required this.x,
    required this.z,
  });

  final String dimension;
  final int x;
  final int z;

  @override
  bool operator ==(Object other) {
    if (other is! ChunkKey) {
      return false;
    }
    final sameDimension = other.dimension == dimension;
    final sameX = other.x == x;
    final sameZ = other.z == z;
    return sameDimension && sameX && sameZ;
  }

  @override
  int get hashCode {
    return Object.hash(dimension, x, z);
  }
}

class WorldWindow {
  const WorldWindow({
    required this.protocol,
    required this.sessionEpoch,
    required this.dimension,
    required this.centerChunkX,
    required this.centerChunkZ,
    required this.radius,
    required this.revision,
  });

  final int protocol;
  final int sessionEpoch;
  final String dimension;
  final int centerChunkX;
  final int centerChunkZ;
  final int radius;
  final int revision;

  static const String _dimensionPattern =
      r'^[a-z0-9_.-]+:[a-z0-9/._-]+$';
  static final RegExp _dimension = RegExp(_dimensionPattern);
  static const int _minChunkCoordinate = -2147483648;
  static const int _maxChunkCoordinate = 2147483647;
  static const int _maxRadius = 8;

  int get chunkCount {
    final width = radius * 2 + 1;
    return width * width;
  }

  Iterable<ChunkKey> get chunks sync* {
    final minX = centerChunkX - radius;
    final maxX = centerChunkX + radius;
    final minZ = centerChunkZ - radius;
    final maxZ = centerChunkZ + radius;
    for (var z = minZ; z <= maxZ; z++) {
      for (var x = minX; x <= maxX; x++) {
        yield ChunkKey(dimension: dimension, x: x, z: z);
      }
    }
  }

  bool contains(ChunkKey key) {
    if (key.dimension != dimension) {
      return false;
    }
    final dx = (key.x - centerChunkX).abs();
    final dz = (key.z - centerChunkZ).abs();
    final insideX = dx <= radius;
    final insideZ = dz <= radius;
    return insideX && insideZ;
  }

  factory WorldWindow.fromMessage(Map<String, dynamic> message) {
    final protocol = message['protocol'];
    final sessionEpoch = message['sessionEpoch'];
    final dimension = message['dimension'];
    final centerChunkX = message['centerChunkX'];
    final centerChunkZ = message['centerChunkZ'];
    final radius = message['radius'];
    final revision = message['revision'];

    if (message['type'] != 'world_window') {
      throw const FormatException();
    }
    if (message['play'] != false) {
      throw const FormatException();
    }
    if (protocol != 1) {
      throw const FormatException();
    }
    if (sessionEpoch is! int) {
      throw const FormatException();
    }
    if (sessionEpoch <= 0) {
      throw const FormatException();
    }
    if (dimension is! String) {
      throw const FormatException();
    }
    if (!_dimension.hasMatch(dimension)) {
      throw const FormatException();
    }
    if (centerChunkX is! int) {
      throw const FormatException();
    }
    if (!_validChunkCoordinate(centerChunkX)) {
      throw const FormatException();
    }
    if (centerChunkZ is! int) {
      throw const FormatException();
    }
    if (!_validChunkCoordinate(centerChunkZ)) {
      throw const FormatException();
    }
    if (radius is! int) {
      throw const FormatException();
    }
    if (radius < 0) {
      throw const FormatException();
    }
    if (radius > _maxRadius) {
      throw const FormatException();
    }
    if (revision is! int) {
      throw const FormatException();
    }
    if (revision <= 0) {
      throw const FormatException();
    }

    return WorldWindow(
      protocol: protocol,
      sessionEpoch: sessionEpoch,
      dimension: dimension,
      centerChunkX: centerChunkX,
      centerChunkZ: centerChunkZ,
      radius: radius,
      revision: revision,
    );
  }

  static bool _validChunkCoordinate(int value) {
    final aboveMinimum = value >= _minChunkCoordinate;
    final belowMaximum = value <= _maxChunkCoordinate;
    return aboveMinimum && belowMaximum;
  }
}
