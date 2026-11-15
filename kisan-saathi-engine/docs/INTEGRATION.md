# Wiring the engine into the Android app

Nothing in the app needs to be deleted. These steps add real-data advisories and fall back to
the existing demo cards if anything fails.

## 1. Copy files

| From this package | To the app repo |
|---|---|
| `android/assets/kisan/` (whole folder) | `app/src/main/assets/kisan/` |
| `android/kotlin/com/example/engine/KisanEngine.kt` | `app/src/main/java/com/example/engine/KisanEngine.kt` |
| `android/kotlin/com/example/engine/KisanAssets.kt` | `app/src/main/java/com/example/engine/KisanAssets.kt` |
| `android/kotlin/com/example/data/repository/EngineAdapter.kt` | `app/src/main/java/com/example/data/repository/EngineAdapter.kt` |

No new Gradle dependency is needed.

## 2. Edit `KisanRepository.kt`

Add one property inside the class:

```kotlin
var engine: com.example.engine.KisanEngine? = null
```

At the very top of `getAdvisoriesForDate`, before `val list = ...`, add:

```kotlin
engine?.let { e ->
    runCatching { EngineAdapter.advisoriesFor(e, simulatedDate) }
        .getOrNull()?.let { return it }
}
```

## 3. Load the engine once in `MainActivity.kt`

After the line `val repository = remember { KisanRepository(context) }` add:

```kotlin
LaunchedEffect(Unit) {
    repository.engine = withContext(Dispatchers.IO) {
        runCatching { com.example.engine.KisanAssets.load(context) }.getOrNull()
    }
}
```

with these imports: `androidx.compose.runtime.LaunchedEffect`, `kotlinx.coroutines.Dispatchers`,
`kotlinx.coroutines.withContext`.

The Home screen reads advisories inside `remember(simulatedDate)`, so cards refresh when the
time-travel date changes. On first launch the demo cards may show for a moment before the
engine finishes loading; changing the date once refreshes them.

## What you will see

- Dates up to the last day of data use that day's real weather.
- Later dates (the demo's 2026-27 season) replay the same calendar day from 2025-26, the most
  recent season with data. The "act by" date on those cards therefore shows the replay year.
- The engine assumes sowing on 10 November. Before that date it gives sowing advice; after it,
  crop-stage advice.
- Some dates have no card, because no rule fires. That is the real outcome for a quiet week.

## What was and was not tested

- `KisanEngine.kt` was compiled with Kotlin 2.2.10 and its output matched the Python engine
  exactly on 1,297 dates.
- `EngineAdapter.kt` was compiled against the app's real `Models.kt`, with the Firebase
  annotations stubbed out.
- `KisanAssets.kt` and the edits in steps 2 and 3 were NOT compiled, because there is no
  Android SDK where this was built. They are short; if the build fails, send the error.
- The app's Field Health, History, Summer and Rewards screens are untouched and still show demo values.
