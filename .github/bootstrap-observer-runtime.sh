#!/usr/bin/env bash
set -euo pipefail

mode="${1:-}"

prepare() {
  rm -rf src gradle gradlew gradlew.bat build.gradle settings.gradle gradle.properties LICENSE

  mkdir -p src/main/java/dev/totem/observer
  source_root='.source/src/main/java/dev/totem/vanillatweaks'
  while IFS= read -r -d '' file; do
    rel="${file#${source_root}/}"
    dest="src/main/java/dev/totem/observer/${rel}"
    mkdir -p "$(dirname "$dest")"
    sed \
      -e 's/dev\.totem\.vanillatweaks/dev.totem.observer/g' \
      -e 's/TotemVanillaTweaks/TotemObserver/g' \
      -e 's/TotemObserver\.MOD_ID/TotemObserver.PROTOCOL_NAMESPACE/g' \
      "$file" > "$dest"
  done < <(find "$source_root" -type f -name 'Observer*.java' -print0)

  cat > src/main/java/dev/totem/observer/TotemObserver.java <<'JAVA'
package dev.totem.observer;

import dev.totem.observer.observer.ObserverServerRuntime;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TotemObserver implements ModInitializer {
    public static final String MOD_ID = "totem-observer";
    /** Existing Observer v4 packet namespace; retained during repository extraction. */
    public static final String PROTOCOL_NAMESPACE = "totem-vanilla-tweaks";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ObserverServerRuntime.register();
        LOGGER.info("TotemObserver initialized");
    }
}
JAVA

  cat > src/main/java/dev/totem/observer/TotemObserverClient.java <<'JAVA'
package dev.totem.observer;

import dev.totem.observer.observer.ObserverClientRuntime;
import net.fabricmc.api.ClientModInitializer;

public final class TotemObserverClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ObserverClientRuntime.register();
    }
}
JAVA

  mkdir -p src/main/resources/assets/totem-observer/lang
  python3 <<'PY'
import json
from pathlib import Path

source = Path('.source/src/main/resources')
target = Path('src/main/resources')
for old_name, new_name in [
    ('totem-vanilla-tweaks.mixins.json', 'totem-observer.mixins.json'),
    ('totem-vanilla-tweaks.client.mixins.json', 'totem-observer.client.mixins.json'),
]:
    data = json.loads((source / old_name).read_text())
    data['package'] = 'dev.totem.observer.mixin'
    for key in ('mixins', 'client', 'server'):
        if key in data:
            data[key] = [entry for entry in data[key]
                         if entry.rsplit('.', 1)[-1].startswith('Observer')]
    (target / new_name).write_text(json.dumps(data, indent=2) + '\n')

lang_source = source / 'assets/totem/lang/zh_tw.json'
if lang_source.exists():
    lang = json.loads(lang_source.read_text())
    observer = {k: v for k, v in lang.items()
                if 'observer' in k.lower() or 'observe' in k.lower()}
    (target / 'assets/totem-observer/lang/zh_tw.json').write_text(
        json.dumps(observer, ensure_ascii=False, indent=2) + '\n')
PY

  if [[ -f .source/src/main/resources/assets/totem-vanilla-tweaks/icon.png ]]; then
    mkdir -p src/main/resources/assets/totem-observer
    cp .source/src/main/resources/assets/totem-vanilla-tweaks/icon.png \
      src/main/resources/assets/totem-observer/icon.png
  fi

  cat > src/main/resources/fabric.mod.json <<'JSON'
{
  "schemaVersion": 1,
  "id": "totem-observer",
  "version": "${version}",
  "name": "TotemObserver",
  "description": "Server-authoritative spectator Observer View runtime for the Totem ecosystem.",
  "authors": ["Yunitrish006006"],
  "license": "Apache-2.0",
  "icon": "assets/totem-observer/icon.png",
  "environment": "*",
  "entrypoints": {
    "main": ["dev.totem.observer.TotemObserver"],
    "client": ["dev.totem.observer.TotemObserverClient"]
  },
  "mixins": [
    "totem-observer.mixins.json",
    {"config": "totem-observer.client.mixins.json", "environment": "client"}
  ],
  "depends": {
    "fabricloader": ">=0.19.3",
    "fabric-api": "*",
    "minecraft": "~26.2",
    "java": ">=25",
    "totem-core": ">=0.7.18 <0.8.0"
  },
  "breaks": {
    "totem-vanilla-tweaks": "<=0.1.27"
  }
}
JSON

  cp -R .source/gradle .
  cp .source/gradlew .
  [[ ! -f .source/gradlew.bat ]] || cp .source/gradlew.bat .
  chmod +x gradlew
  [[ ! -f .source/LICENSE ]] || cp .source/LICENSE .

  cat > settings.gradle <<'GRADLE'
