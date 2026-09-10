#!/usr/bin/env bash
set -euo pipefail

src="${GITHUB_WORKSPACE}/.source/TotemVanillaTweaks"
root="${GITHUB_WORKSPACE}"

rm -rf "$root/src/gametest" "$root/src/integrationGametest"
mkdir -p "$root/src/gametest/java/dev/totem/observer/gametest" "$root/src/gametest/resources"

for file in "$src"/src/gametest/java/dev/totem/vanillatweaks/gametest/Observer*.java; do
  cp "$file" "$root/src/gametest/java/dev/totem/observer/gametest/$(basename "$file")"
done

cp -R "$src/src/integrationGametest" "$root/src/"
mkdir -p "$root/src/integrationGametest/java/dev/totem/observer"
if [[ -d "$root/src/integrationGametest/java/dev/totem/vanillatweaks" ]]; then
  mv "$root/src/integrationGametest/java/dev/totem/vanillatweaks/client" \
    "$root/src/integrationGametest/java/dev/totem/observer/client"
  rmdir "$root/src/integrationGametest/java/dev/totem/vanillatweaks" || true
fi

python3 - <<'PY'
from pathlib import Path
import json

root = Path('.')
src = Path('.source/TotemVanillaTweaks')

for base in [root / 'src/gametest/java', root / 'src/integrationGametest/java']:
    for path in base.rglob('*.java'):
        text = path.read_text()
        text = text.replace('dev.totem.vanillatweaks.gametest', 'dev.totem.observer.gametest')
        text = text.replace('dev.totem.vanillatweaks.client', 'dev.totem.observer.client')
        text = text.replace('dev.totem.vanillatweaks.network', 'dev.totem.observer.network')
        text = text.replace('dev.totem.vanillatweaks.observer', 'dev.totem.observer.runtime')
        path.write_text(text)

old = json.loads((src / 'src/gametest/resources/fabric.mod.json').read_text())
entries = [
    value.replace('dev.totem.vanillatweaks.gametest', 'dev.totem.observer.gametest')
    for value in old.get('entrypoints', {}).get('fabric-client-gametest', [])
    if '.Observer' in value
]
manifest = {
    'schemaVersion': 1,
    'id': 'totem-observer-gametest',
    'version': '1.0.0',
    'name': 'TotemObserver GameTests',
    'environment': 'client',
    'entrypoints': {'fabric-client-gametest': entries},
    'depends': {
        'fabricloader': '*',
        'fabric-api': '*',
        'minecraft': '*',
        'totem-core': '*',
        'totem-observer': '*'
    }
}
(root / 'src/gametest/resources/fabric.mod.json').write_text(json.dumps(manifest, indent=2) + '\n')

integration = json.loads((root / 'src/integrationGametest/resources/fabric.mod.json').read_text())
integration['id'] = 'totem-observer-integration-gametest'
integration['name'] = 'TotemObserver Cross-module Integration GameTests'
integration['entrypoints']['fabric-client-gametest'] = [
    value.replace('dev.totem.vanillatweaks.client', 'dev.totem.observer.client')
    for value in integration['entrypoints']['fabric-client-gametest']
]
integration['depends'].pop('totem-vanilla-tweaks', None)
integration['depends']['totem-observer'] = '*'
(root / 'src/integrationGametest/resources/fabric.mod.json').write_text(json.dumps(integration, indent=2) + '\n')
PY

cp "$src/.github/scripts/build-observer-integration-jars.sh" "$root/.github/scripts/build-observer-integration-jars.sh"
chmod +x "$root/.github/scripts/build-observer-integration-jars.sh"

cat > "$root/.github/scripts/check-owned-observer-screens.sh" <<'EOF'
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
EOF
chmod +x "$root/.github/scripts/check-owned-observer-screens.sh"

cat > "$root/build.gradle" <<'EOF'
plugins {
    id 'net.fabricmc.fabric-loom' version '1.17.12'
    id 'maven-publish'
}

version = project.mod_version
group = project.maven_group
base { archivesName = project.archives_base_name }

def totemCoreJar = file(providers.gradleProperty('totemCoreJar')
        .getOrElse('../TotemCore/build/libs/totem-core-0.7.18.jar'))
def observerIntegrationJars = [
        remnant   : file(providers.gradleProperty('totemRemnantJar')
                .getOrElse('../TotemRemnant/build/libs/totem-remnant-0.2.21.jar')),
        automata  : file(providers.gradleProperty('totemAutomataJar')
                .getOrElse('../TotemAutomata/build/libs/totem-automata-0.1.24.jar')),
        nexus     : file(providers.gradleProperty('totemNexusJar')
                .getOrElse('../TotemNexus/build/libs/totem-nexus-0.3.17.jar')),
        villagers : file(providers.gradleProperty('totemVillagersJar')
                .getOrElse('../TotemVillagers/build/libs/totem-villagers-0.1.36.jar')),
        locksmith : file(providers.gradleProperty('totemLocksmithJar')
                .getOrElse('../TotemLocksmith/build/libs/totem-locksmith-0.1.10.jar'))
]

