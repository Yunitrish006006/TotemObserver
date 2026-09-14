import 'dart:async';
import 'dart:convert';

import 'outbound_frame_pacer.dart';
import 'world_target_outline.dart';

import 'package:flutter/foundation.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

abstract interface class BridgeTransport {
  Stream<dynamic> get messages;
  Future<void> get ready;
  void send(String message);
  void close();
}

class SocketTransport implements BridgeTransport {
  SocketTransport(Uri uri)
    : _channel = WebSocketChannel.connect(
        uri,
        protocols: const ['totem-observer-account-v1'],
      );
  final WebSocketChannel _channel;
  @override
  Stream<dynamic> get messages => _channel.stream;
  @override
  Future<void> get ready => _channel.ready;
  @override
  void send(String message) => _channel.sink.add(message);
  @override
  void close() {
    unawaited(_channel.sink.close(1000));
  }
}

enum ConnectionPhase { offline, connecting, connected }

@immutable
class WorldSectionKey {
  const WorldSectionKey({
    required this.subscriptionId,
    required this.revision,
    required this.chunkX,
    required this.chunkZ,
    required this.sectionY,
  });

  final int subscriptionId;
  final int revision;
  final int chunkX;
  final int chunkZ;
  final int sectionY;

  @override
  bool operator ==(Object other) =>
      other is WorldSectionKey &&
      subscriptionId == other.subscriptionId &&
      revision == other.revision &&
      chunkX == other.chunkX &&
      chunkZ == other.chunkZ &&
      sectionY == other.sectionY;

  @override
  int get hashCode =>
      Object.hash(subscriptionId, revision, chunkX, chunkZ, sectionY);
}

@immutable
class WorldSectionSnapshot {
  WorldSectionSnapshot({required this.key, required List<int> stateIds})
    : stateIds = List.unmodifiable(stateIds) {
    if (this.stateIds.length != 4096) throw const FormatException();
  }

  final WorldSectionKey key;
  final List<int> stateIds;

  int stateAt(int localX, int localY, int localZ) {
    if (localX < 0 ||
        localX > 15 ||
        localY < 0 ||
        localY > 15 ||
        localZ < 0 ||
        localZ > 15) {
      throw RangeError('Section coordinates must be 0..15');
    }
    return stateIds[(localY * 16 + localZ) * 16 + localX];
  }
}

class _WorldSectionAssembly {
  _WorldSectionAssembly({
    required this.sequence,
    required this.key,
    required this.dimension,
    required this.registryFingerprint,
  });

  final int sequence;
  final WorldSectionKey key;
  final String dimension;
  final String registryFingerprint;
  final List<List<int>?> parts = List<List<int>?>.filled(4, null);
}

class ObserverConnection extends ChangeNotifier {
  ObserverConnection({BridgeTransport Function(Uri)? open})
    : _open = open ?? SocketTransport.new;
  final BridgeTransport Function(Uri) _open;
  BridgeTransport? _transport;
  OutboundFramePacer? _pacer;
  StreamSubscription<dynamic>? _subscription;
  Timer? _deadline, _heartbeat, _responseDeadline;
  Timer? _movementDeadline, _movementCooldown, _registryDeadline;
  Timer? _outlineDeadline, _outlineCooldown, _outlineExpiry;
  final _outlineClock = Stopwatch();
  int _outlineProtocol = 0, _pendingOutlineSequence = -1, _outlineGeometry = -1;
  WorldTargetOutlineSnapshot? _outline;
  bool get outlinePending => _pendingOutlineSequence >= 0;
  bool get canRequestTargetOutline =>
      canMove && hasWorldRegistry && _outlineProtocol == 1;
  WorldTargetOutlineSnapshot? get targetOutline {
    final value = _outline;
    if (value == null ||
        !canRequestTargetOutline ||
        worldWindowRefreshing ||
        _outlineClock.elapsedMilliseconds >= 500 ||
        _outlineGeometry != worldGeometryRevision ||
        value.x != worldX ||
        value.y != worldY ||
        value.z != worldZ ||
        value.yaw != worldYaw ||
        value.pitch != worldPitch)
      return null;
    return value;
  }

  Timer? _blockUseDeadline, _blockUseCooldown;
  Timer? _blockUsePreparation;
  final _blockUsePreparationClock = Stopwatch();
  bool get blockUsePreparing =>
      (_blockUsePreparation?.isActive ?? false) &&
      _blockUsePreparationClock.elapsedMilliseconds < 750;
  int _worldBlockUseProtocol = 0, _pendingBlockUseSequence = -1;
  bool get blockUsePending => _pendingBlockUseSequence >= 0;
  bool get canUseBlock => canMove && _worldBlockUseProtocol == 1;
  String? lastBlockUseOutcome;
  Timer? _sectionCooldown,
      _registryCooldown,
      _sectionDeadline,
      _bootstrapDeadline;
  bool _worldWindowRefreshNeeded = false;
  bool get worldWindowRefreshing =>
      _worldWindowRefreshNeeded || _pendingWorldBootstrapSequence >= 0;
  bool get canSendWorldSectionNow =>
      !blockUsePreparing &&
      !blockUsePending &&
      !outlinePending &&
      !(_sectionCooldown?.isActive ?? false) &&
      !worldWindowRefreshing &&
      _pendingWorldSection == null;
  int _worldMovementProtocol = 0, _pendingMovementSequence = -1;
  int _lastMovementServerTick = -1, _worldGeometryRevision = 0;
  int get worldGeometryRevision => _worldGeometryRevision;
  bool lastMovementApplied = false, worldOnGround = false;
  bool get canMove =>
      phase == ConnectionPhase.connected &&
      playerAttached &&
      _worldMovementProtocol == 1 &&
      hasWorldState &&
      hasWorldBootstrap &&
      worldDimension == bootstrapDimension;
  bool get movementPending => _pendingMovementSequence >= 0;

