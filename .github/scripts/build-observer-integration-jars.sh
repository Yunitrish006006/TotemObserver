#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
lockstep_root="${OBSERVER_LOCKSTEP_ROOT:-$repo_root/.lockstep}"
core="$lockstep_root/TotemCore"
core_jar="$core/build/libs/totem-core-0.7.23.jar"
wrapper="$core/gradlew"

assert_checkout() {
  local repo="$1" commit="$2" version="$3"
  test "$(git -C "$lockstep_root/$repo" rev-parse HEAD)" = "$commit"
  test "$(sed -n 's/^mod_version=//p' "$lockstep_root/$repo/gradle.properties")" = "$version"
}

assert_production_jar() {
  local archive="$1" mod_id="$2" version="$3"
  local entries

  test -f "$archive"
  case "$archive" in
    */build/libs/*.jar) ;;
    *)
      printf 'Expected a production JAR under build/libs, got %s\n' "$archive" >&2
      return 1
      ;;
  esac
  case "$(basename "$archive")" in
    *-dev.jar|*-sources.jar)
      printf 'Refusing non-production integration artifact %s\n' "$archive" >&2
      return 1
      ;;
  esac

  entries="$(jar tf "$archive")"
  grep -Fxq 'fabric.mod.json' <<< "$entries"
  if grep -Eq '/(gametest|integrationGametest|e2e)/' <<< "$entries"; then
    printf 'Production integration artifact contains test-only classes: %s\n' "$archive" >&2
    return 1
  fi
  unzip -p "$archive" fabric.mod.json \
    | jq -e --arg mod_id "$mod_id" --arg version "$version" \
        '.id == $mod_id and .version == $version' >/dev/null
}

assert_checkout TotemCore 424b90dc3929caa8c1afcfca7e63c50a64bde669 0.7.23
assert_checkout TotemExcavation 87bc24076b9fb6fe3a64504f7b638d4ec0767c7c 0.1.16
assert_checkout TotemRemnant 728db4adbe16fa13d1f99da3f2309b2ae5f7613a 0.2.27
assert_checkout TotemAutomata a5532acbd0146e35f08a7e8ecd110a0a78e27b6c 0.1.28
assert_checkout TotemNexus 5e61897005c79082d20b5d0c318e85a26dc6c688 0.3.25
assert_checkout TotemVillagers e264484bae4772bb949d74b5788b30f4154668e4 0.1.39
assert_checkout TotemLocksmith 93cb1ded5542918a98b164ad0a26b6ff4bcd1c90 0.1.13

chmod +x "$wrapper"
"$wrapper" -p "$core" jar --no-daemon --stacktrace
assert_production_jar "$core_jar" totem-core 0.7.23

"$wrapper" -p "$lockstep_root/TotemExcavation" \
  -PtotemCoreJar="$core_jar" jar --no-daemon --stacktrace
excavation_jar="$lockstep_root/TotemExcavation/build/libs/totem-excavation-0.1.16.jar"
assert_production_jar "$excavation_jar" totem-excavation 0.1.16

"$wrapper" -p "$lockstep_root/TotemRemnant" \
  -PtotemCoreJar="$core_jar" remapJar --no-daemon --stacktrace
remnant_jar="$lockstep_root/TotemRemnant/build/libs/totem-remnant-0.2.27.jar"
assert_production_jar "$remnant_jar" totem-remnant 0.2.27

"$wrapper" -p "$lockstep_root/TotemAutomata" \
  -PtotemCoreJar="$core_jar" \
  -PtotemExcavationJar="$excavation_jar" \
  -PincludeTotemExcavationRuntime=false jar --no-daemon --stacktrace
assert_production_jar \
  "$lockstep_root/TotemAutomata/build/libs/totem-automata-0.1.28.jar" \
  totem-automata 0.1.28

"$wrapper" -p "$lockstep_root/TotemNexus" \
  -PtotemCoreJar="$core_jar" jar --no-daemon --stacktrace
assert_production_jar \
  "$lockstep_root/TotemNexus/build/libs/totem-nexus-0.3.25.jar" \
  totem-nexus 0.3.25

"$wrapper" -p "$lockstep_root/TotemVillagers" \
  -PtotemCoreJar="$core_jar" -PtotemRemnantJar="$remnant_jar" \
  jar --no-daemon --stacktrace
assert_production_jar \
  "$lockstep_root/TotemVillagers/build/libs/totem-villagers-0.1.39.jar" \
  totem-villagers 0.1.39

"$wrapper" -p "$lockstep_root/TotemLocksmith" \
  -PtotemCoreJar="$core_jar" jar --no-daemon --stacktrace
assert_production_jar \
  "$lockstep_root/TotemLocksmith/build/libs/totem-locksmith-0.1.13.jar" \
  totem-locksmith 0.1.13
