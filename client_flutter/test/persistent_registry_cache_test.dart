import 'dart:async';
import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/connection.dart';
import 'package:totem_observer_client/persistent_registry_cache.dart';
import 'package:totem_observer_client/registry_storage_contract.dart';
import 'world_registry_test.dart';

class MemoryDisk implements RegistryStorage {
  Object? value;
  bool broken = false;
  Completer<Object?>? pending;
  @override
  Future<Object?> read() async {
    if (broken) throw StateError('unavailable');
    return pending == null ? value : pending!.future;
  }

  @override
  Future<void> write(Map<String, Object> data) async {
    if (broken) throw StateError('quota');
    value = jsonDecode(jsonEncode(data));
  }
}

Future<(ObserverConnection, RegistryFakeTransport)> open(
  MemoryDisk disk,
) async {
  final socket = RegistryFakeTransport();
  final c = ObserverConnection(
    open: (_) => socket,
    registryCache: PersistentRegistryCache(storage: disk),
  );
  await c.authenticate(
    'ws://localhost:25580/observer/bridge',
    'alice',
    'local-test-password',
  );
  socket.receive(registryHello);
  socket.receive(registryAuthenticated);
  socket.receive(registryPage(seq: 0, offset: 0));
  socket.receive({'type': 'pong', 'seq': 1});
  return (c, socket);
}

void main() {
  testWidgets('blocked disk read expires and releases registry requests', (
    tester,
  ) async {
    final disk = MemoryDisk()..pending = Completer<Object?>();
    final (c, wire) = await open(disk);
    expect(c.worldRegistryPending, isTrue);
    await tester.pump(const Duration(seconds: 2));
    expect(c.worldRegistryPending, isFalse);
    c.requestBlockState(9);
    expect(jsonDecode(wire.sent.last)['offset'], 8);
    c.dispose();
    disk.pending!.complete(null);
    await tester.pump();
  });

  test(
    'an older overlapping restore cannot replace a newer fingerprint',
    () async {
      final disk = MemoryDisk()..pending = Completer<Object?>();
      final oldRead = disk.pending!;
      final cache = PersistentRegistryCache(storage: disk);
      final old = cache.restore(fingerprint, 24);
      disk.pending = null;
      final newer = await cache.restore('b' * 64, 24);
      expect(newer, isEmpty);
      cache.remember('b' * 64, 24, 8, List.filled(8, 'minecraft:stone'));
      oldRead.complete({
        'version': 1,
        'fingerprint': fingerprint,
        'total': 24,
        'pages': [
          {'offset': 8, 'states': List.filled(8, 'minecraft:dirt')},
        ],
      });
      expect(await old, isEmpty);
      expect(cache.page('b' * 64, 24, 8)!.first, 'minecraft:stone');
      expect(cache.page(fingerprint, 24, 8), isNull);
    },
  );

  testWidgets(
    'new client restores disk only after server fingerprint and skips page requests',
    (tester) async {
      final disk = MemoryDisk();
      final (first, wire) = await open(disk);
      await tester.pump();
      first.requestBlockState(9);
      wire.receive(registryPage(seq: 2, offset: 8));
      await tester.pump();
      first.dispose();
      final (second, secondWire) = await open(disk);
      await tester.pump();
      expect(second.blockStateName(9), 'minecraft:test_9');
      final count = secondWire.sent.length;
      second.requestBlockState(9);
      expect(secondWire.sent.length, count);
      second.retainBlockStatePages({0});
      expect(second.blockStateName(9), isNull);
      second.requestBlockState(9);
      expect(second.blockStateName(9), 'minecraft:test_9');
      expect(secondWire.sent.length, count);
      second.dispose();
    },
  );

  testWidgets(
    'corrupt or unavailable storage falls back without ending session',
    (tester) async {
      for (final variant in ['broken', 'mismatch', 'malformed']) {
        final disk = MemoryDisk()..broken = variant == 'broken';
        disk.value = {
          'version': 1,
          'fingerprint': variant == 'mismatch' ? 'b' * 64 : fingerprint,
          'total': 24,
          'pages': [
            {
              'offset': 8,
              'states': ['invalid'],
            },
          ],
        };
        final (c, wire) = await open(disk);
        await tester.pump();
        expect(c.phase, ConnectionPhase.connected);
        expect(c.blockStateName(9), isNull);
        c.requestBlockState(9);
        expect(jsonDecode(wire.sent.last)['offset'], 8);
        c.dispose();
      }
    },
  );

  testWidgets('late restore cannot populate a disconnected session', (
    tester,
  ) async {
    final disk = MemoryDisk()..pending = Completer<Object?>();
    final (c, _) = await open(disk);
    expect(c.worldRegistryPending, isTrue);
    c.disconnect();
    disk.pending!.complete({
      'version': 1,
      'fingerprint': fingerprint,
      'total': 24,
      'pages': [
        {
          'offset': 8,
          'states': List.generate(8, (i) => 'minecraft:test_${i + 8}'),
        },
      ],
    });
    await tester.pump();
    expect(c.cachedBlockStateCount, 0);
    expect(c.worldRegistryPending, isFalse);
    c.dispose();
  });

  test(
    'disk and memory retain at most 256 immutable pages across registries',
    () async {
      final disk = MemoryDisk();
      final cache = PersistentRegistryCache(storage: disk);
      await cache.restore(fingerprint, 4096);
      for (int offset = 0; offset < 4096; offset += 8) {
        cache.remember(
          fingerprint,
          4096,
          offset,
          List.generate(8, (i) => 'minecraft:test_${i + offset}'),
        );
      }
      await Future<void>.delayed(Duration.zero);
      expect((disk.value as Map)['pages'], hasLength(256));
      final restored = await PersistentRegistryCache(
        storage: disk,
      ).restore(fingerprint, 4096);
      expect(restored.length, 256);
      expect(restored.containsKey(0), isFalse);
      expect(() => restored.values.first.clear(), throwsUnsupportedError);
      expect(await cache.restore('b' * 64, 4096), isEmpty);
    },
  );
}
