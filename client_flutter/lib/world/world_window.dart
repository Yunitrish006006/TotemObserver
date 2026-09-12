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
    if (other is! ChunkKey) return false;
    return other.dimension == dimension && other.x == x && other.z == z;
  }

  @override
  int get hashCode => Object.hash(dimension, x, z);
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

  static final RegExp _dimension = RegExp(r'^[a-z0-9_.-]+:[a-z0-9/._-]+$');
  static const int _minChunkCoordinate = -2147483648;
  static const int _maxChunkCoordinate = 2147483647;
  static const int _maxRadius = 8;

  int get chunkCount {
    final width = radius * 2 + 1;
    return width * width;
  }

  Iterable<ChunkKey> get chunks sync* {
    for (var z = centerChunkZ - radius; z <= centerChunkZ + radius; z++) {
      for (var x = centerChunkX - radius; x <= centerChunkX + radius; x++) {
        yield ChunkKey(dimension: dimension, x: x, z: z);
      }
    }
  }

  bool contains(ChunkKey key) {
    if (key.dimension != dimension) return false;
    final dx = (key.x - centerChunkX).abs();
    final dz = (key.z - centerChunkZ).abs();
    return dx <= radius && dz <= radius;
  }

  factory WorldWindow.fromMessage(Map<String, dynamic> message) {
    final protocol = message['protocol'];
    final sessionEpoch = message['sessionEpoch'];
    final dimension = message['dimension'];
    final centerChunkX = message['centerChunkX'];
    final centerChunkZ = message['centerChunkZ'];
    final radius = message['radius'];
    final revision = message['revision'];

    if (message['type'] != 'world_window' || message['play'] != false) {
      throw const FormatException();
    }
    if (protocol != 1 || sessionEpoch is! int || sessionEpoch <= 0) {
      throw const FormatException();
    }
    if (dimension is! String || !_dimension.hasMatch(dimension)) {
      throw const FormatException();
    }
    if (centerChunkX is! int || !_validChunkCoordinate(centerChunkX)) {
      throw const FormatException();
    }
    if (centerChunkZ is! int || !_validChunkCoordinate(centerChunkZ)) {
      throw const FormatException();
    }
    if (radius is! int || radius < 0 || radius > _maxRadius) {
      throw const FormatException();
    }
    if (revision is! int || revision <= 0) {
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

  static bool _validChunkCoordinate(int value) =>
      value >= _minChunkCoordinate && value <= _maxChunkCoordinate;
}