  int _generation = 0,
      _sequence = 0,
      _lastPong = -1,
      _identityProtocol = 0,
      _worldStateProtocol = 0,
      _worldBootstrapProtocol = 0,
      _worldRegistryProtocol = 0,
      _worldSectionProtocol = 0,
      _pendingWorldStateSequence = -1,
      _pendingWorldBootstrapSequence = -1,
      _pendingWorldRegistrySequence = -1,
      _pendingWorldRegistryOffset = -1;
  String? _login;
  String _expectedAccount = '';
  bool _hello = false, _disposed = false, _playerAdmissionAvailable = false;
  _WorldSectionAssembly? _pendingWorldSection;
  ConnectionPhase phase = ConnectionPhase.offline;
  String status = '尚未連線';
  String account = '';
  String playerUuid = '';
  String playerName = '';
  int sessionEpoch = 0;
  bool playerAttached = false;
  String worldDimension = '';
  double worldX = 0, worldY = 0, worldZ = 0, worldYaw = 0, worldPitch = 0;
  String bootstrapDimension = '';
  int bootstrapSubscriptionId = 0,
      bootstrapRevision = 0,
      bootstrapMinY = 0,
      bootstrapHeight = 0,
      bootstrapCenterChunkX = 0,
      bootstrapCenterChunkZ = 0,
      bootstrapRadius = 0;
  String registryFingerprint = '';
  int registryTotal = 0;
  final Map<int, String> _blockStateNames = {};
  final Map<WorldSectionKey, WorldSectionSnapshot> _worldSections = {};
  final Set<WorldSectionKey> _unavailableWorldSections = {};
  Set<WorldSectionKey>? _wantedWorldSections;
  static const maxCachedWorldSections = 43;
  int get cachedWorldEntryCount =>
      _worldSections.length + _unavailableWorldSections.length;

  void retainWorldSections(Set<WorldSectionKey> keys) {
    if (keys.length > maxCachedWorldSections ||
        keys.any(
          (key) =>
              key.subscriptionId != bootstrapSubscriptionId ||
              key.revision != bootstrapRevision,
        )) {
      throw ArgumentError('Current bounded world keys required');
    }
    _wantedWorldSections = Set.unmodifiable(keys);
    final before = cachedWorldEntryCount;
    _worldSections.removeWhere((key, _) => !keys.contains(key));
    _unavailableWorldSections.removeWhere((key) => !keys.contains(key));
    if (cachedWorldEntryCount != before) {
      _worldGeometryRevision++;
      _notify();
    }
  }

  void _boundWorldCache() {
    while (cachedWorldEntryCount > maxCachedWorldSections) {
      if (_unavailableWorldSections.isNotEmpty) {
        _unavailableWorldSections.remove(_unavailableWorldSections.first);
      } else {
        _worldSections.remove(_worldSections.keys.first);
      }
    }
  }

  void _tryRefreshWorldWindow() {
    if (_worldWindowRefreshNeeded) _requestWorldBootstrap();
  }

  int replies = 0;

  bool get hasWorldState => worldDimension.isNotEmpty;
  bool get hasWorldBootstrap =>
      bootstrapDimension.isNotEmpty && bootstrapSubscriptionId > 0;
  bool get hasWorldRegistry =>
      registryFingerprint.isNotEmpty && registryTotal > 0;
  bool get canRequestWorldSections =>
      _worldSectionProtocol == 1 && hasWorldBootstrap && hasWorldRegistry;
  bool get worldRegistryPending => _pendingWorldRegistrySequence >= 0;
  static const maxCachedBlockStates = 4096;
  int get cachedBlockStateCount => _blockStateNames.length;

  void retainBlockStatePages(Set<int> offsets) {
    if (offsets.length > 256 || offsets.any((v) => v < 0 || v % 8 != 0)) {
      throw ArgumentError('Bounded registry pages required');
    }
    final before = _blockStateNames.length;
    _blockStateNames.removeWhere((id, _) => !offsets.contains((id ~/ 8) * 8));
    if (_blockStateNames.length != before) {
      _worldGeometryRevision++;
      _notify();
    }
  }

  String? blockStateName(int rawId) => _blockStateNames[rawId];

  WorldSectionSnapshot? worldSection(int chunkX, int chunkZ, int sectionY) {
    if (!hasWorldBootstrap) return null;
    return _worldSections[WorldSectionKey(
      subscriptionId: bootstrapSubscriptionId,
      revision: bootstrapRevision,
      chunkX: chunkX,
      chunkZ: chunkZ,
      sectionY: sectionY,
    )];
  }

  bool worldSectionUnavailable(int chunkX, int chunkZ, int sectionY) {
    if (!hasWorldBootstrap) return false;
    return _unavailableWorldSections.contains(
      WorldSectionKey(
        subscriptionId: bootstrapSubscriptionId,
        revision: bootstrapRevision,
        chunkX: chunkX,
        chunkZ: chunkZ,
        sectionY: sectionY,
      ),
    );
  }

  Future<void> authenticate(
    String address,
    String username,
    String password, {
    bool register = false,
  }) async {
    disconnect();
    final uri = Uri.tryParse(address.trim());
    if (uri == null ||
        uri.scheme != 'ws' ||
        !['localhost', '127.0.0.1'].contains(uri.host) ||
        !uri.hasPort ||
        uri.port < 1 ||
        uri.port > 65535 ||
        uri.path != '/observer/bridge' ||
        uri.hasQuery ||
        uri.hasFragment ||
        uri.userInfo.isNotEmpty) {
      _fail('請輸入本機 Observer 連線位址');
      return;
    }
    if (!RegExp(r'^[a-z0-9_]{3,24}$').hasMatch(username) ||
        username == 'version' ||
        password.length < 12 ||
        password.length > 128) {
      _fail('帳號須為 3–24 個小寫英數或底線；密碼須為 12–128 個字元');
      return;
    }
    final generation = _generation;
    _expectedAccount = username;
    _login = jsonEncode({
      'type': register ? 'register' : 'login',
      'username': username,
      'password': password,
    });
    phase = ConnectionPhase.connecting;
    status = register ? '正在建立帳號…' : '正在登入…';
    _notify();
    _deadline = Timer(const Duration(seconds: 9), () => _fail('登入逾時，請重試'));
    try {
      final transport = _open(uri);
      _transport = transport;
      _pacer = OutboundFramePacer(
        write: transport.send,
        onError: () => _fail('連線已中斷'),
      );
      _subscription = transport.messages.listen(
        (data) {
          if (generation == _generation) _receive(data);
        },
        onError: (_) {
          if (generation == _generation) _fail('連線失敗，請確認伺服器已啟動');
        },
        onDone: () {
          if (generation == _generation) _fail('連線已結束，請重新登入');
        },
      );
      await transport.ready.timeout(const Duration(seconds: 5));
    } catch (_) {
      if (generation == _generation) _fail('無法連線，請確認位址與伺服器設定');
    }
  }

