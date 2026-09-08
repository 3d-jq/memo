# Product

## Register

product

## Users

People who use multiple LLM providers across mobile and desktop and need a dependable, configurable chat workspace for text, files, tools, search, voice, and custom assistants.

## Product Purpose

Kelivo makes advanced LLM capabilities practical in one cross-platform client while keeping conversations, settings, and user-owned data portable and recoverable.

## Surfaces

- **Kelivo (Flutter)** — the original cross-platform app (`lib/`), source of truth for every screen, string, and behavior.
- **Memo (native Android)** — a 1:1 native port in `memo-android/` (Kotlin + Jetpack Compose, package `com.psyche.memo`). Same features and flows as the Flutter app, Memo-branded, with its own database (`memo.db`). Port progress and per-batch specs live in `memo-android/docs/PORTING.md`.

## Brand Personality

Practical, calm, and capable. The interface should feel familiar to Material You users, stay out of the conversation, and explain exceptional states without alarming or trapping people.

## Anti-references

Avoid harsh monochrome outlines, decorative recovery screens, diagnostic-only dead ends, generic AI-dashboard styling, and controls that diverge from the established Material 3 vocabulary. RikkaHub's practical information density and interaction clarity are positive references, not surfaces to copy verbatim.

## Design Principles

- Keep the conversation and the user's current task visually primary.
- Make safety mechanisms actionable and understandable, not merely technically correct.
- Preserve familiar platform and Material 3 interaction patterns across form factors.
- Use restrained hierarchy and color so status is clear without visual noise.
- Prefer recoverable, user-owned data flows with explicit outcomes.

## Accessibility & Inclusion

Support light and dark themes, dynamic color, localization, scalable text, keyboard and touch input, sufficient text contrast, meaningful semantics, and reduced-motion-friendly state changes across Android, iOS, Windows, macOS, and Linux.
