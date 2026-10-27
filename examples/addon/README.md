# Addon example

A minimal Forge addon that uses the MCA: Kingdoms API and still runs without MCA: Kingdoms. It is not shipped or packaged: `./gradlew check` compiles it (`compileAddonExampleJava`) against the API jar and Forge only, the same classpath an addon has, so the example and the API contract it shows cannot drift apart. The [addon API guide](../../docs/Addon-API.md) explains each point.

| File | Shows |
| --- | --- |
| [`KingdomsAddonExample.java`](src/main/java/com/example/kingdomsaddon/KingdomsAddonExample.java) | The `@Mod` entry point. It registers the bridge only when `mcakingdoms` is loaded, so no MCA: Kingdoms class is touched otherwise. |
| [`KingdomsBridge.java`](src/main/java/com/example/kingdomsaddon/KingdomsBridge.java) | Getting the service in `ServerStartedEvent` and handling an unready service; registering a kingdom resolver and closing it in `ServerStoppingEvent`; resolving stored settlement ids after merges; the visibility rule for trusted API results. |

To build a real addon from it, copy the two classes into a ForgeGradle 6 project, add `compileOnly files("libs/mcakingdoms-<version>-api.jar")`, give it a `mods.toml` with an optional dependency on `mcakingdoms`, and put the main `mcakingdoms-<version>.jar` in `run/mods` for development runs.
