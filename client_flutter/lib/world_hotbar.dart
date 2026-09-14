import 'package:flutter/foundation.dart';

@immutable
class WorldHotbarStack {
  const WorldHotbarStack._(this.item, this.count);
  final String item;
  final int count;

  static WorldHotbarStack parse(Object? value) {
    final m = _map(value, {'item', 'count'});
    final item = m['item'];
    if (item is! String ||
        item.length > 256 ||
        !RegExp(r'^[a-z0-9_.-]+:[a-z0-9_./-]+$').hasMatch(item)) {
      throw const FormatException();
    }
    return WorldHotbarStack._(item, _integer(m['count'], 0, 999));
  }
}

/// Last server confirmation, not an inventory mutation or a live inventory stream.
@immutable
class WorldHotbarSnapshot {
  WorldHotbarSnapshot._(
    this.seq,
    this.sessionEpoch,
    this.subscriptionId,
    this.revision,
    this.dimension,
    this.outcome,
    this.selected,
    List<WorldHotbarStack>? slots,
  ) : slots = slots == null ? null : List.unmodifiable(slots);
  final int seq, sessionEpoch, subscriptionId, revision;
  final String dimension, outcome;
  final int? selected;
  final List<WorldHotbarStack>? slots;

  static WorldHotbarSnapshot parse(Object? value) {
    final m = _map(value, {
      'type',
      'protocol',
      'seq',
      'sessionEpoch',
      'subscriptionId',
      'revision',
      'dimension',
      'outcome',
      'selected',
      'slots',
    });
    final dimension = m['dimension'],
        outcome = m['outcome'],
        slots = m['slots'];
    if (m['type'] != 'world_hotbar' ||
        m['protocol'] is! int ||
        m['protocol'] != 1 ||
        dimension is! String ||
        dimension.length > 128 ||
        !RegExp(r'^[a-z0-9_.-]+:[a-z0-9_./-]+$').hasMatch(dimension) ||
        !{'snapshot', 'selected', 'denied'}.contains(outcome)) {
      throw const FormatException();
    }
    if (outcome == 'denied') {
      if (m['selected'] != null || slots != null) throw const FormatException();
    } else if (slots is! List || slots.length != 9) {
      throw const FormatException();
    }
    return WorldHotbarSnapshot._(
      _integer(m['seq'], 0, 999999999),
      _integer(m['sessionEpoch'], 1, 9007199254740991),
      _integer(m['subscriptionId'], 1, 999999999),
      _integer(m['revision'], 1, 999999999),
      dimension,
      outcome as String,
      outcome == 'denied' ? null : _integer(m['selected'], 0, 8),
      outcome == 'denied'
          ? null
          : (slots as List).map(WorldHotbarStack.parse).toList(growable: false),
    );
  }
}

Map<String, dynamic> _map(Object? value, Set<String> keys) {
  if (value is! Map<String, dynamic> ||
      value.length != keys.length ||
      !value.keys.every(keys.contains))
    throw const FormatException();
  return value;
}

int _integer(Object? value, int min, int max) {
  if (value is! int || value < min || value > max)
    throw const FormatException();
  return value;
}
