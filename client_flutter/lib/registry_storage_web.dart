import 'dart:async';
import 'dart:js_interop';
import 'package:web/web.dart' as web;
import 'registry_storage_contract.dart';

RegistryStorage createRegistryStorage() => _IndexedRegistryStorage();

/// One structured-clone record. No account, endpoint or session data is stored.
class _IndexedRegistryStorage implements RegistryStorage {
  Future<web.IDBDatabase> _open() {
    final result = Completer<web.IDBDatabase>();
    final request = web.window.indexedDB.open('totem-observer-registry-v1', 1);
    final timeout = Timer(const Duration(seconds: 2), () {
      if (!result.isCompleted)
        result.completeError(TimeoutException('Registry storage'));
    });
    request.onupgradeneeded = ((web.Event _) {
      (request.result as web.IDBDatabase).createObjectStore('cache');
    }).toJS;
    request.onsuccess = ((web.Event _) {
      final db = request.result as web.IDBDatabase;
      timeout.cancel();
      db.onversionchange = ((web.Event _) => db.close()).toJS;
      if (result.isCompleted) {
        db.close();
      } else {
        result.complete(db);
      }
    }).toJS;
    request.onerror = ((web.Event _) {
      timeout.cancel();
      if (!result.isCompleted)
        result.completeError(StateError('Registry storage unavailable'));
    }).toJS;
    return result.future;
  }

  Future<Object?> _transaction(Map<String, Object>? value) async {
    final db = await _open();
    final result = Completer<Object?>();
    try {
      final tx = db.transaction(
        'cache'.toJS,
        value == null ? 'readonly' : 'readwrite',
      );
      final store = tx.objectStore('cache');
      final request = value == null
          ? store.get('latest'.toJS)
          : store.put(value.jsify(), 'latest'.toJS);
      Object? loaded;
      request.onsuccess = ((web.Event _) {
        if (value == null) loaded = request.result.dartify();
      }).toJS;
      tx.oncomplete = ((web.Event _) {
        if (!result.isCompleted) result.complete(loaded);
      }).toJS;
      tx.onabort = ((web.Event _) {
        if (!result.isCompleted)
          result.completeError(StateError('Registry transaction aborted'));
      }).toJS;
      return await result.future.timeout(
        const Duration(seconds: 2),
        onTimeout: () {
          tx.abort();
          throw TimeoutException('Registry transaction');
        },
      );
    } finally {
      db.close();
    }
  }

  @override
  Future<Object?> read() => _transaction(null);
  @override
  Future<void> write(Map<String, Object> value) async {
    await _transaction(value);
  }
}
