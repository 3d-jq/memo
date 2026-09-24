NOTICE
======

Memo — native Android LLM chat client
Copyright (C) 2026 Memo contributors

This program is free software licensed under the GNU Affero General Public
License, version 3.0 (see LICENSE).

Third-party attribution
-----------------------

Memo's product shape, interface language and interaction design originate from
the Flutter application **kelivo** by Chevey339, also licensed under the GNU
Affero General Public License v3.0.
Source: https://github.com/Chevey339/kelivo

Memo re-implements that design as a native Kotlin / Jetpack Compose application
for Android, and extends it with native-only behaviour. Source-level comments
carry provenance markers naming the upstream file or component an
implementation was derived from, so individual decisions can be traced back.

Native platform integrations and several capabilities additionally reference
**RikkaHub**, also licensed under AGPL-3.0: network TTS and ASR, theme presets,
agent skills, the proot sandbox and its terminal, plus notifications, in-app
updates, permission handling and file sharing.
Source: https://github.com/rikkahub/rikkahub

Note that kelivo's own interface design was in turn inspired by RikkaHub, so
these lines converge visually.

The context-compaction threshold mechanism follows **opencode**.
Source: https://github.com/sst/opencode

Data compatibility
------------------

Memo is a standalone application: the application id is `com.psyche.memo` and
the database is `memo.db`. It is **not** data-compatible with the Flutter
original and backups are not interchangeable. The SQLite DDL in
`core/data/src/main/assets/memo_schema_v3.sql` is generated from
`drift_schemas/app_database/drift_schema_v3.json`; that schema is kept because
it is proven, not for interoperability.
