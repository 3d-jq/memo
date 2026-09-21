# -*- coding: utf-8 -*-
"""Convert memo ARB localizations to Android strings.xml resources.

Sources of truth (lib/l10n/ of the Flutter repo):
  app_en.arb    -> core/ui/src/main/res/values/strings.xml
  app_zh.arb    -> core/ui/src/main/res/values-zh/strings.xml  (zh.arb is the
                   complete Simplified Chinese set; app_zh_Hans.arb is an older
                   subset, so it is not used)
  app_zh_Hant.arb -> core/ui/src/main/res/values-zh-rTW/strings.xml

Rules:
  - every locale must contain the full en key set (checked)
  - ARB placeholders {name} become positional %1$s, %2$s ... in text order
  - keys are lower_snake_cased; collisions and invalid names abort the run
  - only strings with placeholders get their stray '%' doubled (they are
    Android format strings); unparameterized strings keep '%' literal
  - ICU plural/select messages ({n, plural, ...}) are emitted verbatim with
    formatted="false" and are rendered at runtime with
    android.icu.text.MessageFormat (semantically identical to Dart intl).
    A key is an ICU key if ANY locale uses ICU syntax; plain translations of
    such a key ({count} only) are also emitted raw, since MessageFormat
    understands plain {name} arguments.
  - XML escaping applied for & < > (formatted strings additionally \' \"
    since aapt still consumes the apostrophe escape when formatted=true;
    formatted=false strings must contain no apostrophe at all because it is
    MessageFormat's quote character - asserted)
Usage: python tools/arb_to_android.py
"""
from __future__ import print_function

import json
import os
from upstream_root import upstream_root
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
L10N = os.path.join(upstream_root(HERE), 'lib', 'l10n')
RES = os.path.normpath(os.path.join(HERE, '..', 'core', 'ui', 'src', 'main', 'res'))

LOCALES = [
    ('app_en.arb', 'values'),
    ('app_zh.arb', 'values-zh'),
    ('app_zh_Hant.arb', 'values-zh-rTW'),
]

# Brand: user-visible product name is "Memo"; the upstream ARB (Flutter source)
# still says "Kelivo", so every value is brandified on the way out. Applied
# after all validation so the source ARB stays the upstream file.
BRAND_MAP = [('Kelivo', 'Memo'), ('kelivo', 'memo')]


def brandify(text):
    for old, new in BRAND_MAP:
        text = text.replace(old, new)
    return text

PLACEHOLDER = re.compile(r'\{([a-zA-Z0-9_]+)\}')
ICU_CLAUSE = re.compile(r'\{[a-zA-Z0-9_]+\s*,\s*(plural|select)\s*,')
VALID_KEY = re.compile(r'^[a-zA-Z_][a-zA-Z0-9_]*$')


def snake(key):
    return re.sub(r'(?<!^)(?=[A-Z])', '_', key).lower()


def to_android(key):
    return re.sub(r'[^a-zA-Z0-9_]', '_', key)


def arg_names(text):
    """Set of {name} argument references in an (ICU or plain) message."""
    return set(PLACEHOLDER.findall(text))


def convert_plain(text):
    """Two-pass: first double stray %, then insert positional specifiers."""
    names = PLACEHOLDER.findall(text)
    escaped = PLACEHOLDER.sub('\x00', text)
    if names:
        escaped = escaped.replace('%', '%%')
    idx = [0]

    def repl(m):
        idx[0] += 1
        return '%%%d$s' % idx[0]

    final = re.sub('\x00', lambda m: repl(m), escaped)
    return final


def xml_escape(text):
    out = text.replace('&', '&amp;')
    out = out.replace('<', '&lt;')
    out = out.replace('>', '&gt;')
    out = out.replace("'", "\\'")
    out = out.replace('"', '\\"')
    return out


def xml_escape_raw(text):
    """Escape for formatted=false entries; apostrophe must not appear at all."""
    out = text.replace('&', '&amp;')
    out = out.replace('<', '&lt;')
    out = out.replace('>', '&gt;')
    return out


