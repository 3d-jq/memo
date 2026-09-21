NOTICE
======

Memo — native Android client
Copyright (C) 2026 Memo contributors

This program is free software licensed under the GNU Affero General Public
License, version 3.0 (see LICENSE).

Third-party attribution
-----------------------

This repository is a **derivative work** of the Flutter application **kelivo**
by Chevey339, licensed under the GNU Affero General Public License v3.0.
Source: https://github.com/Chevey339/kelivo

Nearly everything in this repository was translated 1:1 from that project's
Dart source — screens, layout metrics, string content, iconography and
behaviour. The translation is documented per-batch in `docs/PORTING.md`, which
names the Dart file and line range behind each Kotlin composable, and records
every place where this port deliberately deviates from upstream.

Native-side implementation details (platform integrations, provider/streaming
plumbing, theming) additionally reference **RikkaHub**, also licensed under the
GNU Affero General Public License v3.0. Source:
https://github.com/rikkahub/rikkahub

Generated-resource inputs
-------------------------

`upstream/` holds the subset of the kelivo Dart source that this repository's
code generators read:

    upstream/lib/l10n/*.arb                        UI strings  -> res/values*/strings.xml
    upstream/lib/theme/palettes.dart               palettes    -> core/ui/.../Palettes.kt
    upstream/lib/core/database/business_*.dart     settings keys -> SettingsKeyRegistry.kt
    upstream/drift_schemas/.../drift_schema_v3.json  SQLite DDL -> assets/memo_schema_v3.sql

They are upstream copyright works redistributed here under AGPL-3.0 so that the
quality gate can run from a standalone checkout. They are inputs only — nothing
in this repository is compiled from Dart.

Data compatibility
------------------

Despite sharing a schema lineage, this app is **not** data-compatible with the
Flutter original: the database is `memo.db` and the application id is
`com.psyche.memo`. The SQLite DDL is generated from upstream's drift schema
because it is a proven schema, not for interoperability.
