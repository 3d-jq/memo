#!/usr/bin/env bash
# Memo (kelivo-android) quality gates.
#
# Usage: tools/quality_gate.sh [--skip-format]
#
# Gates (all must pass before commit / PR):
#   1. Kotlin formatting           — ktlint (gradle kotlin-format via spotless if enabled, else skip)
#   2. :app:lintDebug              — Android Lint
#   3. :app:testDebugUnitTest      — JVM unit tests for every module
#   4. :app:assembleDebug          — build
#
# Env notes: system JAVA_HOME points at jdk-13 (breaks AGP) and the default
# GRADLE_USER_HOME sits under a Chinese username (`C:\Users\邓嘉权\.gradle`),
# which makes Gradle's `@argfile` worker classpath unreadable on CP936 JVMs.
# This script pins both.
set -euo pipefail

cd "$(dirname "$0")/.."

# Pinned regardless of the inherited (broken jdk-13) environment value.
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-D:/DevCache/.gradle}"

echo "==> Memo quality gate"
echo "    JAVA_HOME=$JAVA_HOME"
echo "    GRADLE_USER_HOME=$GRADLE_USER_HOME"

GRADLE_ARGS=(--console=plain --stacktrace)

echo "==> [1/4] Lint"
./gradlew "${GRADLE_ARGS[@]}" :app:lintDebug

echo "==> [2/4] Unit tests (all modules)"
./gradlew "${GRADLE_ARGS[@]}" :app:testDebugUnitTest

echo "==> [3/4] Assemble debug APK"
./gradlew "${GRADLE_ARGS[@]}" :app:assembleDebug

echo "==> Quality gate passed ✔"
