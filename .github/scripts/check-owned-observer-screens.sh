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