def load_entries(path):
    with open(path, 'r', encoding='utf-8') as f:
        data = json.load(f)
    return {k: v for k, v in data.items() if not k.startswith('@')}


def main():
    en = load_entries(os.path.join(L10N, 'app_en.arb'))
    all_entries = []
    for fn, folder in LOCALES:
        entries = load_entries(os.path.join(L10N, fn))
        if set(entries) != set(en):
            print('error: %s key set differs from app_en.arb (missing=%d extra=%d)'
                  % (fn, len(set(en) - set(entries)), len(set(entries) - set(en))),
                  file=sys.stderr)
            return 1
        all_entries.append((folder, entries))

    # key mapping sanity across whole corpus
    mapped = {}
    for folder, entries in all_entries:
        for key in entries:
            sk = snake(key)
            if sk != key and not VALID_KEY.match(key):
                print('error: key has invalid chars: %r' % key, file=sys.stderr)
                return 1
            android_key = to_android(sk)
            if android_key in mapped and mapped[android_key] != key:
                print('error: snake collision %r <-> %r -> %r'
                      % (mapped[android_key], key, android_key), file=sys.stderr)
                return 1
            mapped[android_key] = key

    # A key uses raw ICU output when any locale message uses ICU syntax.
    def icu_special(key):
        for folder, entries in all_entries:
            text = entries[key]
            if isinstance(text, str) and ICU_CLAUSE.search(text):
                return True
        return False

    icu_keys = [k for k in en if icu_special(k)]

    for folder, entries in all_entries:
        out_dir = os.path.join(RES, folder)
        os.makedirs(out_dir, exist_ok=True)
        lines = []
        lines.append('<?xml version="1.0" encoding="utf-8"?>')
        lines.append('<!-- GENERATED by tools/arb_to_android.py from lib/l10n/*.arb - DO NOT EDIT -->')
        lines.append('<resources>')
        for key in sorted(entries.keys()):
            text = brandify(entries[key])
            if not isinstance(text, str):
                continue  # metadata dicts are not plain strings
            android_key = to_android(snake(key)).replace('kelivo', 'memo')
            if key in icu_keys:
                if "'" in text:
                    print('error: ICU key %r contains apostrophe in %s; '
                          'MessageFormat quoting not supported' % (key, folder),
                          file=sys.stderr)
                    return 1
                lines.append('    <string name="%s" formatted="false">%s</string>'
                             % (android_key, xml_escape_raw(text)))
            else:
                converted = convert_plain(text)
                lines.append('    <string name="%s">%s</string>'
                             % (android_key, xml_escape(converted)))
        lines.append('</resources>')
        with open(os.path.join(out_dir, 'strings.xml'), 'w', encoding='utf-8',
                  newline='\n') as f:
            f.write('\n'.join(lines) + '\n')
        print('%s: wrote %d strings (incl %d ICU) -> %s'
              % (folder, len(entries), len(icu_keys), out_dir))

    # arg-name consistency across locales
    bad = 0
    for key in en:
        base_args = None
        for folder, entries in all_entries:
            text = entries[key]
            args = arg_names(text)
            if key in icu_keys:
                if base_args is None:
                    base_args = args
                elif args - base_args:
                    print('error: %r has extra arg names in %s: %s' %
                          (key, folder, sorted(args - base_args)), file=sys.stderr)
                    bad += 1
            else:
                if base_args is None:
                    base_args = args
                elif len(args) != len(base_args):
                    print('error: placeholder count differs for %s in %s (%d vs %d)'
                          % (key, folder, len(base_args), len(args)), file=sys.stderr)
                    bad += 1
    if bad:
        print('error: %d arg mismatches' % bad, file=sys.stderr)
        return 1
    print('placeholder/arg consistency OK across locales (%d ICU keys: %s)'
          % (len(icu_keys), ', '.join(icu_keys)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
