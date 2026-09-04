# AFENTS.md

## Project overview

Kelivo is a cross-platform LLM chat client built with Flutter, targeting iOS, Android, macOS, Windows, and Linux. Package name is `Kelivo` — imports use `package:Kelivo/...`.

**Native Android port** lives in `kelivo-android/` (Kotlin + Jetpack Compose,
AGP 8.11.1 / Gradle 8.14 / Kotlin 2.2.20, minSdk 26 / target 35). It reuses the
Flutter drift v3 schema verbatim (see `kelivo-android/core/data/src/main/assets/kelivo_schema_v3.sql`)
so an existing `kelivo.db` opens in place. UI brand name is **Memo** (package id
stays `com.psyche.kelivo` for backup compatibility). Reference implementation
for native details: **RikkaHub** (https://github.com/rikkahub/rikkahub, same
AGPL-3.0 license; its `ai` module is the closest match to `core:llm`).

## Native Android port (kelivo-android)

**Golden rule (user requirement): 1:1 translation of the Flutter source — every
screen, component, string and icon must come from the original kelivo code.
Never invent UI/UX. The Flutter project in this repo is the source of truth;
RikkaHub is only a reference for solving native-side issues.**

Already aligned (committed, quality-gate green):
- Top bar: list icon + title + model subtitle + Map (mini map) + MessageCirclePlus
- Input bar: frosted rounded container (TextField top + action row: Boxes/Globe/
  Brain/Hammer/Zap left, Plus/Mic/ArrowUp send right)
- Message headers: user 13px α0.7 + 11px α0.5; assistant 32px avatar + name/time
- Bubbles: user primary α0.08 r16; assistant bare text 15.7sp/1.5
- Message actions: Copy/RefreshCw/Pencil 28px rounded
- MarkdownText (commonmark + GFM) + ThinkingCard (chain-of-thought collapse)
- Side drawer: search/history/assistant card/date-grouped list/user bar
- Launch: most recent conversation (or fresh); temporary chat only via toggle
- Theme: light default + palettes; status/nav icons follow theme

Still to port (per original source): showModelSelectSheet, settings sections,
ChatHistoryPage, mini-map panel, tool detail cards, 24 business features
(search/translate/MCP/backup/voice/QR/OCR), real device chat smoke w/ API key.

- Build env on this machine: system `JAVA_HOME` points at jdk-13 (breaks AGP) —
  always pin `JAVA_HOME=/c/Program Files/Java/jdk-21.0.10` and
  `GRADLE_USER_HOME=D:/DevCache/.gradle` (the default `~/.gradle` is under a
  Chinese username and Gradle's `@argfile` worker classpath becomes unreadable
  on CP936 JVMs, which kills `testDebugUnitTest` with `ClassNotFoundException:
  GradleWorkerMain`).
- Quality gate (all must pass before commit):
  ```bash
  cd kelivo-android && bash tools/quality_gate.sh
  ```
  which runs `:app:lintDebug`, `:app:testDebugUnitTest`, `:app:assembleDebug`.
  CI `.github/workflows/android-pr-check.yml` enforces the same plus a
  regenerated-resources check (`tools/arb_to_android.py`, `tools/settings_keys_gen.py`).
- Generated resources are committed and must stay in sync: ARB→strings via
  `tools/arb_to_android.py` (brandifies `Kelivo`→`Memo`), drift schema→SQL via
  `tools/drift_schema_to_sql.py`, palettes via `tools/palettes_gen.py`,
  settings keys via `tools/settings_keys_gen.py`.
- Modules: `app` (UI/nav/container), `core:common`, `core:ui` (theme + l10n),
  `core:data` (SQLite DAO + settings), `core:llm` (OkHttp SSE + OpenAI/Claude/
  Gemini clients), `feature:*` (assistant/chat/settings/utility — skeleton so far).
- Tests live under each module's `src/test/` (JUnit 4). `core:llm` has
  MockWebServer integration tests for SSE decoding and retry policy.

## Architecture

- **Feature-based structure**: `lib/features/<feature>/` with `pages/`, `widgets/`, `models/`, `utils/` subdirectories.
- **Desktop / mobile split**: most UI pages have separate desktop and mobile layouts (e.g. `home_desktop_layout.dart` / `home_mobile_layout.dart`). Desktop-only code lives in `lib/desktop/`. Use `ResponsiveHelper` from `lib/shared/responsive/` to branch by screen type.
- **State management**: Provider (`lib/core/providers/`).
- **Database**: Drift (`lib/core/database/`). Schema versions tracked in `drift_schemas/`.
- **Localization**: ARB-based (`lib/l10n/`), English template (`app_en.arb`). Run `flutter gen-l10n` after editing ARB files and commit the generated output.

## Pre-commit checklist

All three must pass before committing:

```bash
dart format lib test                        # format changed files
dart analyze --fatal-infos lib test         # zero warnings, zero infos
flutter test                                # all unit tests green
```

CI (`pr-check.yml`) enforces the same gates on every PR.

## Benchmarks are not tests

`test/perf/*_bench.dart` print timings and contain no `expect()`, so they cannot
fail. They are named out of the default `_test.dart` glob and so are skipped by
`flutter test`. Run one explicitly:

```bash
flutter test test/perf/timeline_scroll_bench.dart
```

## UI guidelines

- **Use app-defined widgets** from `lib/shared/widgets/` and `lib/shared/dialogs/` instead of raw Flutter/Material widgets wherever an equivalent exists (e.g. `SectionCard`, `CustomBottomSheet`, `IosFormTextField`, `IosCheckbox`, `InteractiveDrawer`).
- **BottomSheet**: both Flutter's built-in bottom sheet and `CustomBottomSheet` are fine on mobile. Never use any bottom sheet on desktop — use a dialog or another interaction pattern instead.
- **Icons**: use `lucide_icons_flutter`, not `Icons.*` from Material.
- **Animations**: use `flutter_animate` / `animations` for motion.
- When building a new page, create separate desktop and mobile layouts unless the page is trivially simple. Wire them together via `ResponsiveHelper`.

## Code style

- Do not preserve backward compatibility. Remove obsolete paths instead of adding compatibility layers, fallbacks, or migrations.
- Choose the simplest implementation that fully meets the current requirements. Avoid speculative abstractions, configuration, and indirection.
- Grow the system in layers. Start from the smallest version that works end to end, and add each new capability on top of a product that already works. Never trade a working product for unfinished complexity.
- Keep components modular and concerns clearly separated.
- Prefer established, well-maintained libraries when they reduce overall complexity or improve reliability. Do not reimplement common functionality without a clear reason.
- Lean on the dependencies already in the project before writing your own implementation or adding packages. Do not assume a library lacks a capability without checking its documentation and types.
- Make architectural decisions for the long term. Do not accept a stopgap that only works for now and is meant to be replaced later.

## Local dependencies

Several packages live under `dependencies/` and are referenced by path in `pubspec.yaml` (e.g. `gpt_markdown`, `mcp_client`, `flutter_tts`, `flutter_math_fork`, `downsize`). The analyzer excludes `dependencies/flutter_math_fork/**` and `dependencies/flutter_tts/**`.

## Useful commands

```bash
flutter pub get                             # install dependencies
flutter gen-l10n                            # regenerate l10n files
dart run build_runner build                 # regenerate Drift code
```
