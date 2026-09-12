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
  bool operator ==(Object other) =>
      other is ChunkKey &&
      other.dimension == dimension &&
      other.x == x &&
      other.z == z;

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

  bool contains(ChunkKey key) =>
      key.dimension == dimension &&
      key.x >= centerChunkX - radius &&
      key.x <= centerChunkX + radius &&
      key.z >= centerChunkZ - radius &&
      key.z <= centerChunkZ + radius;

  factory WorldWindow.fromMessage(Map<String, dynamic> message) {
    final protocol = message['protocol'];
    final sessionEpoch = message['sessionEpoch'];
    final dimension = message['dimension'];
    final centerChunkX = message['centerChunkX'];
    final centerChunkZ = message['centerChunkZ'];
    final radius = message['radius'];
    final revision = message['revision'];
    if (message['type'] != 'world_window' ||
        message['play'] != false ||
        protocol != 1 ||
        sessionEpoch is! int ||
        sessionEpoch <= 0 ||
        dimension is! String ||
        !_dimension.hasMatch(dimension) ||
        centerChunkX is! int ||
        centerChunkX < _minChunkCoordinate ||
        centerChunkX > _maxChunkCoordinate ||
        centerChunkZ is! int ||
        centerChunkZ < _minChunkCoordinate ||
        centerChunkZ > _maxChunkCoordinate ||
        radius is! int ||
        radius < 0 ||
        radius > _maxRadius ||
        revision is! int ||
        revision <= 0) {
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
}