  void _receive(dynamic data) {
    try {
      if (data is! String || data.length > 8192) throw const FormatException();
      final message = jsonDecode(data) as Map<String, dynamic>;
      switch (message['type']) {
        case 'hello':
          if (_hello ||
              _login == null ||
              message['protocol'] != 1 ||
              message['authentication'] != true) {
            throw const FormatException();
          }
          final identityProtocol = message['playerIdentityProtocol'];
          if (identityProtocol != null && identityProtocol != 1) {
            throw const FormatException();
          }
          final playerAdmission = message['playerAdmission'];
          if (playerAdmission != null && playerAdmission is! bool) {
            throw const FormatException();
          }
          final worldStateProtocol = message['worldStateProtocol'];
          if (worldStateProtocol != null &&
              worldStateProtocol != 0 &&
              worldStateProtocol != 1) {
            throw const FormatException();
          }
          final worldBootstrapProtocol = message['worldBootstrapProtocol'];
          if (worldBootstrapProtocol != null &&
              worldBootstrapProtocol != 0 &&
              worldBootstrapProtocol != 1) {
            throw const FormatException();
          }
          final worldRegistryProtocol = message['worldRegistryProtocol'];
          if (worldRegistryProtocol != null &&
              worldRegistryProtocol != 0 &&
              worldRegistryProtocol != 1) {
            throw const FormatException();
          }
          final worldMovementProtocol = message['worldMovementProtocol'];
          if (worldMovementProtocol != null &&
              (worldMovementProtocol is! int ||
                  (worldMovementProtocol != 0 && worldMovementProtocol != 1)))
            throw const FormatException();
          if (worldMovementProtocol == 1 &&
              (playerAdmission != true ||
                  identityProtocol != 1 ||
                  worldBootstrapProtocol != 1 ||
                  worldStateProtocol != 1))
            throw const FormatException();
          _worldMovementProtocol = worldMovementProtocol == 1 ? 1 : 0;
          final blockUseProtocol = message['worldBlockUseProtocol'];
          if (blockUseProtocol != null &&
              (blockUseProtocol is! int ||
                  (blockUseProtocol != 0 && blockUseProtocol != 1))) {
            throw const FormatException();
          }
          if (blockUseProtocol == 1 &&
              (playerAdmission != true ||
                  identityProtocol != 1 ||
                  worldMovementProtocol != 1 ||
                  worldBootstrapProtocol != 1)) {
            throw const FormatException();
          }
          _worldBlockUseProtocol = blockUseProtocol == 1 ? 1 : 0;
          final outlineProtocol = message['worldTargetOutlineProtocol'];
          if (outlineProtocol != null &&
              (outlineProtocol is! int ||
                  (outlineProtocol != 0 && outlineProtocol != 1))) {
            throw const FormatException();
          }
          if (outlineProtocol == 1 &&
              (playerAdmission != true ||
                  identityProtocol != 1 ||
                  worldMovementProtocol != 1 ||
                  worldBootstrapProtocol != 1 ||
                  worldRegistryProtocol != 1)) {
            throw const FormatException();
          }
          _outlineProtocol = outlineProtocol == 1 ? 1 : 0;
          final worldSectionProtocol = message['worldSectionProtocol'];
          if (worldSectionProtocol != null &&
              worldSectionProtocol != 0 &&
              worldSectionProtocol != 1) {
            throw const FormatException();
          }
          if ((worldStateProtocol == 1 ||
                  worldBootstrapProtocol == 1 ||
                  worldRegistryProtocol == 1 ||
                  worldSectionProtocol == 1) &&
              (playerAdmission != true || identityProtocol != 1)) {
            throw const FormatException();
          }
          if (worldSectionProtocol == 1 &&
              (worldBootstrapProtocol != 1 || worldRegistryProtocol != 1)) {
            throw const FormatException();
          }
          _identityProtocol = identityProtocol == 1 ? 1 : 0;
          _playerAdmissionAvailable = playerAdmission == true;
          _worldStateProtocol = worldStateProtocol == 1 ? 1 : 0;
          _worldBootstrapProtocol = worldBootstrapProtocol == 1 ? 1 : 0;
          _worldRegistryProtocol = worldRegistryProtocol == 1 ? 1 : 0;
          _worldSectionProtocol = worldSectionProtocol == 1 ? 1 : 0;
          _hello = true;
          _pacer!.send(_login!);
          _login = null;
        case 'authenticated':
          if (!_hello ||
              phase != ConnectionPhase.connecting ||
              message['username'] != _expectedAccount ||
              message['play'] != false) {
            throw const FormatException();
          }
          if (_identityProtocol == 1) {
            final uuid = message['playerUuid'];
            final name = message['playerName'];
            final epoch = message['sessionEpoch'];
            final attached = message['playerAttached'];
            if (uuid is! String ||
                !RegExp(
                  r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
                ).hasMatch(uuid) ||
                name is! String ||
                !RegExp(r'^obs_[0-9a-f]{12}$').hasMatch(name) ||
                epoch is! int ||
                epoch <= 0 ||
                (attached != null && attached is! bool) ||
                (_playerAdmissionAvailable && attached != true)) {
              throw const FormatException();
            }
            playerUuid = uuid;
            playerName = name;
            sessionEpoch = epoch;
            playerAttached = attached == true;
          }
          _deadline?.cancel();
          account = _expectedAccount;
          phase = ConnectionPhase.connected;
          status = playerAttached
              ? '玩家已接入 Minecraft'
              : _identityProtocol == 1
              ? '已驗證玩家身分'
              : '已連線';
          _watchResponse();
          _heartbeat = Timer.periodic(
            const Duration(seconds: 5),
            (_) => ping(),
          );
          if (_worldBootstrapProtocol == 1 && playerAttached) {
            _requestWorldBootstrap();
          }
          if (_worldRegistryProtocol == 1 && playerAttached) {
            _requestWorldRegistry(0);
          }
          if (_worldStateProtocol == 1 && playerAttached) _requestWorldState();
          ping();
        case 'world_target_outline':
          if (!canRequestTargetOutline || !outlinePending)
            throw const FormatException();
          final outline = WorldTargetOutlineSnapshot.parse(
            message,
            registryTotal: registryTotal,
          );
          if (outline.seq != _pendingOutlineSequence ||
              outline.sessionEpoch != sessionEpoch ||
              outline.subscriptionId != bootstrapSubscriptionId ||
              outline.revision != bootstrapRevision ||
              outline.dimension != bootstrapDimension ||
              outline.registryFingerprint != registryFingerprint ||
              outline.serverTick < _lastMovementServerTick)
            throw const FormatException();
          final target = outline.target;
          if (target != null &&
              (target.y < bootstrapMinY ||
                  target.y >= bootstrapMinY + bootstrapHeight ||
                  (target.x - outline.x).abs() > 8 ||
                  (target.z - outline.z).abs() > 8 ||
                  (target.y - outline.eyeY).abs() > 8))
            throw const FormatException();
          _pendingOutlineSequence = -1;
          _outlineDeadline?.cancel();
          if (_outlineGeometry == worldGeometryRevision &&
              !worldWindowRefreshing &&
              target != null) {
            _outline = outline;
            _outlineClock
              ..reset()
              ..start();
            _outlineExpiry = Timer(const Duration(milliseconds: 500), () {
              clearTargetOutline();
              _notify();
            });
          }
          _tryRefreshWorldWindow();
          _notify();
        case 'world_block_use':
          const keys = {
            'type',
            'protocol',
            'seq',
            'sessionEpoch',
            'subscriptionId',
            'revision',
            'dimension',
            'outcome',
            'refreshRequired',
          };
          final outcome = message['outcome'];
          if (!canUseBlock ||
              !blockUsePending ||
              message.length != keys.length ||
              !message.keys.every(keys.contains) ||
              ![
                'protocol',
                'seq',
                'sessionEpoch',
                'subscriptionId',
                'revision',
              ].every((key) => message[key] is int) ||
              message['protocol'] != 1 ||
              message['seq'] != _pendingBlockUseSequence ||
              message['sessionEpoch'] != sessionEpoch ||
              message['subscriptionId'] != bootstrapSubscriptionId ||
              message['revision'] != bootstrapRevision ||
              message['dimension'] != bootstrapDimension ||
              !{
                'applied',
                'no_target',
                'unsupported',
                'denied',
              }.contains(outcome) ||
              message['refreshRequired'] is! bool ||
              message['refreshRequired'] != (outcome == 'applied')) {
            throw const FormatException();
          }
          _blockUseDeadline?.cancel();
          _pendingBlockUseSequence = -1;
          lastBlockUseOutcome = outcome as String;
          if (outcome == 'applied') {
            _worldGeometryRevision++;
            _worldSections.clear();
            _unavailableWorldSections.clear();
            _wantedWorldSections = null;
            _worldWindowRefreshNeeded = true;
            _tryRefreshWorldWindow();
          }
          _notify();
        case 'world_movement':
          const keys = {
            'type',
            'protocol',
            'seq',
            'sessionEpoch',
            'subscriptionId',
            'revision',
            'serverTick',
            'applied',
            'onGround',
            'dimension',
            'x',
            'y',
            'z',
            'yaw',
            'pitch',
          };
          final serverTick = message['serverTick'];
          final x = message['x'], y = message['y'], z = message['z'];
          final yaw = message['yaw'], pitch = message['pitch'];
          if (!canMove ||
              !movementPending ||
              message.length != keys.length ||
              !message.keys.every(keys.contains) ||
              ![
                'protocol',
                'seq',
                'sessionEpoch',
                'subscriptionId',
                'revision',
              ].every((key) => message[key] is int) ||
              message['protocol'] != 1 ||
              message['seq'] != _pendingMovementSequence ||
              message['sessionEpoch'] != sessionEpoch ||
              message['subscriptionId'] != bootstrapSubscriptionId ||
              message['revision'] != bootstrapRevision ||
              message['dimension'] != bootstrapDimension ||
              serverTick is! int ||
              serverTick < 0 ||
              serverTick <= _lastMovementServerTick ||
              message['applied'] is! bool ||
              message['onGround'] is! bool ||
              x is! num ||
              y is! num ||
              z is! num ||
              yaw is! num ||
              pitch is! num ||
              ![x, y, z, yaw, pitch].every((v) => v.toDouble().isFinite) ||
              x.abs() > 32000000 ||
              z.abs() > 32000000 ||
              y.abs() > 8192 ||
              pitch < -90 ||
              pitch > 90)
            throw const FormatException();
          _pendingMovementSequence = -1;
          _movementDeadline?.cancel();
          _lastMovementServerTick = serverTick;
          lastMovementApplied = message['applied'] as bool;
          worldOnGround = message['onGround'] as bool;
          worldX = x.toDouble();
          worldY = y.toDouble();
          worldZ = z.toDouble();
          worldYaw = yaw.toDouble();
          worldPitch = pitch.toDouble();
          if ((worldX / 16).floor() != bootstrapCenterChunkX ||
              (worldZ / 16).floor() != bootstrapCenterChunkZ) {
            _worldWindowRefreshNeeded = true;
          }
          _tryRefreshWorldWindow();
          _notify();
        case 'world_state':
          final seq = message['seq'];
          final epoch = message['sessionEpoch'];
          final dimension = message['dimension'];
          final x = message['x'];
          final y = message['y'];
          final z = message['z'];
          final yaw = message['yaw'];
          final pitch = message['pitch'];
          if (phase != ConnectionPhase.connected ||
              _worldStateProtocol != 1 ||
              !playerAttached ||
              message['protocol'] != 1 ||
              seq is! int ||
              seq != _pendingWorldStateSequence ||
              epoch != sessionEpoch ||
              dimension is! String ||
              !RegExp(r'^[a-z0-9_.-]+:[a-z0-9_./-]+$').hasMatch(dimension) ||
              x is! num ||
              y is! num ||
              z is! num ||
              yaw is! num ||
              pitch is! num ||
              !x.toDouble().isFinite ||
              !y.toDouble().isFinite ||
              !z.toDouble().isFinite ||
              !yaw.toDouble().isFinite ||
              !pitch.toDouble().isFinite ||
              pitch.toDouble() < -90 ||
              pitch.toDouble() > 90) {
            throw const FormatException();
          }
          _pendingWorldStateSequence = -1;
          worldDimension = dimension;
          worldX = x.toDouble();
          worldY = y.toDouble();
          worldZ = z.toDouble();
          worldYaw = yaw.toDouble();
          worldPitch = pitch.toDouble();
          _notify();
        case 'world_bootstrap':
          final seq = message['seq'];
          final epoch = message['sessionEpoch'];
          final subscriptionId = message['subscriptionId'];
          final revision = message['revision'];
          final dimension = message['dimension'];
          final minY = message['minY'];
          final height = message['height'];
          final centerChunkX = message['centerChunkX'];
          final centerChunkZ = message['centerChunkZ'];
          final radius = message['radius'];
          if (phase != ConnectionPhase.connected ||
              _worldBootstrapProtocol != 1 ||
              !playerAttached ||
              message['protocol'] != 1 ||
              seq is! int ||
              seq != _pendingWorldBootstrapSequence ||
              epoch != sessionEpoch ||
              subscriptionId is! int ||
              subscriptionId <= 0 ||
              revision is! int ||
              revision <= 0 ||
              dimension is! String ||
              !RegExp(r'^[a-z0-9_.-]+:[a-z0-9_./-]+$').hasMatch(dimension) ||
              minY is! int ||
              minY < -4096 ||
              minY > 4096 ||
              height is! int ||
              height <= 0 ||
              height > 4096 ||
              centerChunkX is! int ||
              centerChunkX < -2000000 ||
              centerChunkX > 2000000 ||
              centerChunkZ is! int ||
              centerChunkZ < -2000000 ||
              centerChunkZ > 2000000 ||
              radius is! int ||
              radius < 0 ||
              radius > 32) {
            throw const FormatException();
          }
          if (bootstrapSubscriptionId == 0) {
            if (revision != 1) throw const FormatException();
          } else if (subscriptionId == bootstrapSubscriptionId) {
            if (revision <= bootstrapRevision ||
                dimension != bootstrapDimension ||
                minY != bootstrapMinY ||
                height != bootstrapHeight ||
                radius != bootstrapRadius) {
              throw const FormatException();
            }
          } else if (subscriptionId <= bootstrapSubscriptionId ||
              revision != 1) {
            throw const FormatException();
          }
          _bootstrapDeadline?.cancel();
          _pendingWorldBootstrapSequence = -1;
          _worldWindowRefreshNeeded = false;
          _wantedWorldSections = null;
          _pendingWorldSection = null;
          _worldGeometryRevision++;
          _worldSections.clear();
          _unavailableWorldSections.clear();
          bootstrapSubscriptionId = subscriptionId;
          bootstrapRevision = revision;
          bootstrapDimension = dimension;
          bootstrapMinY = minY;
          bootstrapHeight = height;
          bootstrapCenterChunkX = centerChunkX;
          bootstrapCenterChunkZ = centerChunkZ;
          bootstrapRadius = radius;
          _notify();
        case 'world_registry':
          final seq = message['seq'];
          final epoch = message['sessionEpoch'];
          final fingerprint = message['fingerprint'];
          final offset = message['offset'];
          final total = message['total'];
          final states = message['states'];
          if (phase != ConnectionPhase.connected ||
              _worldRegistryProtocol != 1 ||
              !playerAttached ||
              message['protocol'] != 1 ||
              seq is! int ||
              seq != _pendingWorldRegistrySequence ||
              epoch != sessionEpoch ||
              fingerprint is! String ||
              !RegExp(r'^[0-9a-f]{64}$').hasMatch(fingerprint) ||
              offset is! int ||
              offset != _pendingWorldRegistryOffset ||
              offset < 0 ||
              total is! int ||
              total <= 0 ||
              total > 1000000 ||
              states is! List ||
              states.isEmpty ||
              states.length > 8 ||
              offset + states.length > total ||
              states.length != (total - offset < 8 ? total - offset : 8)) {
            throw const FormatException();
          }
          final statePattern = RegExp(
            r'^[a-z0-9_.-]+:[a-z0-9_./-]+(?:\[[a-z0-9_.,=:/-]+\])?$',
          );
          for (final state in states) {
            if (state is! String ||
                state.length > 512 ||
                !statePattern.hasMatch(state)) {
              throw const FormatException();
            }
          }
          if (registryFingerprint.isEmpty) {
            if (offset != 0) throw const FormatException();
            registryFingerprint = fingerprint;
            registryTotal = total;
          } else if (fingerprint != registryFingerprint ||
              total != registryTotal) {
            throw const FormatException();
          }
          _registryDeadline?.cancel();
          _pendingWorldRegistrySequence = -1;
          _pendingWorldRegistryOffset = -1;
          _worldGeometryRevision++;
          while (_blockStateNames.length + states.length >
              maxCachedBlockStates) {
            final oldestPage = (_blockStateNames.keys.first ~/ 8) * 8;
            _blockStateNames.removeWhere((id, _) => id ~/ 8 == oldestPage ~/ 8);
          }
          for (int i = 0; i < states.length; i++) {
            _blockStateNames[offset + i] = states[i] as String;
          }
          _notify();
        case 'world_section':
          final pending = _pendingWorldSection;
          final seq = message['seq'];
          final epoch = message['sessionEpoch'];
          final subscriptionId = message['subscriptionId'];
          final revision = message['revision'];
          final fingerprint = message['registryFingerprint'];
          final dimension = message['dimension'];
          final chunkX = message['chunkX'];
          final chunkZ = message['chunkZ'];
          final sectionY = message['sectionY'];
          final part = message['part'];
          final parts = message['parts'];
          final stateCount = message['stateCount'];
          final encoded = message['data'];
          if (phase != ConnectionPhase.connected ||
              _worldSectionProtocol != 1 ||
              !playerAttached ||
              pending == null ||
              message['protocol'] != 1 ||
              seq is! int ||
              seq != pending.sequence ||
              epoch != sessionEpoch ||
              subscriptionId != pending.key.subscriptionId ||
              revision != pending.key.revision ||
              subscriptionId != bootstrapSubscriptionId ||
              revision != bootstrapRevision ||
              fingerprint != pending.registryFingerprint ||
              fingerprint != registryFingerprint ||
              dimension != pending.dimension ||
              dimension != bootstrapDimension ||
              chunkX != pending.key.chunkX ||
              chunkZ != pending.key.chunkZ ||
              sectionY != pending.key.sectionY ||
              part is! int ||
              part < 0 ||
              part >= 4 ||
              parts != 4 ||
              stateCount != 1024 ||
              encoded is! String ||
              pending.parts[part] != null) {
            throw const FormatException();
          }
          final decoded = _decodeSectionPart(encoded, registryTotal);
          pending.parts[part] = decoded;
          if (pending.parts.every((value) => value != null)) {
            final stateIds = <int>[];
            for (final values in pending.parts) {
              stateIds.addAll(values!);
            }
            final snapshot = WorldSectionSnapshot(
              key: pending.key,
              stateIds: stateIds,
            );
            _unavailableWorldSections.remove(pending.key);
            _worldGeometryRevision++;
            if (_wantedWorldSections?.contains(pending.key) ?? true) {
              _worldSections[pending.key] = snapshot;
              _boundWorldCache();
            }
            _sectionDeadline?.cancel();
            _pendingWorldSection = null;
            _tryRefreshWorldWindow();
            _notify();
          }
        case 'world_section_unavailable':
          final pending = _pendingWorldSection;
          final seq = message['seq'];
          final epoch = message['sessionEpoch'];
          final subscriptionId = message['subscriptionId'];
          final revision = message['revision'];
          final fingerprint = message['registryFingerprint'];
          final dimension = message['dimension'];
          final chunkX = message['chunkX'];
          final chunkZ = message['chunkZ'];
          final sectionY = message['sectionY'];
          final reason = message['reason'];
          if (phase != ConnectionPhase.connected ||
              _worldSectionProtocol != 1 ||
              !playerAttached ||
              pending == null ||
              message['protocol'] != 1 ||
              seq is! int ||
              seq != pending.sequence ||
              epoch != sessionEpoch ||
              subscriptionId != pending.key.subscriptionId ||
              revision != pending.key.revision ||
              subscriptionId != bootstrapSubscriptionId ||
              revision != bootstrapRevision ||
              fingerprint != pending.registryFingerprint ||
              fingerprint != registryFingerprint ||
              dimension != pending.dimension ||
              dimension != bootstrapDimension ||
              chunkX != pending.key.chunkX ||
              chunkZ != pending.key.chunkZ ||
              sectionY != pending.key.sectionY ||
              reason != 'not_loaded' ||
              pending.parts.any((value) => value != null)) {
            throw const FormatException();
          }
          _pendingWorldSection = null;
          _worldGeometryRevision++;
          _worldSections.remove(pending.key);
          if (_wantedWorldSections?.contains(pending.key) ?? true) {
            _unavailableWorldSections.add(pending.key);
            _boundWorldCache();
          }
          _sectionDeadline?.cancel();
          _tryRefreshWorldWindow();
          _notify();
        case 'pong':
          final seq = message['seq'];
          if (phase != ConnectionPhase.connected ||
              seq is! int ||
              seq <= _lastPong ||
              seq >= _sequence) {
            throw const FormatException();
          }
          _lastPong = seq;
          replies++;
          _watchResponse();
          _notify();
        case 'auth_failed':
          _fail('帳號或密碼不正確，或目前無法建立帳號');
        case 'logged_out':
          disconnect();
        default:
          throw const FormatException();
      }
    } catch (_) {
      _fail('伺服器回應不相容，請重新連線');
    }
  }

