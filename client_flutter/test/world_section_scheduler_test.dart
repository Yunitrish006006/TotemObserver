import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/connection.dart';
import 'package:totem_observer_client/world_section_scheduler.dart';

class SchedulerTestConnection extends ObserverConnection {
  SchedulerTestConnection() {
    phase = ConnectionPhase.connected;
    playerAttached = true;
    bootstrapDimension = 'minecraft:overworld';
    bootstrapSubscriptionId = 1;
    bootstrapRevision = 1;
    bootstrapMinY = -64;
    bootstrapHeight = 384;
    bootstrapCenterChunkX = 10;
    bootstrapCenterChunkZ = -3;
    bootstrapRadius = 2;
    registryFingerprint =
        '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef';
    registryTotal = 16;
  }

  final requests = <WorldSectionCoordinate>[];
  final Map<WorldSectionKey, WorldSectionSnapshot> completed = {};
  final Set<WorldSectionKey> unavailable = {};

  @override
  bool get canRequestWorldSections => true;

  @override
  void requestWorldSection(int chunkX, int chunkZ, int sectionY) {
    requests.add(
      WorldSectionCoordinate(
        chunkX: chunkX,
        chunkZ: chunkZ,
        sectionY: sectionY,
      ),
    );
  }

  @override
  WorldSectionSnapshot? worldSection(int chunkX, int chunkZ, int sectionY) =>
      completed[_key(chunkX, chunkZ, sectionY)];

  @override
  bool worldSectionUnavailable(int chunkX, int chunkZ, int sectionY) =>
      unavailable.contains(_key(chunkX, chunkZ, sectionY));

  WorldSectionKey _key(int chunkX, int chunkZ, int sectionY) => WorldSectionKey(
    subscriptionId: bootstrapSubscriptionId,
    revision: bootstrapRevision,
    chunkX: chunkX,
    chunkZ: chunkZ,
    sectionY: sectionY,
  );

  void complete(WorldSectionCoordinate coordinate) {
    final key = _key(coordinate.chunkX, coordinate.chunkZ, coordinate.sectionY);
    completed[key] = WorldSectionSnapshot(
      key: key,
      stateIds: List<int>.filled(4096, 0),
    );
    notifyListeners();
  }

  void markUnavailable(WorldSectionCoordinate coordinate) {
    unavailable.add(
      _key(coordinate.chunkX, coordinate.chunkZ, coordinate.sectionY),
    );
    notifyListeners();
  }

  void advanceRevision() {
    bootstrapRevision++;
    notifyListeners();
  }
}

void main() {
  test(
    'queues a horizontal bootstrap window center-first and serializes work',
    () {
      final connection = SchedulerTestConnection();
      final scheduler = WorldSectionScheduler(connection);

      expect(scheduler.enqueueHorizontalWindow(4), 25);
      expect(connection.requests, hasLength(1));
      expect(
        connection.requests.single,
        const WorldSectionCoordinate(chunkX: 10, chunkZ: -3, sectionY: 4),
      );
      expect(scheduler.queuedCount, 24);

      connection.complete(connection.requests.first);
      expect(connection.requests, hasLength(2));
      expect(
        connection.requests[1],
        const WorldSectionCoordinate(chunkX: 10, chunkZ: -4, sectionY: 4),
      );
      expect(scheduler.queuedCount, 23);

      expect(
        scheduler.enqueue(
          const WorldSectionCoordinate(chunkX: 10, chunkZ: -4, sectionY: 4),
        ),
        isFalse,
        reason: 'active work must not be duplicated',
      );

      scheduler.dispose();
      connection.dispose();
    },
  );

  test('advances after an active section is unavailable', () {
    final connection = SchedulerTestConnection();
    final scheduler = WorldSectionScheduler(connection);

    expect(scheduler.enqueueHorizontalWindow(4), 25);
    final first = connection.requests.single;
    connection.markUnavailable(first);

    expect(connection.requests, hasLength(2));
    expect(
      connection.requests[1],
      const WorldSectionCoordinate(chunkX: 10, chunkZ: -4, sectionY: 4),
    );
    expect(scheduler.queuedCount, 23);
    expect(scheduler.active, connection.requests[1]);
    expect(scheduler.enqueue(first), isFalse);

    scheduler.dispose();
    connection.dispose();
  });

  test('drops queued work when the bootstrap revision changes', () {
    final connection = SchedulerTestConnection();
    final scheduler = WorldSectionScheduler(connection);

    scheduler.enqueueHorizontalWindow(4);
    expect(scheduler.isIdle, isFalse);
    expect(connection.requests, hasLength(1));

    connection.advanceRevision();
    expect(scheduler.isIdle, isTrue);
    expect(scheduler.active, isNull);
    expect(scheduler.queuedCount, 0);

    expect(
      scheduler.enqueue(
        const WorldSectionCoordinate(chunkX: 10, chunkZ: -3, sectionY: 4),
      ),
      isTrue,
    );
    expect(connection.requests, hasLength(2));

    scheduler.dispose();
    connection.dispose();
  });

  test('rejects requests outside horizontal or vertical bootstrap bounds', () {
    final connection = SchedulerTestConnection();
    final scheduler = WorldSectionScheduler(connection);

    expect(
      scheduler.enqueue(
        const WorldSectionCoordinate(chunkX: 13, chunkZ: -3, sectionY: 4),
      ),
      isFalse,
    );
    expect(
      scheduler.enqueue(
        const WorldSectionCoordinate(chunkX: 10, chunkZ: -3, sectionY: -5),
      ),
      isFalse,
    );
    expect(
      scheduler.enqueue(
        const WorldSectionCoordinate(chunkX: 10, chunkZ: -3, sectionY: 20),
      ),
      isFalse,
    );
    expect(connection.requests, isEmpty);

    scheduler.dispose();
    connection.dispose();
  });
}
