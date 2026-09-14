import 'package:flutter/foundation.dart';

enum WorldDestroyAction { start, hold, cancel }

/// Immutable server confirmation. Progress never mutates the local world or inventory.
@immutable
class WorldDestroySnapshot {
  const WorldDestroySnapshot._(
    this.seq,
    this.sessionEpoch,
    this.subscriptionId,
    this.revision,
    this.dimension,
    this.operation,
    this.action,
    this.outcome,
    this.progress,
    this.worldMayHaveChanged,
    this.refreshRequired,
  );
  final int seq, sessionEpoch, subscriptionId, revision, operation;
  final String dimension, outcome;
  final WorldDestroyAction action;
  final double progress;
  final bool worldMayHaveChanged, refreshRequired;

  static WorldDestroySnapshot parse(Object? value) {
    const keys = {
      'type',
      'protocol',
      'seq',
      'sessionEpoch',
      'subscriptionId',
      'revision',
      'dimension',
      'operation',
      'action',
      'outcome',
      'progress',
      'worldMayHaveChanged',
      'refreshRequired',
    };
    if (value is! Map<String, dynamic> ||
        value.length != keys.length ||
        !value.keys.every(keys.contains)) {
      throw const FormatException();
    }
    final dimension = value['dimension'],
        outcome = value['outcome'],
        progress = value['progress'];
    final dirty = value['worldMayHaveChanged'],
        refresh = value['refreshRequired'];
    final action = switch (value['action']) {
      'start' => WorldDestroyAction.start,
      'hold' => WorldDestroyAction.hold,
      'cancel' => WorldDestroyAction.cancel,
      _ => throw const FormatException(),
    };
    if (value['type'] != 'world_block_destroy' ||
        value['protocol'] is! int ||
        value['protocol'] != 1 ||
        dimension is! String ||
        dimension.length > 128 ||
        !RegExp(r'^[a-z0-9_.-]+:[a-z0-9_./-]+$').hasMatch(dimension) ||
        !{'active', 'changed', 'denied', 'cancelled'}.contains(outcome) ||
        progress is! num ||
        !progress.isFinite ||
        progress < 0 ||
        progress > 1 ||
        dirty is! bool ||
        refresh is! bool ||
        refresh != (outcome != 'active') ||
        (outcome == 'active' && (progress <= 0 || !dirty)) ||
        (action == WorldDestroyAction.cancel && outcome == 'active') ||
        (outcome == 'changed' && (progress != 1 || !dirty)) ||
        ((outcome == 'denied' || outcome == 'cancelled') && progress != 0)) {
      throw const FormatException();
    }
    final seq = _integer(value['seq'], 0, 999999999);
    final operation = _integer(value['operation'], 0, seq);
    if ((action == WorldDestroyAction.start && operation != seq) ||
        (action != WorldDestroyAction.start && operation >= seq))
      throw const FormatException();
    return WorldDestroySnapshot._(
      seq,
      _integer(value['sessionEpoch'], 1, 9007199254740991),
      _integer(value['subscriptionId'], 1, 999999999),
      _integer(value['revision'], 1, 999999999),
      dimension,
      operation,
      action,
      outcome as String,
      progress.toDouble(),
      dirty,
      refresh,
    );
  }
}

int _integer(Object? value, int min, int max) {
  if (value is! int || value < min || value > max)
    throw const FormatException();
  return value;
}