  /// One immediate, read-only capture; callers own polling and rendering owns no requests.
  bool requestTargetOutline() {
    if (!canRequestTargetOutline ||
        outlinePending ||
        movementPending ||
        blockUsePending ||
        blockUsePreparing ||
        worldWindowRefreshing ||
        (_outlineCooldown?.isActive ?? false) ||
        !(_pacer?.canSendImmediately ?? false))
      return false;
    try {
      clearTargetOutline();
      _outlineGeometry = worldGeometryRevision;
      final seq = _sequence++;
      _pendingOutlineSequence = seq;
      _outlineCooldown = Timer(const Duration(milliseconds: 250), _notify);
      _outlineDeadline = Timer(
        const Duration(seconds: 5),
        () => _fail('目標回應逾時，連線已結束'),
      );
      _pacer!.send(
        jsonEncode({
          'type': 'world_target_outline',
          'protocol': 1,
          'seq': seq,
          'sessionEpoch': sessionEpoch,
          'subscriptionId': bootstrapSubscriptionId,
          'revision': bootstrapRevision,
          'dimension': bootstrapDimension,
          'registryFingerprint': registryFingerprint,
        }),
      );
      _notify();
      return true;
    } catch (_) {
      _fail('連線已中斷');
      return false;
    }
  }