sourceSets {
    integrationGametest {
        java.setSrcDirs(['src/integrationGametest/java'])
        resources.setSrcDirs(['src/integrationGametest/resources'])
        compileClasspath += sourceSets.main.output.classesDirs
        compileClasspath += configurations.compileClasspath
        compileClasspath += files(observerIntegrationJars.values())
        runtimeClasspath += output + sourceSets.main.output
        runtimeClasspath += configurations.runtimeClasspath
        runtimeClasspath += files(observerIntegrationJars.values())
    }
}

loom {
    mods {
        'totem-observer' { sourceSet sourceSets.main }
        'totem-observer-integration-gametest' { sourceSet sourceSets.integrationGametest }
    }
    runs {
        integrationClientGametest {
            client()
            configName = 'Observer Cross-module Integration Client GameTest'
            runDir = 'build/run/integrationClientGametest'
            source = sourceSets.integrationGametest
            vmArg '-Dfabric.client.gametest'
            vmArg '-Dfabric.client.gametest.disableNetworkSynchronizer=true'
        }
    }
}

fabricApi {
    configureTests {
        createSourceSet = true
        modId = 'totem-observer-gametest'
        enableGameTests = true
        enableClientGameTests = true
        eula = true
    }
}

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

tasks.named('compileIntegrationGametestJava').configure {
    doFirst {
        observerIntegrationJars.each { module, jarFile ->
            if (!jarFile.isFile()) throw new GradleException("Missing pinned Observer integration JAR for ${module}: ${jarFile}")
        }
    }
}

tasks.named('runIntegrationClientGametest').configure {
    dependsOn(sourceSets.integrationGametest.classesTaskName, sourceSets.integrationGametest.processResourcesTaskName)
    doFirst {
        File dir = file('build/run/integrationClientGametest')
        dir.mkdirs()
        new File(dir, 'options.txt').text = '''onboardAccessibility:false
skipMultiplayerWarning:true
tutorialStep:none
'''
    }
}

tasks.withType(Test).configureEach { useJUnitPlatform() }

tasks.register('productionGametestModJar', Jar) {
    dependsOn(sourceSets.gametest.classesTaskName, sourceSets.gametest.processResourcesTaskName)
    archiveClassifier = 'production-gametest'
    from sourceSets.gametest.output
    preserveFileTimestamps = false
    reproducibleFileOrder = true
}

tasks.register('runProductionClientGameTest', net.fabricmc.loom.task.prod.ClientProductionRunTask) {
    dependsOn('productionGametestModJar')
    mods.from(tasks.named('productionGametestModJar').flatMap { it.archiveFile })
    jvmArgs.add('-Dfabric.client.gametest')
    jvmArgs.add('-Dfabric.client.gametest.disableNetworkSynchronizer=true')
    useXVFB = true
}

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
EOF

cat > "$root/.github/workflows/observer-runtime-validation.yml" <<'EOF'
name: Observer Runtime Validation

on:
  workflow_dispatch:
  pull_request:
  push:
    branches:
      - main

permissions:
  contents: read

