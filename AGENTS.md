# AFENTS.md

## Project overview

Kelivo is a cross-platform LLM chat client built with Flutter, targeting iOS, Android, macOS, Windows, and Linux. Package name is `Kelivo` — imports use `package:Kelivo/...`.

**Memo** is a standalone native Android app (Kotlin + Jetpack Compose,
AGP 8.11.1 / Gradle 8.14 / Kotlin 2.2.20, minSdk 26 / target 35) in
`memo-android/`. It is **not** data-compatible with the upstream Flutter app:
`namespace`/`applicationId` are `com.psyche.memo` and the database is
`memo.db`. The SQLite DDL is still generated verbatim from drift v3
(`tools/drift_schema_to_sql.py` → `core/data/src/main/assets/memo_schema_v3.sql`)
because it is a proven schema — not for compatibility. No user-visible string,
identifier, resource key or asset may carry the kelivo name. Reference
implementation for native details: **RikkaHub** (https://github.com/rikkahub/rikkahub,
same AGPL-3.0 license; its `ai` module is the closest match to `core:llm`, local clone
at `D:\program\.rikkahub-ref`). Memo and RikkaHub are functionally very close (sibling
LLM chat clients with the same provider/tool/surface model), so when implementing or
fixing a Memo feature, **borrow or directly port from RikkaHub** as a valid source —
don't re-derive from scratch unless RikkaHub doesn't cover the case. The Flutter
source (`lib/`) remains the primary 1:1 source-of-truth for UI/text parity; RikkaHub
fills in native-side details and shared feature implementations.

## Native Android port (memo-android)

**Golden rule (user requirement): 1:1 translation of the Flutter source — every
screen, component, string and icon must come from the original kelivo code.
Never invent UI/UX. The Flutter project in this repo is the source of truth;
RikkaHub is only a reference for solving native-side issues.**

1:1 covers behaviour, layout and components — **not branding or upstream
endpoints**. Do not carry over the kelivo name, its GitHub/Discord/afdian links,
the `kelivo.psycheas.top` update/sponsor/tools endpoints, or upstream-branded
seed entries; comment references to Flutter source paths (`mirrors kelivo's
_iosNavRow`) stay as-is because they are provenance, not UI text.

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

Also aligned (9/3-9/4): model select sheet (search/Bookmark/provider chips),
ChatHistoryScreen (swipe delete/pin/date formats), MiniMapSheet (QA pairing +
jump), display-settings 16 rows + ChatItemDisplay 13 toggles + Rendering 8 +
Behavior/Startup 20 rows + Image/MessageStyle/AutoRetry/Haptics sub-pages,
provider management pages, drawer global-search mode, temporary-chat 3-state
icon, long-press conversation sheet + multi-select bar, streaming breathing
dot, IosSwitch/IosCheckbox/IosTileButton + tactile press + Haptics, semantic
colors + surface ladder + HCT port, self-drawn InteractiveDrawer (offset
slide-in, scrim 0.12, edge-only drag, settle by velocity), global ripple off.

Still to port (per original source): tool detail cards, 24 business features
(search/translate/MCP/backup/voice/QR/OCR), real device chat smoke w/ API key,
remaining settings sub-pages, feature:* modules are still empty shells.

- Build env on this machine: system `JAVA_HOME` points at jdk-13 (breaks AGP) —
  always pin `JAVA_HOME=/c/Program Files/Java/jdk-21.0.10` and
  `GRADLE_USER_HOME=D:/DevCache/.gradle` (the default `~/.gradle` is under a
  Chinese username and Gradle's `@argfile` worker classpath becomes unreadable
  on CP936 JVMs, which kills `testDebugUnitTest` with `ClassNotFoundException:
  GradleWorkerMain`).
- Quality gate (all must pass before commit):
  ```bash
  cd memo-android && bash tools/quality_gate.sh
  ```
  which runs aggregate `lintDebug` + `testDebugUnitTest` across **all** modules,
  `:app:assembleDebug`, then two presence checks: every module with sources must
  have at least one test, and all four generators must produce no diff.
  CI `.github/workflows/android-pr-check.yml` enforces the same gates.
- Generated resources are committed and must stay in sync: ARB→strings via
  `tools/arb_to_android.py` (brandifies `Kelivo`/`kelivo`→`Memo`/`memo`), drift
  schema→SQL via `tools/drift_schema_to_sql.py`, palettes via
  `tools/palettes_gen.py`, settings keys via `tools/settings_keys_gen.py`.
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
- **Preserve the current Memo UI/UX** — the 1:1-ported visual style and interaction feel is intentional. Don't refactor or swap components for "modern patterns" / library upgrades / cleanliness without explicit approval; user prefers the current look. Visible-behavior changes (animations, transitions, gestures, spacing, color, type ramp, motion) need plan-then-confirm. Library/architecture swaps (e.g. swapping `MemoSnackbar` for `io.github.dokar3:sonner`) are allowed only when the *visible* UI/UX is preserved.

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
