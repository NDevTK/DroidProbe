# DroidProbe

Android security tool that scans installed apps bytecode to discover content providers, file providers, and intent interfaces, then generates a contextual GUI for interacting with them — no root required.

Unlike tools like Drozer that require typing raw URIs and intent parameters, DroidProbe automatically discovers IPC surfaces from DEX bytecode and presents them as tappable, interactive UI elements.

## Download

[**Download debug APK**](app/build/outputs/apk/debug/app-debug.apk) — sideload directly to any Android 8.0+ device.

## Features

- **App Scanner** — Lists all installed apps (including system apps) with search filtering
- **Manifest Analysis** — Extracts exported activities, services, receivers, and providers with permissions and intent filters
- **Binary Manifest Parsing** — Parses binary AndroidManifest.xml (AXML) directly from APK files for complete intent filter extraction, including deep link schemes, authorities, paths, and MIME types that `PackageManager` query methods miss
- **DEX Bytecode Analysis** — Parses APK DEX files using dexlib2 with forward register tracking to discover:
  - Content provider URIs (`UriMatcher.addURI`, `Uri.parse`, `CONTENT_URI` fields)
  - Deep link URI patterns with query parameters (`Uri.getQueryParameter`)
  - Intent extras with types (`getStringExtra`, `putExtra`, `Bundle.get*`)
  - ContentProvider `call()` method names and authorities
  - FileProvider path configurations (binary XML + bytecode heuristics)
  - Raw `content://` and deep link string constants
- **Content Provider Explorer** — Discovered URIs shown as tappable cards; tap to auto-query, results in a scrollable table
- **Intent Launcher** — Exported components grouped by type with one-tap launch; expandable extras editor with type-appropriate keyboards, deep link URI selector with query parameter fields, pre-filled from bytecode analysis; BROWSABLE badge highlights browser-launchable attack surfaces
- **FileProvider Browser** — Discovered paths as tappable cards with inline probe results showing accessibility, size, MIME type, and content preview
- **Class Hierarchy Resolution** — Extras are mapped to components via actual inheritance chain tracing, not name guessing

## Screenshots

<p align="center">
  <img src="Screenshot1.png" alt="App Scanner" width="250"/>
  <img src="Screenshot2.png" alt="Analysis" width="250"/>
  <img src="Screenshot3.png" alt="Intent Launcher" width="250"/>
</p>

## How It Works

1. **Manifest pass** — Reads exported components, permissions, and provider authorities via `PackageManager`, then enriches intent filters by parsing binary AndroidManifest.xml (AXML) from the APK for complete action/category/data coverage
2. **DEX pass 1** — Builds a class hierarchy map (`class -> superclass`) from all DEX classes
3. **DEX pass 2** — Scans bytecode with five extractors using forward register tracking: `UriPatternExtractor`, `IntentExtraExtractor`, `FileProviderExtractor`, `ContentProviderCallExtractor`, `StringConstantCollector`
4. **Inheritance resolution** — Maps discovered extras to exported components by walking the inheritance chain (handles inner classes, base classes, and superclass propagation)
5. **Interactive GUI** — Pre-populates three interaction screens from analysis results

## Tech Stack

| Component | Version |
|---|---|
| Kotlin | 2.2.10 |
| Jetpack Compose (BOM) | 2026.02.00 |
| Material 3 | Latest via BOM |
| Android Gradle Plugin | 9.0.1 |
| Room | 2.8.4 |
| Navigation Compose | 2.9.7 |
| smali-dexlib2 | 3.0.9 |
| Min SDK | 26 |
| Target SDK | 36 |

## Architecture

- **Single-activity** with Navigation Compose and type-safe `@Serializable` routes
- **MVVM** with ViewModels + StateFlow
- **Manual DI** via `AppModule` service locator (no Hilt/Dagger)
- **In-memory cache** (`ConcurrentHashMap`) in `AnalysisRepository` for sharing DEX results across screens
- **No root required** — `ApplicationInfo.sourceDir` APKs are world-readable; exported components accessible via standard Android APIs

## Project Structure

```
app/src/main/java/com/droidprobe/app/
├── analysis/
│   ├── manifest/
│   │   ├── ManifestAnalyzer.kt             # PackageManager + binary XML manifest extraction
│   │   └── BinaryManifestParser.kt         # AXML parser for complete intent filters
│   └── dex/
│       ├── DexAnalyzer.kt                  # Orchestrates DEX analysis with class hierarchy
│       ├── UriPatternExtractor.kt          # Content provider & deep link URI discovery
│       ├── IntentExtraExtractor.kt         # Intent extras with inheritance resolution
│       ├── FileProviderExtractor.kt        # FileProvider path config extraction
│       ├── ContentProviderCallExtractor.kt # ContentResolver.call() detection
│       └── StringConstantCollector.kt      # Brute-force string constant collection
├── data/
│   ├── model/                              # AppInfo, ManifestAnalysis, DexAnalysis, etc.
│   ├── db/                                 # Room database, DAOs, entities
│   └── repository/                         # AppRepository, AnalysisRepository
├── di/AppModule.kt                         # Manual dependency injection
├── interaction/
│   ├── ContentProviderInteractor.kt        # Query/insert/update/delete via ContentResolver
│   ├── IntentLauncher.kt                   # Build and launch intents
│   └── FileProviderAccessor.kt             # Probe FileProvider URIs
├── navigation/
│   ├── Screen.kt                           # Type-safe route definitions
│   └── DroidProbeNavGraph.kt               # NavHost wiring
├── scanner/PackageScanner.kt               # Installed app enumeration
├── ui/
│   ├── scanner/                            # App list screen
│   ├── analysis/                           # Component analysis screen
│   ├── providers/                          # Content provider explorer
│   ├── intents/                            # Intent launcher
│   ├── fileprovider/                       # FileProvider browser
│   ├── components/                         # Shared UI components
│   └── theme/                              # Material 3 dark theme
├── DroidProbeApplication.kt
└── MainActivity.kt
```

## Building

Requires Android Studio with JDK 21 (bundled JBR works).

```bash
JAVA_HOME="/path/to/android-studio/jbr" ./gradlew assembleDebug
```

## Permissions

- `QUERY_ALL_PACKAGES` — Required on Android 11+ to enumerate installed apps. Allowed for security tools on Play Store.