  /// Clears display ownership without cancelling an already-sent wire request.
  void clearTargetOutline({bool notify = false}) {
    final changed = _outline != null || _outlineGeometry >= 0;
    _outline = null;
    _outlineGeometry = -1;
    _outlineExpiry?.cancel();
    _outlineClock.stop();
    if (notify && changed) _notify();
  }

  /// Sends an immediate use intent only after previous world work has drained.
  /// Callers own input/focus; false means no action was queued or sent.
  bool sendBlockUse() {
    if (!canUseBlock ||
        outlinePending ||
        blockUsePending ||
        movementPending ||
        worldWindowRefreshing ||
        _pendingWorldSection != null ||
        (_blockUseCooldown?.isActive ?? false) ||
        !(_pacer?.canSendImmediately ?? false))
      return false;
    try {
      final seq = _sequence++;
      clearTargetOutline();
      _pendingBlockUseSequence = seq;
      lastBlockUseOutcome = null;
      _blockUseCooldown = Timer(const Duration(milliseconds: 220), _notify);
      _blockUseDeadline = Timer(
        const Duration(seconds: 5),
        () => _fail('互動回應逾時，連線已結束'),
      );
      _pacer!.send(
        jsonEncode({
          'type': 'world_block_use',
          'protocol': 1,
          'seq': seq,
          'sessionEpoch': sessionEpoch,
          'subscriptionId': bootstrapSubscriptionId,
          'revision': bootstrapRevision,
          'dimension': bootstrapDimension,
        }),
      );
      _notify();
      return true;
    } catch (_) {
      _fail('連線已中斷');
      return false;
    }
  }

