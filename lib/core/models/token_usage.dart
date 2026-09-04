class TokenUsage {
  final int promptTokens;
  final int completionTokens;
  final int cachedTokens;
  final int totalTokens;

  const TokenUsage({
    this.promptTokens = 0,
    this.completionTokens = 0,
    this.cachedTokens = 0,
    this.totalTokens = 0,
  });

  /// Intra-round only (Claude two-half splice, Gemini usageMetadata replay).
  /// Use [accumulate] to add settled rounds together.
  TokenUsage merge(TokenUsage other) {
    // For streaming responses:
    // - prompt tokens: take max (usually stays constant after initial value)
    // - completion tokens: take max (grows as response streams)
    // - cached tokens: take max (usually set once)
    final prompt = other.promptTokens > 0 ? other.promptTokens : promptTokens;
    final completion = other.completionTokens > 0
        ? other.completionTokens
        : completionTokens;
    final cached = other.cachedTokens > 0 ? other.cachedTokens : cachedTokens;
    final splitTotal = prompt + completion;
    final explicitTotal = other.totalTokens > 0
        ? other.totalTokens
        : totalTokens;
    final total = splitTotal > 0 ? splitTotal : explicitTotal;
    return TokenUsage(
      promptTokens: prompt,
      completionTokens: completion,
      cachedTokens: cached,
      totalTokens: total,
    );
  }

  TokenUsage accumulate(TokenUsage other) {
    final prompt = promptTokens + other.promptTokens;
    final completion = completionTokens + other.completionTokens;
    final cached = cachedTokens + other.cachedTokens;
    final splitTotal = prompt + completion;
    return TokenUsage(
      promptTokens: prompt,
      completionTokens: completion,
      cachedTokens: cached,
      // same as merge: use split when present, else sum explicit totals
      totalTokens: splitTotal > 0
          ? splitTotal
          : totalTokens + other.totalTokens,
    );
  }
}
