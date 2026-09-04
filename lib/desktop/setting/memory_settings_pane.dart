import 'package:flutter/material.dart';

import '../../features/settings/pages/memory_settings_page.dart';
import '../../l10n/app_localizations.dart';
import '../../theme/app_font_weights.dart';

/// Desktop right-side pane for global memory settings.
class DesktopMemorySettingsPane extends StatelessWidget {
  const DesktopMemorySettingsPane({super.key});

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    final cs = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 8),
          child: Text(
            l10n.memorySettingsPageTitle,
            style: TextStyle(
              fontSize: 18,
              fontWeight: AppFontWeights.semibold,
              color: cs.onSurface,
            ),
          ),
        ),
        const Expanded(
          child: MemorySettingsContent(
            padding: EdgeInsets.fromLTRB(20, 8, 20, 24),
          ),
        ),
      ],
    );
  }
}