  /// Input owner briefly pauses new sections while flushing look and draining
  /// prior work. This lease expires even if the input owner stops sampling.
  bool prepareBlockUse() {
    if (!canUseBlock ||
        blockUsePending ||
        blockUsePreparing ||
        worldWindowRefreshing)
      return false;
    _blockUsePreparation?.cancel();
    _blockUsePreparationClock
      ..reset()
      ..start();
    _blockUsePreparation = Timer(const Duration(milliseconds: 750), _notify);
    return true;
  }

  void cancelBlockUsePreparation() {
    _blockUsePreparation?.cancel();
    _blockUsePreparation = null;
    _blockUsePreparationClock.stop();
  }

  /// Sends intent only. The response replaces authoritative state; no local XYZ prediction.
  bool sendMovement({
    required int strafe,
    required int forward,
    required double yaw,
    required double pitch,
    required bool jump,
  }) {
    if (!canMove ||
        outlinePending ||
        blockUsePending ||
        _worldWindowRefreshNeeded ||
        !(_pacer?.canSendImmediately ?? false) ||
        movementPending ||
        _pendingWorldBootstrapSequence >= 0 ||
        (_movementCooldown?.isActive ?? false))
      return false;
    if (strafe < -1 ||
        strafe > 1 ||
        forward < -1 ||
        forward > 1 ||
        !yaw.isFinite ||
        !pitch.isFinite)
      return false;
    final wrappedYaw = ((yaw + 180) % 360) - 180;
    try {
      final seq = _sequence++;
      if (strafe != 0 ||
          forward != 0 ||
          jump ||
          wrappedYaw != worldYaw ||
          pitch.clamp(-90, 90) != worldPitch) {
        clearTargetOutline();
      }
      _pendingMovementSequence = seq;
      _movementCooldown = Timer(const Duration(milliseconds: 100), () {});
      _movementDeadline = Timer(
        const Duration(seconds: 5),
        () => _fail('移動回應逾時，連線已結束'),
      );
      _pacer!.send(
        jsonEncode({
          'type': 'world_movement',
          'protocol': 1,
          'seq': seq,
          'sessionEpoch': sessionEpoch,
          'subscriptionId': bootstrapSubscriptionId,
          'revision': bootstrapRevision,
          'dimension': bootstrapDimension,
          'strafe': strafe,
          'forward': forward,
          'yaw': (wrappedYaw * 100).round(),
          'pitch': (pitch.clamp(-90, 90) * 100).round(),
          'jump': jump ? 1 : 0,
        }),
      );
      return true;
    } catch (_) {
      _fail('連線已中斷');
      return false;
    }
  }