jobs:
  runtime-validation:
    runs-on: ubuntu-latest
    timeout-minutes: 55
    steps:
      - name: Check out TotemObserver
        uses: actions/checkout@v4

      - name: Set up Java 25
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '25'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          cache-disabled: true

      - name: Check out pinned TotemCore
        uses: actions/checkout@v4
        with:
          repository: Yunitrish006006/TotemCore
          ref: f87cd10fe8aefce77925b9e75dad237f17f38966
          path: .lockstep/TotemCore
          persist-credentials: false

      - name: Check out pinned TotemExcavation compile dependency
        uses: actions/checkout@v4
        with:
          repository: Yunitrish006006/TotemExcavation
          ref: f40b94fd5d9de8b47534343c76a95f62926d2b1b
          path: .lockstep/TotemExcavation
          persist-credentials: false

      - name: Check out pinned TotemRemnant
        uses: actions/checkout@v4
        with:
          repository: Yunitrish006006/TotemRemnant
          ref: 1d89395f93d8ea817947db4653919a11355eb548
          path: .lockstep/TotemRemnant
          persist-credentials: false

      - name: Check out pinned TotemAutomata
        uses: actions/checkout@v4
        with:
          repository: Yunitrish006006/TotemAutomata
          ref: cc4bdb022615faad73bc9e5c0ef6d52b9d0970e6
          path: .lockstep/TotemAutomata
          persist-credentials: false

      - name: Check out pinned TotemNexus
        uses: actions/checkout@v4
        with:
          repository: Yunitrish006006/TotemNexus
          ref: e3f91a19790f9da85494b9ae1d5770dda12e0e43
          path: .lockstep/TotemNexus
          persist-credentials: false

      - name: Check out pinned TotemVillagers
        uses: actions/checkout@v4
        with:
          repository: Yunitrish006006/TotemVillagers
          ref: 615f83c5c3534a40e6ae7a2a0713390512f8b64c
          path: .lockstep/TotemVillagers
          persist-credentials: false

      - name: Check out pinned TotemLocksmith
        uses: actions/checkout@v4
        with:
          repository: Yunitrish006006/TotemLocksmith
          ref: 9e8e25d44887a33839dc2a3b92a424ca4b931e00
          path: .lockstep/TotemLocksmith
          persist-credentials: false

      - name: Build pinned Observer owner modules
        shell: bash
        run: bash .github/scripts/build-observer-integration-jars.sh

      - name: Enforce module-owned Observer screens
        shell: bash
        run: bash .github/scripts/check-owned-observer-screens.sh

      - name: Compile Observer validation source sets
        shell: bash
        run: |
          set -euo pipefail
          core_jar="${GITHUB_WORKSPACE}/.lockstep/TotemCore/build/libs/totem-core-0.7.18.jar"
          ./gradlew \
            -PtotemCoreJar="$core_jar" \
            -PtotemRemnantJar="${GITHUB_WORKSPACE}/.lockstep/TotemRemnant/build/libs/totem-remnant-0.2.21.jar" \
            -PtotemAutomataJar="${GITHUB_WORKSPACE}/.lockstep/TotemAutomata/build/libs/totem-automata-0.1.24.jar" \
            -PtotemNexusJar="${GITHUB_WORKSPACE}/.lockstep/TotemNexus/build/libs/totem-nexus-0.3.17.jar" \
            -PtotemVillagersJar="${GITHUB_WORKSPACE}/.lockstep/TotemVillagers/build/libs/totem-villagers-0.1.36.jar" \
            -PtotemLocksmithJar="${GITHUB_WORKSPACE}/.lockstep/TotemLocksmith/build/libs/totem-locksmith-0.1.10.jar" \
            clean test assemble compileGametestJava compileIntegrationGametestJava --no-daemon --stacktrace

      - name: Run client GameTests
        shell: bash
        run: |
          set -euo pipefail
          core_jar="${GITHUB_WORKSPACE}/.lockstep/TotemCore/build/libs/totem-core-0.7.18.jar"
          xvfb-run -a ./gradlew -PtotemCoreJar="$core_jar" runClientGametest --no-daemon --stacktrace

      - name: Run cross-module production sender Client GameTest
        shell: bash
        run: |
          set -euo pipefail
          core_jar="${GITHUB_WORKSPACE}/.lockstep/TotemCore/build/libs/totem-core-0.7.18.jar"
          xvfb-run -a ./gradlew \
            -PtotemCoreJar="$core_jar" \
            -PtotemRemnantJar="${GITHUB_WORKSPACE}/.lockstep/TotemRemnant/build/libs/totem-remnant-0.2.21.jar" \
            -PtotemAutomataJar="${GITHUB_WORKSPACE}/.lockstep/TotemAutomata/build/libs/totem-automata-0.1.24.jar" \
            -PtotemNexusJar="${GITHUB_WORKSPACE}/.lockstep/TotemNexus/build/libs/totem-nexus-0.3.17.jar" \
            -PtotemVillagersJar="${GITHUB_WORKSPACE}/.lockstep/TotemVillagers/build/libs/totem-villagers-0.1.36.jar" \
            -PtotemLocksmithJar="${GITHUB_WORKSPACE}/.lockstep/TotemLocksmith/build/libs/totem-locksmith-0.1.10.jar" \
            runIntegrationClientGametest --no-daemon --stacktrace

      - name: Verify cross-module screenshots
        shell: bash
        run: |
          set -euo pipefail
          dir="${GITHUB_WORKSPACE}/build/owner-present-integration-screenshots"
          count="$(find "$dir" -maxdepth 1 -type f -name '*.png' | wc -l)"
          test "$count" = 10

      - name: Run production-namespace client GameTests
        shell: bash
        run: |
          set -euo pipefail
          core_jar="${GITHUB_WORKSPACE}/.lockstep/TotemCore/build/libs/totem-core-0.7.18.jar"
          ./gradlew -PtotemCoreJar="$core_jar" runProductionClientGameTest --no-daemon --stacktrace

      - name: Enforce extraction boundaries
        shell: bash
        run: |
          set -euo pipefail
          ! grep -R -n 'dev\.totem\.vanillatweaks' src/gametest src/integrationGametest
          forbidden='Screenshot\.takeScreenshot|FrameChunk|FrameRelay|DynamicTexture|ObserverFrameRules|observer_frame_chunk|observer_frame_relay|observer_capture_control'
          ! grep -R -n -E "$forbidden" src/main
EOF

# The migration workflow will remove itself and this helper before committing.
