import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/connection.dart';
import 'package:totem_observer_client/world_section_scheduler.dart';
import 'package:totem_observer_client/world_visible_view.dart';

class VisibleViewTestConnection extends ObserverConnection {
  VisibleViewTestConnection({double y = 70}) {
    phase = ConnectionPhase.connected;
    playerAttached = true;
    worldDimension = 'minecraft:overworld';
    worldY = y;
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
  test('policy budgets 43 sections and prioritizes the local volume', () {
    final connection = VisibleViewTestConnection();
    final plan = WorldVisibleViewPolicy.create(connection);

    expect(plan, isNotNull);
    expect(plan!.anchorSectionY, 4);
    expect(plan.targets, hasLength(43));
    expect(
      plan.targets[0],
      const WorldSectionCoordinate(chunkX: 10, chunkZ: -3, sectionY: 4),
    );
    expect(
      plan.targets[9],
      const WorldSectionCoordinate(chunkX: 10, chunkZ: -3, sectionY: 3),
    );
    expect(
      plan.targets[18],
      const WorldSectionCoordinate(chunkX: 10, chunkZ: -3, sectionY: 5),
    );
    expect(
      plan.targets[27],
      const WorldSectionCoordinate(chunkX: 10, chunkZ: -5, sectionY: 4),
    );
    expect(plan.targets.toSet(), hasLength(43));

    connection.dispose();
  });

  test('policy omits the lower layer at the dimension floor', () {
    final connection = VisibleViewTestConnection(y: -64);
    final plan = WorldVisibleViewPolicy.create(connection);

    expect(plan, isNotNull);
    expect(plan!.anchorSectionY, -4);
    expect(plan.targets, hasLength(34));
    expect(plan.targets.where((target) => target.sectionY == -5), isEmpty);
    expect(
      plan.targets.where((target) => target.sectionY == -3),
      hasLength(9),
    );

    connection.dispose();
  });

  test('policy refuses mismatched world and bootstrap dimensions', () {
    final connection = VisibleViewTestConnection();
    connection.worldDimension = 'minecraft:the_nether';

    expect(WorldVisibleViewPolicy.create(connection), isNull);

    connection.dispose();
  });

  test('controller fills the scheduler and tracks resolved progress', () {
    final connection = VisibleViewTestConnection();
    final scheduler = WorldSectionScheduler(connection);
    final controller = WorldVisibleViewController(connection, scheduler);

    expect(controller.hasPlan, isTrue);
    expect(controller.targetCount, 43);
    expect(controller.resolvedCount, 0);
    expect(connection.requests, hasLength(1));
    expect(scheduler.queuedCount, 42);
    expect(
      connection.requests.single,
      const WorldSectionCoordinate(chunkX: 10, chunkZ: -3, sectionY: 4),
    );

    connection.markUnavailable(connection.requests.first);
    expect(controller.unavailableCount, 1);
    expect(controller.resolvedCount, 1);
    expect(connection.requests, hasLength(2));

    connection.complete(connection.requests[1]);
    expect(controller.availableCount, 1);
    expect(controller.unavailableCount, 1);
    expect(controller.resolvedCount, 2);
    expect(connection.requests, hasLength(3));

    controller.dispose();
    scheduler.dispose();
    connection.dispose();
  });

  test('new bootstrap revision resets progress and replans the view', () {
    final connection = VisibleViewTestConnection();
    final scheduler = WorldSectionScheduler(connection);
    final controller = WorldVisibleViewController(connection, scheduler);

    connection.markUnavailable(connection.requests.first);
    expect(controller.resolvedCount, 1);
    final beforeRevision = connection.requests.length;

    connection.advanceRevision();
    expect(controller.plan!.revision, 2);
    expect(controller.resolvedCount, 0);
    expect(connection.requests.length, beforeRevision + 1);
    expect(scheduler.queuedCount, 42);

    controller.dispose();
    scheduler.dispose();
    connection.dispose();
  });
}
