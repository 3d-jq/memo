import 'package:flutter_test/flutter_test.dart';

import 'package:Kelivo/core/models/token_usage.dart';

void main() {
  group('TokenUsage', () {
    test(
      'merge preserves explicit total when split token fields are missing',
      () {
        final merged = const TokenUsage().merge(
          const TokenUsage(totalTokens: 895),
        );

        expect(merged.promptTokens, 0);
        expect(merged.completionTokens, 0);
        expect(merged.cachedTokens, 0);
        expect(merged.totalTokens, 895);
      },
    );

    test('merge splices Claude halves instead of summing both sides', () {
      final merged = const TokenUsage(
        promptTokens: 100,
        completionTokens: 0,
      ).merge(const TokenUsage(promptTokens: 0, completionTokens: 20));

      expect(merged.promptTokens, 100);
      expect(merged.completionTokens, 20);
      expect(merged.totalTokens, 120);
    });

    test('accumulate sums three settled rounds', () {
      final summed = const TokenUsage(promptTokens: 100, completionTokens: 20)
          .accumulate(const TokenUsage(promptTokens: 300, completionTokens: 40))
          .accumulate(
            const TokenUsage(promptTokens: 500, completionTokens: 10),
          );

      expect(summed.promptTokens, 900);
      expect(summed.completionTokens, 70);
      expect(summed.totalTokens, 970);
    });

    test(
      'accumulate sums explicit totals, then ignores them once split exists',
      () {
        final totalsOnly = const TokenUsage(
          totalTokens: 50,
        ).accumulate(const TokenUsage(totalTokens: 80));
        expect(totalsOnly.promptTokens, 0);
        expect(totalsOnly.completionTokens, 0);
        expect(totalsOnly.totalTokens, 130);

        final splitAfterEstimate = totalsOnly.accumulate(
          const TokenUsage(
            promptTokens: 100,
            completionTokens: 20,
            totalTokens: 999,
          ),
        );
        expect(splitAfterEstimate.promptTokens, 100);
        expect(splitAfterEstimate.completionTokens, 20);
        expect(splitAfterEstimate.totalTokens, 120);

        final estimateAfterSplit = splitAfterEstimate.accumulate(
          const TokenUsage(totalTokens: 80),
        );
        expect(estimateAfterSplit.promptTokens, 100);
        expect(estimateAfterSplit.completionTokens, 20);
        expect(estimateAfterSplit.totalTokens, 120);
      },
    );
  });
}
