import 'dart:async';
import 'registry_storage_contract.dart';
import 'registry_storage_stub.dart'
    if (dart.library.js_interop) 'registry_storage_web.dart'
    as platform;

/// Canonical-name content only; registry fingerprint does not cover visual flags.
class PersistentRegistryCache {
  PersistentRegistryCache({RegistryStorage? storage})
    : _storage = storage ?? platform.createRegistryStorage();
  static const maxPages = 256;
  final RegistryStorage? _storage;
  bool get available => _storage != null;
  final Map<int, List<String>> _pages = {};
  String _fingerprint = '';
  int _total = 0, _generation = 0;
  bool _writing = false, _dirty = false;

  Future<Map<int, List<String>>> restore(String fingerprint, int total) async {
    if (!available) return {};
    final generation = ++_generation;
    _fingerprint = fingerprint;
    _total = total;
    _pages.clear();
    try {
      final raw = await _storage!.read().timeout(const Duration(seconds: 2));
      if (generation != _generation) return {};
      if (raw is! Map ||
          raw.length != 4 ||
          raw['version'] != 1 ||
          raw['fingerprint'] != fingerprint ||
          raw['total'] != total)
        return {};
      final pages = raw['pages'];
      if (pages is! List || pages.length > maxPages) return {};
      final decoded = <int, List<String>>{};
      for (final page in pages) {
        if (page is! Map ||
            page.length != 2 ||
            page['offset'] is! int ||
            page['states'] is! List)
          return {};
        final offset = page['offset'] as int, states = page['states'] as List;
        if (!_valid(offset, total, states) || decoded.containsKey(offset))
          return {};
        decoded[offset] = List<String>.unmodifiable(states);
      }
      _pages.addAll(decoded);
      return Map.unmodifiable(decoded);
    } catch (_) {
      return {};
    }
  }

  static bool _valid(int offset, int total, List states) =>
      offset >= 0 &&
      offset % 8 == 0 &&
      offset < total &&
      states.length == (total - offset < 8 ? total - offset : 8) &&
      states.every(
        (v) =>
            v is String &&
            v.length <= 512 &&
            RegExp(
              r'^[a-z0-9_.-]+:[a-z0-9_./-]+(?:\[[a-z0-9_.,=:/-]+\])?$',
            ).hasMatch(v),
      );

  List<String>? page(String fingerprint, int total, int offset) {
    if (fingerprint != _fingerprint || total != _total) return null;
    final value = _pages.remove(offset);
    if (value != null) _pages[offset] = value;
    return value;
  }

  void remember(
    String fingerprint,
    int total,
    int offset,
    List<String> states,
  ) {
    if (fingerprint != _fingerprint ||
        total != _total ||
        !_valid(offset, total, states))
      return;
    _pages.remove(offset);
    _pages[offset] = List.unmodifiable(states);
    while (_pages.length > maxPages) {
      _pages.remove(_pages.keys.first);
    }
    _dirty = true;
    if (!_writing) unawaited(_flush());
  }

  Future<void> _flush() async {
    _writing = true;
    while (_dirty) {
      _dirty = false;
      final value = <String, Object>{
        'version': 1,
        'fingerprint': _fingerprint,
        'total': _total,
        'pages': _pages.entries
            .map((p) => <String, Object>{'offset': p.key, 'states': p.value})
            .toList(growable: false),
      };
      try {
        await _storage!.write(value);
      } catch (_) {
        /* Optional cache only. */
      }
    }
    _writing = false;
  }
}