  void _requestWorldState() {
    if (phase != ConnectionPhase.connected ||
        _worldStateProtocol != 1 ||
        !playerAttached ||
        _pendingWorldStateSequence >= 0) {
      return;
    }
    try {
      final seq = _sequence++;
      _pendingWorldStateSequence = seq;
      _pacer?.send(jsonEncode({'type': 'world_state', 'seq': seq}));
    } catch (_) {
      _fail('連線已中斷');
    }
  }

  void _requestWorldBootstrap() {
    if (phase != ConnectionPhase.connected ||
        _worldBootstrapProtocol != 1 ||
        !playerAttached ||
        _pendingWorldBootstrapSequence >= 0 ||
        outlinePending ||
        blockUsePending ||
        movementPending ||
        _pendingWorldSection != null) {
      return;
    }
    try {
      final seq = _sequence++;
      clearTargetOutline();
      _pendingWorldBootstrapSequence = seq;
      _bootstrapDeadline = Timer(
        const Duration(seconds: 5),
        () => _fail('世界視窗回應逾時，連線已結束'),
      );
      _pacer?.send(jsonEncode({'type': 'world_bootstrap', 'seq': seq}));
    } catch (_) {
      _fail('連線已中斷');
    }
  }

  void _requestWorldRegistry(int offset) {
    if (phase != ConnectionPhase.connected ||
        _worldRegistryProtocol != 1 ||
        !playerAttached ||
        offset < 0 ||
        (_registryCooldown?.isActive ?? false) ||
        _pendingWorldRegistrySequence >= 0) {
      return;
    }
    try {
      final seq = _sequence++;
      _pendingWorldRegistrySequence = seq;
      _pendingWorldRegistryOffset = offset;
      if (_worldMovementProtocol == 1) {
        _registryCooldown = Timer(const Duration(milliseconds: 300), () {});
      }
      _registryDeadline = Timer(
        const Duration(seconds: 5),
        () => _fail('方塊資料回應逾時，連線已結束'),
      );
      _pacer?.send(
        jsonEncode({'type': 'world_registry', 'seq': seq, 'offset': offset}),
      );
    } catch (_) {
      _fail('連線已中斷');
    }
  }

  void requestBlockState(int rawId) {
    if (!hasWorldRegistry ||
        rawId < 0 ||
        rawId >= registryTotal ||
        _blockStateNames.containsKey(rawId)) {
      return;
    }
    _requestWorldRegistry((rawId ~/ 8) * 8);
  }

