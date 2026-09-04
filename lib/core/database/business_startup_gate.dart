import 'dart:developer' as developer;

import 'package:flutter/foundation.dart';

import 'business_migration_engine.dart';
import 'business_preferences.dart';
import 'business_repository.dart';

/// Completes the one-time legacy migration before exposing business state.
///
/// Keeping this gate outside the widget tree prevents providers from observing
/// an empty database when migration, verification, or legacy cleanup fails.
final class BusinessStartupGate {
  BusinessStartupGate._();

  /// Non-null when the last [migrateAndLoad] degraded a recoverable business
  /// migration validation failure instead of migrating cleanly. The legacy
  /// preferences source is retained (the failed migration transaction rolled
  /// back and wrote no receipt), so a fixed future build can retry.
  static String? lastDegradedReason;

  // Validation-class failures that must degrade rather than lock the user out
  // of the app. Anything else (e.g. a genuine database fault) still fails
  // closed so we never hide real corruption behind an empty settings screen.
  static bool _isRecoverableMigrationFailure(Object error) =>
      error is StateError &&
      (error.message == 'business_migration_export_mismatch' ||
          error.message.startsWith('business_migration_count:'));

  static Future<BusinessPreferences> migrateAndLoad({
    required BusinessRepository repository,
    required LegacyBusinessPreferences legacyPreferences,
    @visibleForTesting Future<void> Function()? debugRunMigration,
  }) async {
    lastDegradedReason = null;
    try {
      await (debugRunMigration ??
          BusinessMigrationEngine(
            repository: repository,
            legacyPreferences: legacyPreferences,
          ).run)();
    } catch (error, stackTrace) {
      if (!_isRecoverableMigrationFailure(error)) rethrow;
      // The migration transaction rolled back, so the database holds no
      // migrated business data and no receipt. Entering with defaults keeps
      // the user in the app (and their legacy preference data intact for a
      // later retry) instead of trapping them behind a fail-closed startup
      // screen.
      lastDegradedReason = (error as StateError).message;
      developer.log(
        'Business migration degraded; entering with defaults and retaining '
        'legacy data for a future retry.',
        name: 'Kelivo.business.migration',
        error: error,
        stackTrace: stackTrace,
      );
    }
    final preferences = BusinessPreferences(repository);
    await preferences.load();
    return preferences;
  }
}
