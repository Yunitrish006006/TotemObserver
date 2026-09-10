#!/usr/bin/env bash
set -euo pipefail

source_dir='.source/src/main/java/dev/totem/vanillatweaks/mixin/client'
target_dir='src/main/java/dev/totem/observer/mixin/client'
mkdir -p "$target_dir"

accessors=(
  AbstractContainerScreenMenuAccessor
  AbstractMountInventoryScreenAccessor
  AbstractRecipeBookScreenAccessor
  AbstractSignEditScreenAccessor
  AdvancementTabAccessor
  AdvancementsScreenAccessor
  AnvilScreenAccessor
  BeaconScreenAccessor
  BookEditScreenAccessor
  BookSignScreenAccessor
  BookViewScreenAccessor
  ClientAdvancementsAccessor
  LoomScreenAccessor
  MerchantScreenAccessor
  RecipeBookComponentAccessor
  RecipeBookPageAccessor
  StatsScreenAccessor
  StatsScreenItemStatisticsListAccessor
  StatsScreenStatisticsTabAccessor
  StonecutterScreenAccessor
)

for name in "${accessors[@]}"; do
  source="$source_dir/${name}.java"
  test -f "$source"
  sed 's/dev\.totem\.vanillatweaks/dev.totem.observer/g' "$source" > "$target_dir/${name}.java"
done

python3 <<'PY'
import json
from pathlib import Path

allowed = {
    'AbstractContainerScreenMenuAccessor',
    'AbstractMountInventoryScreenAccessor',
    'AbstractRecipeBookScreenAccessor',
    'AbstractSignEditScreenAccessor',
    'AdvancementTabAccessor',
    'AdvancementsScreenAccessor',
    'AnvilScreenAccessor',
    'BeaconScreenAccessor',
    'BookEditScreenAccessor',
    'BookSignScreenAccessor',
    'BookViewScreenAccessor',
    'ClientAdvancementsAccessor',
    'LoomScreenAccessor',
    'MerchantScreenAccessor',
    'RecipeBookComponentAccessor',
    'RecipeBookPageAccessor',
    'StatsScreenAccessor',
    'StatsScreenItemStatisticsListAccessor',
    'StatsScreenStatisticsTabAccessor',
    'StonecutterScreenAccessor',
}
source = json.loads(Path('.source/src/main/resources/totem-vanilla-tweaks.client.mixins.json').read_text())
target_path = Path('src/main/resources/totem-observer.client.mixins.json')
target = json.loads(target_path.read_text())
existing = set(target.get('client', []))
for entry in source.get('client', []):
    if entry.rsplit('.', 1)[-1] in allowed and entry not in existing:
        target.setdefault('client', []).append(entry)
        existing.add(entry)
target_path.write_text(json.dumps(target, indent=2) + '\n')
PY
