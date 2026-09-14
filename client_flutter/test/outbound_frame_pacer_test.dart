import 'dart:async';
import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/outbound_frame_pacer.dart';

void main() {
  test('background wakeup can send reentrantly and stops after close', () {
    late OutboundFramePacer pacer;
    late ManualPeriodicTimer timer;
    int wakes = 0;
    final writes = <String>[];
    pacer = OutboundFramePacer(
      write: writes.add,
      onError: () => fail('unexpected error'),
      createPeriodicTimer: (_, callback) =>
          timer = ManualPeriodicTimer(callback),
      onBackgroundAvailable: () {
        wakes++;
        expect(pacer.canSendBackgroundImmediately, isTrue);
        pacer.send('background-$wakes');
      },
    );
    for (int i = 0; i < 6; i++) pacer.send('initial-$i');
    timer.fire(1);
    expect(wakes, 1);
    expect(pacer.canSendBackgroundImmediately, isFalse);
    timer.fire(2);
    expect(wakes, 2);
    expect(writes.sublist(6), ['background-1', 'background-2']);
    pacer.close();
    timer.fire(3);
    expect(wakes, 2);
  });
  test(
    'missed timer callbacks replenish bounded elapsed credit and retain FIFO',
    () {
      final writes = <String>[];
      int backgroundWakeups = 0;
      late ManualPeriodicTimer timer;
      final pacer = OutboundFramePacer(
        write: writes.add,
        onError: () => fail('unexpected overflow'),
        onBackgroundAvailable: () => backgroundWakeups++,
        createPeriodicTimer: (_, callback) =>
            timer = ManualPeriodicTimer(callback),
      );
      for (int i = 0; i < 16; i++) pacer.send('$i');
      expect(writes.length, 8);
      // A suspended browser returns after six seconds. It gets eight tokens,
      // not one token and not a burst of one hundred queued messages.
      timer.fire(100);
      expect(writes, List.generate(16, (i) => '$i'));
      expect(pacer.canSendImmediately, isFalse);
      timer.fire(101);
      expect(pacer.canSendImmediately, isTrue);
      expect(pacer.canSendBackgroundImmediately, isFalse);
      pacer.send('fresh-input');
      expect(writes.last, 'fresh-input');
      timer.fire(104);
      expect(pacer.canSendBackgroundImmediately, isTrue);
      expect(backgroundWakeups, 1);
      pacer.send('background');
      expect(pacer.canSendBackgroundImmediately, isFalse);
      expect(pacer.canSendImmediately, isTrue);
      pacer.close();
      timer.fire(200);
      expect(backgroundWakeups, 1);
    },
  );

  testWidgets(
    'continuous fresh input and background work both receive bounded service',
    (tester) async {
      int now = 0, input = 0, background = 0;
      final times = <int>[];
      final pacer = OutboundFramePacer(
        write: (_) => times.add(now),
        onError: () => fail('overflow'),
      );
      // Poll background first to model scheduler notification order.
      for (int i = 0; i < 1000; i++) {
        if (pacer.canSendBackgroundImmediately) {
          pacer.send('world-data');
          background++;
        }
        if (i % 10 == 0) {
          expect(
            pacer.canSendImmediately,
            isTrue,
            reason: 'Input at $now must not starve',
          );
          pacer.send('fresh-input');
          input++;
        }
        now += 10;
        await tester.pump(const Duration(milliseconds: 10));
      }
      expect(input, 100);
      expect(background, greaterThan(50));
      for (final t in times) {
        expect(
          times.where((v) => v >= t && v <= t + 1000).length,
          lessThanOrEqualTo(25),
        );
      }
      pacer.close();
    },
  );
  testWidgets('mixed requests retain FIFO and bounded sliding-window rate', (
    tester,
  ) async {
    final writes = <(int, String)>[];
    int now = 0;
    final pacer = OutboundFramePacer(
      write: (s) => writes.add((now, s)),
      onError: () => fail('unexpected overflow'),
    );
    for (var i = 0; i < 16; i++) {
      pacer.send('$i');
    }
    expect(writes.length, 8);
    expect(pacer.canSendImmediately, isFalse);
    for (var i = 0; i < 100; i++) {
      now += 60;
      await tester.pump(const Duration(milliseconds: 60));
      pacer.send('${i + 16}');
    }
    for (final (timestamp, _) in writes) {
      expect(
        writes
            .where((v) => v.$1 >= timestamp && v.$1 <= timestamp + 1000)
            .length,
        lessThanOrEqualTo(25),
      );
    }
    expect(writes.map((v) => v.$2), List.generate(writes.length, (i) => '$i'));
    pacer.close();
  });

  testWidgets('overflow, oversized frames and write failures close once', (
    tester,
  ) async {
    for (var mode = 0; mode < 3; mode++) {
      int errors = 0, writes = 0;
      final pacer = OutboundFramePacer(
        write: (_) {
          if (mode == 2) throw StateError('closed socket');
          writes++;
        },
        onError: () => errors++,
      );
      if (mode == 0) {
        for (var i = 0; i < 17; i++) {
          pacer.send('$i');
        }
      } else {
        pacer.send(mode == 1 ? 'x' * 1025 : 'ping');
      }
      final count = writes;
      pacer.send('later');
      await tester.pump(const Duration(seconds: 1));
      expect(errors, 1);
      expect(writes, count);
      expect(pacer.canSendImmediately, isFalse);
      pacer.close();
    }
  });

  testWidgets(
    'close discards pending writes and idle refill restores capacity',
    (tester) async {
      final writes = <String>[];
      final pacer = OutboundFramePacer(
        write: writes.add,
        onError: () => fail('error'),
      );
      for (var i = 0; i < 8; i++) {
        pacer.send('$i');
      }
      await tester.pump(const Duration(milliseconds: 480));
      expect(pacer.canSendImmediately, isTrue);
      for (var i = 0; i < 9; i++) {
        pacer.send('next$i');
      }
      expect(writes.length, 16);
      pacer.close();
      await tester.pump(const Duration(seconds: 1));
      expect(writes.length, 16);
    },
  );
}

class ManualPeriodicTimer implements Timer {
  ManualPeriodicTimer(this.callback);
  final void Function(Timer) callback;
  @override
  int tick = 0;
  @override
  bool isActive = true;
  void fire(int elapsedTicks) {
    tick = elapsedTicks;
    if (isActive) callback(this);
  }

  @override
  void cancel() {
    isActive = false;
  }
}
