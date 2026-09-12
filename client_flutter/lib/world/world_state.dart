import 'world_bootstrap.dart';
import 'world_window.dart';

class WorldState {
  const WorldState._({required this.bootstrap, this.window});

  final WorldBootstrap bootstrap;
  final WorldWindow? window;

  factory WorldState.bootstrap(WorldBootstrap bootstrap) =>
      WorldState._(bootstrap: bootstrap);

  WorldState attachInitialWindow(WorldWindow next) {
    if (window != null ||
        next.sessionEpoch != bootstrap.sessionEpoch ||
        next.dimension != bootstrap.dimension ||
        next.revision != 1 ||
        next.centerChunkX != _chunkCoordinate(bootstrap.x) ||
        next.centerChunkZ != _chunkCoordinate(bootstrap.z)) {
      throw const FormatException();
    }
    return WorldState._(bootstrap: bootstrap, window: next);
  }

  String get dimension => bootstrap.dimension;
  double get x => bootstrap.x;
  double get y => bootstrap.y;
  double get z => bootstrap.z;
  double get yaw => bootstrap.yaw;
  double get pitch => bootstrap.pitch;
  int get gameTime => bootstrap.gameTime;
  int get defaultClockTime => bootstrap.defaultClockTime;
  int get requestedChunkCount => window?.chunkCount ?? 0;
  Iterable<ChunkKey> get requestedChunks => window?.chunks ?? const <ChunkKey>[];

  static int _chunkCoordinate(double blockCoordinate) =>
      blockCoordinate.floor() >> 4;
}
