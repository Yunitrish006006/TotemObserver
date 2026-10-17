import 'package:flutter_test/flutter_test.dart';
import 'package:totem_observer_client/outbound_frame_pacer.dart';

void main() {
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
