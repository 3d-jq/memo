#!/usr/bin/env bash
# Memo (memo-android) quality gates — the local mirror of
# .github/workflows/android-pr-check.yml.
#
# Usage: tools/quality_gate.sh
#
# Gates (all must pass before commit / PR):
#   1. lintDebug            — Android Lint, every module
#   2. testDebugUnitTest    — JVM unit tests, every module
#   3. :app:assembleDebug   — build
#   4. every module with sources has at least one test file
#   5. the committed SQLite DDL matches drift_schemas/  — the only generator left
#
# Env notes: system JAVA_HOME points at jdk-13 (breaks AGP) and the default
# GRADLE_USER_HOME may sit under a non-ASCII user home (e.g. `C:\Users\<你的名字>\.gradle`),
# which makes Gradle's `@argfile` worker classpath unreadable on CP936 JVMs.
# This script pins both.
set -euo pipefail

cd "$(dirname "$0")/.."

# Machine-specific pins (a JDK 21 path, a Gradle cache outside the home dir) live in
# tools/quality_gate.local.sh, which is git-ignored. Without it the inherited
# environment is used as-is — CI and other machines provide their own.
here="$(cd "$(dirname "$0")" && pwd)"
[ -f "$here/quality_gate.local.sh" ] && . "$here/quality_gate.local.sh"

echo "==> Memo quality gate"
echo "    JAVA_HOME=$JAVA_HOME"
echo "    GRADLE_USER_HOME=$GRADLE_USER_HOME"

GRADLE_ARGS=(--console=plain --stacktrace)

echo "==> [1/5] Lint (all modules)"
./gradlew "${GRADLE_ARGS[@]}" --continue lintDebug

echo "==> [2/5] Unit tests (all modules)"
./gradlew "${GRADLE_ARGS[@]}" --continue testDebugUnitTest

echo "==> [3/5] Assemble debug APK"
./gradlew "${GRADLE_ARGS[@]}" :app:assembleDebug

echo "==> [4/5] Every module with sources has tests"
MODULES=(app core/common core/ui core/highlight core/data core/llm core/workspace feature/chat feature/assistant feature/settings feature/utility)

# find exits 1 on a missing path, which under pipefail would kill the assignment.
count_files() {
    if [ -d "$1" ]; then
        find "$1" -name "$2" | wc -l
    else
        echo 0
    fi
}

missing=0
for m in "${MODULES[@]}"; do
    main=$(count_files "$m/src/main" '*.kt')
    test=$(($(count_files "$m/src/test" '*Test.kt') + $(count_files "$m/src/androidTest" '*Test.kt')))
    if [ "$main" -gt 0 ] && [ "$test" -eq 0 ]; then
        echo "    FAIL $m: $main source files, no tests"
        missing=1
    fi
done
[ "$missing" -eq 0 ] || exit 1

echo "==> [5/5] Generated resources are up to date"
# 只剩数据库 DDL 是生成的（输入 drift_schemas/ 就在仓内）。界面文案、色板、偏好键表
# 自 2026-09-24 与上游解耦后直接手维 —— 那三个生成器没有输入，已随上游一起移出仓库。
GENERATED_PATHS=(core/data/src/main)
# core.autocrlf=true smudges checked-out text to CRLF while the committed blobs
# are LF, so hash content with carriage returns stripped; otherwise the first
# regeneration looks like drift.
hash_generated() {
    find "${GENERATED_PATHS[@]}" -type f -print0 | sort -z |
        while IFS= read -r -d '' f; do
            printf '%s %s\n' "$(tr -d '\r' < "$f" | md5sum | cut -d' ' -f1)" "$f"
        done | md5sum
}

before=$(hash_generated)
python tools/drift_schema_to_sql.py
if [ "$before" != "$(hash_generated)" ]; then
    echo "    FAIL generated resources are stale — commit the regenerated files above"
    git status --short -- "${GENERATED_PATHS[@]}"
    exit 1
fi

echo "==> Quality gate passed ✔"