  void requestWorldSection(int chunkX, int chunkZ, int sectionY) {
    if (!canRequestWorldSections ||
        !canSendWorldSectionNow ||
        phase != ConnectionPhase.connected ||
        !playerAttached ||
        _pendingWorldSection != null ||
        _pendingWorldBootstrapSequence >= 0 ||
        chunkX < -2000000 ||
        chunkX > 2000000 ||
        chunkZ < -2000000 ||
        chunkZ > 2000000 ||
        (chunkX - bootstrapCenterChunkX).abs() > bootstrapRadius ||
        (chunkZ - bootstrapCenterChunkZ).abs() > bootstrapRadius) {
      return;
    }
    final minSectionY = _sectionYForBlockY(bootstrapMinY);
    final maxSectionY = _sectionYForBlockY(bootstrapMinY + bootstrapHeight - 1);
    if (sectionY < minSectionY || sectionY > maxSectionY) return;
    final key = WorldSectionKey(
      subscriptionId: bootstrapSubscriptionId,
      revision: bootstrapRevision,
      chunkX: chunkX,
      chunkZ: chunkZ,
      sectionY: sectionY,
    );
    if (_worldSections.containsKey(key) ||
        _unavailableWorldSections.contains(key)) {
      return;
    }
    try {
      final seq = _sequence++;
      if (_worldMovementProtocol == 1) {
        _sectionCooldown = Timer(const Duration(milliseconds: 300), _notify);
      }
      _sectionDeadline = Timer(
        const Duration(seconds: 5),
        () => _fail('區塊回應逾時，連線已結束'),
      );
      _pendingWorldSection = _WorldSectionAssembly(
        sequence: seq,
        key: key,
        dimension: bootstrapDimension,
        registryFingerprint: registryFingerprint,
      );
      _pacer?.send(
        jsonEncode({
          'type': 'world_section',
          'seq': seq,
          'subscriptionId': bootstrapSubscriptionId,
          'revision': bootstrapRevision,
          'chunkX': chunkX,
          'chunkZ': chunkZ,
          'sectionY': sectionY,
        }),
      );
    } catch (_) {
      _fail('連線已中斷');
    }
  }

  void resyncWorld() => _requestWorldBootstrap();

  void ping() {
    if (phase != ConnectionPhase.connected) return;
    try {
      _pacer?.send(jsonEncode({'type': 'ping', 'seq': _sequence++}));
    } catch (_) {
      _fail('連線已中斷');
    }
    _notify();
  }

  static int _sectionYForBlockY(int y) => y >= 0 ? y ~/ 16 : -((-y + 15) ~/ 16);

  static List<int> _decodeSectionPart(String encoded, int registryTotal) {
    if (registryTotal <= 0 ||
        encoded.isEmpty ||
        encoded.length > 4096 ||
        encoded.length % 4 != 0 ||
        !RegExp(r'^[A-Za-z0-9+/]*={0,2}$').hasMatch(encoded)) {
      throw const FormatException();
    }
    final Uint8List bytes;
    try {
      bytes = base64Decode(encoded);
    } catch (_) {
      throw const FormatException();
    }
    final values = <int>[];
    int cursor = 0;
    while (cursor < bytes.length) {
      int value = 0;
      int shift = 0;
      int count = 0;
      int last = 0;
      while (true) {
        if (cursor >= bytes.length || count == 3) {
          throw const FormatException();
        }
        final next = bytes[cursor++];
        last = next & 0x7f;
        value |= last << shift;
        count++;
        if ((next & 0x80) == 0) break;
        shift += 7;
      }
      if ((count > 1 && last == 0) || value < 0 || value >= registryTotal) {
        throw const FormatException();
      }
      values.add(value);
      if (values.length > 1024) throw const FormatException();
    }
    if (values.length != 1024) throw const FormatException();
    return List.unmodifiable(values);
  }

  void _watchResponse() {
    _responseDeadline?.cancel();
    _responseDeadline = Timer(
      const Duration(seconds: 12),
      () => _fail('伺服器未回應，連線已結束'),
    );
  }

  void disconnect() {
    if (phase == ConnectionPhase.connected) {
      try {
        _pacer?.send('{"type":"logout"}');
      } catch (_) {}
    }
    _generation++;
    _worldGeometryRevision++;
    _movementDeadline?.cancel();
    clearTargetOutline();
    _outlineDeadline?.cancel();
    _outlineCooldown?.cancel();
    _pendingOutlineSequence = -1;
    _outlineProtocol = 0;
    _blockUseDeadline?.cancel();
    cancelBlockUsePreparation();
    _blockUseCooldown?.cancel();
    _pendingBlockUseSequence = -1;
    _worldBlockUseProtocol = 0;
    lastBlockUseOutcome = null;
    _registryDeadline?.cancel();
    _sectionDeadline?.cancel();
    _bootstrapDeadline?.cancel();
    _worldWindowRefreshNeeded = false;
    _wantedWorldSections = null;
    _registryCooldown?.cancel();
    _sectionCooldown?.cancel();
    _movementCooldown?.cancel();
    _pendingMovementSequence = -1;
    _lastMovementServerTick = -1;
    _worldMovementProtocol = 0;
    lastMovementApplied = false;
    worldOnGround = false;
    _deadline?.cancel();
    _heartbeat?.cancel();
    _responseDeadline?.cancel();
    unawaited(_subscription?.cancel());
    _subscription = null;
    _pacer?.close();
    _pacer = null;
    _transport?.close();
    _transport = null;
    _login = null;
    _hello = false;
    _expectedAccount = '';
    _identityProtocol = 0;
    _worldStateProtocol = 0;
    _worldBootstrapProtocol = 0;
    _worldRegistryProtocol = 0;
    _worldSectionProtocol = 0;
    _playerAdmissionAvailable = false;
    _pendingWorldStateSequence = -1;
    _pendingWorldBootstrapSequence = -1;
    _pendingWorldRegistrySequence = -1;
    _pendingWorldRegistryOffset = -1;
    _pendingWorldSection = null;
    _sequence = 0;
    _lastPong = -1;
    replies = 0;
    account = '';
    playerUuid = '';
    playerName = '';
    sessionEpoch = 0;
    playerAttached = false;
    worldDimension = '';
    worldX = 0;
    worldY = 0;
    worldZ = 0;
    worldYaw = 0;
    worldPitch = 0;
    bootstrapDimension = '';
    bootstrapSubscriptionId = 0;
    bootstrapRevision = 0;
    bootstrapMinY = 0;
    bootstrapHeight = 0;
    bootstrapCenterChunkX = 0;
    bootstrapCenterChunkZ = 0;
    bootstrapRadius = 0;
    registryFingerprint = '';
    registryTotal = 0;
    _blockStateNames.clear();
    _worldSections.clear();
    _unavailableWorldSections.clear();
    phase = ConnectionPhase.offline;
    status = '尚未連線';
    _notify();
  }

  void _fail(String message) {
    disconnect();
    status = message;
    _notify();
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    disconnect();
    super.dispose();
  }
}