pluginManagement {
    repositories {
        maven { name = 'Fabric'; url = 'https://maven.fabricmc.net/' }
        mavenCentral()
        gradlePluginPortal()
    }
}
rootProject.name = 'TotemObserver'
GRADLE

  minecraft_version="$(sed -n 's/^minecraft_version=//p' .source/gradle.properties)"
  loader_version="$(sed -n 's/^loader_version=//p' .source/gradle.properties)"
  fabric_version="$(sed -n 's/^fabric_version=//p' .source/gradle.properties)"
  cat > gradle.properties <<EOF
org.gradle.jvmargs=-Xmx2G
org.gradle.parallel=true
minecraft_version=${minecraft_version}
loader_version=${loader_version}
fabric_version=${fabric_version}
mod_version=0.1.0
maven_group=dev.totem
archives_base_name=totem-observer
EOF

  cat > build.gradle <<'GRADLE'
plugins {
    id 'net.fabricmc.fabric-loom' version '1.17.12'
    id 'maven-publish'
}

version = project.mod_version
group = project.maven_group
base { archivesName = project.archives_base_name }

def totemCoreJar = file(providers.gradleProperty('totemCoreJar')
        .getOrElse('../TotemCore/build/libs/totem-core-0.7.18.jar'))

repositories { mavenCentral() }

dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    implementation "net.fabricmc:fabric-loader:${project.loader_version}"
    implementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"
    implementation files(totemCoreJar)
    productionRuntimeMods "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"
    productionRuntimeMods files(totemCoreJar)
    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.4'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.withType(Test).configureEach { useJUnitPlatform() }

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.named('processResources', ProcessResources) {
    inputs.property 'version', project.version
    filesMatching('fabric.mod.json') { expand(version: project.version) }
    doLast {
        def zhTw = file('src/main/resources/assets/totem-observer/lang/zh_tw.json')
        if (zhTw.isFile()) {
            copy {
                from zhTw
                into file("${destinationDir}/assets/totem-observer/lang")
                rename { 'zh_cn.json' }
            }
        }
    }
}

tasks.named('jar', Jar) {
    preserveFileTimestamps = false
    reproducibleFileOrder = true
}
GRADLE
}

verify_commit() {
  test -f build/libs/totem-observer-0.1.0.jar
  ! grep -R -n 'dev\.totem\.vanillatweaks' src/main/java
  ! grep -R -n 'TotemVanillaTweaks' src/main/java
  grep -F 'PROTOCOL_NAMESPACE = "totem-vanilla-tweaks"' src/main/java/dev/totem/observer/TotemObserver.java
  forbidden='Screenshot\.takeScreenshot|FrameChunk|FrameRelay|DynamicTexture|ObserverFrameRules|observer_frame_chunk|observer_frame_relay|observer_capture_control'
  if grep -R -n -E "$forbidden" src/main; then
    echo 'Framebuffer transport code entered TotemObserver.' >&2
    exit 1
  fi

  rm -rf .source .lockstep build .gradle
  rm -f .github/workflows/bootstrap-observer-runtime.yml .github/bootstrap-observer-runtime.sh
  rmdir .github/workflows 2>/dev/null || true
  rmdir .github 2>/dev/null || true
  git config user.name 'github-actions[bot]'
  git config user.email '41898282+github-actions[bot]@users.noreply.github.com'
  git add -A
  git commit -m 'feat: extract Observer runtime into dedicated module'
  git push origin HEAD:feat/bootstrap-observer-runtime
}

case "$mode" in
  prepare) prepare ;;
  verify-commit) verify_commit ;;
  *) echo "usage: $0 {prepare|verify-commit}" >&2; exit 2 ;;
esac
