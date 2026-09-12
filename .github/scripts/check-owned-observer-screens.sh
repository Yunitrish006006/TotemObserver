#!/usr/bin/env bash
set -euo pipefail

root="${1:-.}"
clients="$root/src/main/java/dev/totem/observer/client"

forbidden='Screenshot\.takeScreenshot|FrameChunk|FrameRelay|DynamicTexture|observer_frame_chunk|observer_frame_relay|observer_capture_control|glReadPixels|NativeImage\.writeToFile'
if grep -ERn -- "$forbidden" "$root/src/main"; then
  echo 'Observer production code is not framebuffer-free.' >&2
  exit 1
fi

for contract in ObserverReadOnlyScreen ObserverOwnedScreenCoordinator ObserverOwnedScreenPayloads; do
  grep -ERq -- "$contract" "$root/src/main" || { echo "Missing Observer ownership contract: $contract" >&2; exit 1; }
done

# The central protocol table exists only for compatibility with feature-specific transports
# that predate ObserverOwnedScreenPayloads. New module-owned families must negotiate through
# their TotemCore ObserverScreenProvider identity and must not grow this registry.
legacy_registry="$root/src/main/java/dev/totem/observer/network/ObserverOwnedScreenProtocols.java"
expected_legacy_families="$(printf '%s\n' \
  automata_copper_golem \
  locksmith_management \
  nexus \
  nexus_death_node_admin \
  remnant_backpack \
  villagers_woodcutter | sort)"
actual_legacy_families="$(
  awk '
    /EXPECTED = Map\.of\(/ { capture=1; next }
    capture { print; if (/\);/) exit }
  ' "$legacy_registry" \
    | grep -oE '"[a-z0-9_.:-]+"' \
    | tr -d '"' \
    | sort -u
)"
if [[ "$actual_legacy_families" != "$expected_legacy_families" ]]; then
  echo 'Observer legacy owned-screen registry changed.' >&2
  echo 'Do not centrally register new module-owned families; use ObserverScreenProvider + ObserverOwnedScreenPayloads.' >&2
  diff -u <(printf '%s\n' "$expected_legacy_families") <(printf '%s\n' "$actual_legacy_families") || true
  exit 1
fi

grep -Fq 'newFeatureProviderDoesNotNeedCentralFamilyRegistration' \
  "$root/src/test/java/dev/totem/observer/runtime/ObserverOwnedScreenProtocolTest.java" \
  || { echo 'Missing generic owned-screen provider regression test.' >&2; exit 1; }

declare -A owner_screens=(
  [TotemRemnant]='BackpackScreen'
  [TotemAutomata]='CopperGolemMenuScreen'
  [TotemNexus]='NexusOwnedScreen'
  [TotemLocksmith]='LocksmithManagementScreen'
  [TotemVillagers]='WoodcutterScreen'
)
for module in "${!owner_screens[@]}"; do
  module_root="$root/.lockstep/$module"
  [[ -d "$module_root" ]] || continue
  grep -Eq -- 'totem:observer_screen_provider' "$module_root/src/main/resources/fabric.mod.json" \
    || { echo "$module lacks the TotemCore Observer provider entrypoint" >&2; exit 1; }
  grep -ERq -- "class[[:space:]]+${owner_screens[$module]}" "$module_root/src/client" \
    || { echo "$module lacks its production owner Screen" >&2; exit 1; }
  grep -ERq -- 'ObserverReadOnlyScreen|NexusOwnedScreen' "$module_root/src/client" \
    || { echo "$module production Screen lacks the read-only Observer contract" >&2; exit 1; }
done
