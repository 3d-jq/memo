/// Heuristic token estimate for UI (`~N tokens`).
///
/// CJK characters count as 1 token each; remaining characters use 4 chars/token.
int estimateTokens(String text) {
  if (text.isEmpty) return 0;
  var cjk = 0;
  var other = 0;
  for (final unit in text.runes) {
    if (_isCjk(unit)) {
      cjk++;
    } else {
      other++;
    }
  }
  return cjk + (other + 3) ~/ 4;
}

bool _isCjk(int code) {
  return (code >= 0x3400 && code <= 0x4DBF) ||
      (code >= 0x4E00 && code <= 0x9FFF) ||
      (code >= 0xF900 && code <= 0xFAFF) ||
      (code >= 0x20000 && code <= 0x2A6DF) ||
      (code >= 0x2A700 && code <= 0x2B73F) ||
      (code >= 0x2B740 && code <= 0x2B81F) ||
      (code >= 0x3000 && code <= 0x303F) ||
      (code >= 0x3040 && code <= 0x309F) ||
      (code >= 0x30A0 && code <= 0x30FF) ||
      (code >= 0xAC00 && code <= 0xD7AF) ||
      (code >= 0xFF00 && code <= 0xFFEF);
}
