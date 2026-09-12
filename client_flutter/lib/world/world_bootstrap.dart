class WorldBootstrap {
  const WorldBootstrap({
    required this.protocol,
    required this.sessionEpoch,
    required this.dimension,
    required this.x,
    required this.y,
    required this.z,
    required this.yaw,
    required this.pitch,
    required this.gameTime,
    required this.defaultClockTime,
  });

  final int protocol;
  final int sessionEpoch;
  final String dimension;
  final double x;
  final double y;
  final double z;
  final double yaw;
  final double pitch;
  final int gameTime;
  final int defaultClockTime;

  static final RegExp _dimension = RegExp(r'^[a-z0-9_.-]+:[a-z0-9/._-]+$');

  factory WorldBootstrap.fromMessage(Map<String, dynamic> message) {
    final protocol = message['protocol'];
    final sessionEpoch = message['sessionEpoch'];
    final dimension = message['dimension'];
    final x = message['x'];
    final y = message['y'];
    final z = message['z'];
    final yaw = message['yaw'];
    final pitch = message['pitch'];
    final gameTime = message['gameTime'];
    final defaultClockTime = message['defaultClockTime'];
    if (message['type'] != 'world_bootstrap' ||
        message['play'] != false ||
        protocol != 1 ||
        sessionEpoch is! int ||
        sessionEpoch <= 0 ||
        dimension is! String ||
        !_dimension.hasMatch(dimension) ||
        x is! num ||
        y is! num ||
        z is! num ||
        yaw is! num ||
        pitch is! num ||
        gameTime is! int ||
        defaultClockTime is! int) {
      throw const FormatException();
    }
    final px = x.toDouble();
    final py = y.toDouble();
    final pz = z.toDouble();
    final viewYaw = yaw.toDouble();
    final viewPitch = pitch.toDouble();
    if (!px.isFinite ||
        !py.isFinite ||
        !pz.isFinite ||
        !viewYaw.isFinite ||
        !viewPitch.isFinite) {
      throw const FormatException();
    }
    return WorldBootstrap(
      protocol: protocol,
      sessionEpoch: sessionEpoch,
      dimension: dimension,
      x: px,
      y: py,
      z: pz,
      yaw: viewYaw,
      pitch: viewPitch,
      gameTime: gameTime,
      defaultClockTime: defaultClockTime,
    );
  }
}
