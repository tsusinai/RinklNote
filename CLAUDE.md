# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

RinklNote is an Android personal bookkeeping app (记账应用) built with Kotlin + Jetpack Compose + Material 3 + Room. All UI text is in Chinese.

## Build & Test Commands

```bash
./gradlew assembleDebug            # Build debug APK
./gradlew assembleRelease          # Build release APK
./gradlew installDebug             # Install debug APK on connected device
./gradlew test                     # All unit tests (JVM, offline)
./gradlew connectedAndroidTest     # All instrumented tests (requires device/emulator)
./gradlew lint                     # Android Lint

# Run a single test class
./gradlew test --tests "com.example.rinklnote.ExampleUnitTest"
./gradlew connectedAndroidTest --tests "com.example.rinklnote.ExampleInstrumentedTest"
```

## Tech Stack

- **Language:** Kotlin 2.0.21, JVM target 11
- **Build:** Gradle 8.13 with Kotlin DSL + version catalog (`gradle/libs.versions.toml`)
- **AGP:** 8.13.0, compileSdk 36, minSdk 28, targetSdk 36
- **UI:** Jetpack Compose (BOM 2024.09.00) with Material 3
- **Database:** Room 2.6.1 with KSP (2.0.21-1.0.28) for annotation processing
- **Serialization:** kotlinx-serialization-json 1.7.3
- **Testing:** JUnit 4 (unit), AndroidX Test + Espresso (instrumented), Compose UI testing

All version numbers live in `gradle/libs.versions.toml`. Use `alias(libs.plugins.*)` and `libs.*` references — never hardcode versions or Maven coordinates in build files.

## Architecture

**MVVM + Repository pattern, single Activity, no DI framework.**

### Data Flow

```
RinklNoteApp (service locator)
  └─ AppDatabase (Room, singleton)
       ├─ BillDao, CategoryDao, AccountDao
       └─ BillRepositoryImpl
            ├─ StateFlow<List> for categories & accounts (cached, loaded once)
            └─ Flow<List<Bill>> via Room (reactive, observed by month)
                 ↓
ViewModel (Bookkeeping / QuickAdd / Assets)
  └─ exposes StateFlow<State> + processes sealed Event
       ↓
Compose UI (collectAsStateWithLifecycle)
```

### Navigation

Uses `HorizontalPager` (3 pages: 计划/记账/资产) with a custom bottom navigation bar — **not** Navigation Compose. The `AppNavigation.kt` composable orchestrates all three ViewModels, the QuickAdd drawer, the numeric keypad overlay, and voice input. `navigation-compose` is in the version catalog but unused.

### Key Patterns

- **State + Event per ViewModel:** Each ViewModel defines a `@Immutable` `State` data class and a sealed `Event` interface. Events are dispatched via `onEvent(Event)`.
- **One-shot effects:** `QuickAddViewModel` uses `Channel<QuickAddEffect>` (consumed by `LaunchedEffect` in `AppNavigation.kt`) for side effects that must fire exactly once.
- **Manual DI:** `RinklNoteApp.kt` acts as the service locator — lazily creates the database and repository, passes them to ViewModel factories. Each ViewModel has an inner `Factory` class implementing `ViewModelProvider.Factory`.
- **Two-phase confirmation:** Numeric keypad confirm → `CountAfter` animation (amount + check mark) → green check mark tap → `finalConfirm()` → DB insert → close drawer. This is to prevent accidental submissions.

### Quick Add Flow

FAB tap → drawer opens (default category: 三餐) → select category/account → tap amount area → keypad overlay appears → enter amount → confirm on keypad → check mark UI (CountAfter) → tap check mark → bill saved to DB → list refreshes → drawer closes.

### Chart Rendering

`ChartBox.kt` uses a single `Canvas` composable to draw line/bar charts, data labels, and X-axis labels — no third-party charting library. Animation uses `Animatable` with `snapTo(0f)` → `animateTo(1f)` (tween 1000ms). **Important:** `maxVal` for scaling must use the target `expenseData.max()`, not animated data, otherwise the animation scale drifts.

### Database

Room database (`rinklnote.db`, version 3) with 4 tables: `bills`, `categories`, `sub_categories`, `accounts`. Foreign keys on bills → categories/accounts. On first launch, `seedIfNeeded()` populates default categories (7 expense + 4 income, with 10 subcategories) and 3 accounts (微信/支付宝/默认). Currently uses `fallbackToDestructiveMigration` — data is lost on schema changes.

### Voice Input

Uses Android `SpeechRecognizer` (zh-CN). `VoiceParser` extracts amounts via regex (`(\d+\.?\d*)\s*[元块]?`) and matches keywords to categories (e.g., "午餐" → 三餐, "打车" → 交通).
