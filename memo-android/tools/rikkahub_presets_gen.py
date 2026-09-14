# -*- coding: utf-8 -*-
"""Generate RikkaHubPresets.kt from RikkaHub's preset theme sources (read-only).

Ported from RikkaHub (https://github.com/rikkahub/rikkahub, AGPL-3.0 — same
license as Memo); the user asked for RikkaHub's richer theme set while keeping
Memo's own default theme and Memo's theme-settings UI/UX.

Reads `<rikkahub>/app/src/main/java/me/rerere/rikkahub/ui/theme/presets/*.kt`
plus the two `strings.xml` files (for the zh/en theme names) and transcribes:

    private val primaryLight = Color(0xFFC96442)
    private val lightScheme = lightColorScheme(primary = primaryLight, ...)

into Memo's `Palette` shape (see Palettes.kt, which is generated from the
Flutter source and must stay untouched):

    val rikkahubClaudePalette = Palette(
        id = "claude", zhName = "Claude", enName = "Claude",
        light = lightColorScheme(primary = Color(0xFFC96442), ...),
        dark = darkColorScheme(...),
    )

This generator is a one-off transcription helper: its output is committed, and
it is deliberately NOT part of the quality gate's generator list (it needs the
RikkaHub checkout, which CI does not have).

Usage:
    python tools/rikkahub_presets_gen.py [--src <rikkahub repo root>]
Env:
    RIKKAHUB_REF  alternative way to point at the RikkaHub checkout
"""
from __future__ import print_function

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_SRC = os.path.normpath(os.path.join(HERE, '..', '..', '..', '.rikkahub-ref'))
OUT_KT = os.path.normpath(os.path.join(HERE, '..', 'core', 'ui', 'src', 'main', 'java',
                                        'com', 'psyche', 'memo', 'ui', 'theme',
                                        'RikkaHubPresets.kt'))

# RikkaHub's PresetTheme registry order (PresetTheme.kt:24-34).
PRESETS = [
    ('SakuraTheme.kt', 'rikkahubSakuraPalette'),
    ('OceanTheme.kt', 'rikkahubOceanPalette'),
    ('SpringTheme.kt', 'rikkahubSpringPalette'),
    ('AutumnTheme.kt', 'rikkahubAutumnPalette'),
    ('BlackTheme.kt', 'rikkahubBlackPalette'),
    ('MinimalTheme.kt', 'rikkahubMinimalPalette'),
    ('ClaudeTheme.kt', 'rikkahubClaudePalette'),
]

COLOR_RE = re.compile(r'private val (\w+)\s*=\s*Color\((0x[0-9A-Fa-f]{8})\)')
ID_RE = re.compile(r'\bid\s*=\s*"([\w-]+)"')
NAME_KEY_RE = re.compile(r'R\.string\.(theme_name_\w+)')
SCHEME_RE = re.compile(r'(light|dark)ColorScheme\s*\(')
ARG_RE = re.compile(r'(\w+)\s*=\s*(\w+)')
STRLINE_RE = re.compile(r'<string name="([^"]+)">(.*?)</string>', re.S)


def balanced(text, open_idx):
    """End index (exclusive) of the paren group starting at text[open_idx] == '('."""
    depth = 0
    for i in range(open_idx, len(text)):
        if text[i] == '(':
            depth += 1
        elif text[i] == ')':
            depth -= 1
            if depth == 0:
                return i
    raise ValueError('unbalanced parens from %d' % open_idx)


def read_strings(path):
    if not os.path.isfile(path):
        return {}
    with open(path, 'r', encoding='utf-8') as f:
        text = f.read()
    out = {}
    for key, value in STRLINE_RE.findall(text):
        # Undo the escapes Android string resources use for the names we need.
        out[key] = value.replace('\\@', '@').replace("\\'", "'").replace('\\"', '"').strip()
    return out


def scheme_args(text, variant, colors):
    """Ordered (role, hex) pairs inside the `light|darkColorScheme(...)` block."""
    match = SCHEME_RE.search(text)
    if match is None:
        raise ValueError('no ColorScheme block')
    # A file has exactly one light and one dark block; find the one we want.
    for m in SCHEME_RE.finditer(text):
        if m.group(1) != variant:
            continue
        open_idx = text.index('(', m.start())
        block = text[open_idx:balanced(text, open_idx) + 1]
        args = []
        for role, ref in ARG_RE.findall(block):
            if role in ('lightColorScheme', 'darkColorScheme'):
                continue
            hex_value = colors.get(ref)
            if hex_value is None:
                continue
            args.append((role, hex_value))
        return args
    raise ValueError('no %sColorScheme block' % variant)


def main(argv):
    src = os.environ.get('RIKKAHUB_REF') or DEFAULT_SRC
    if '--src' in argv:
        src = argv[argv.index('--src') + 1]
    src = os.path.normpath(src)
    presets_dir = os.path.join(src, 'app', 'src', 'main', 'java', 'me', 'rerere',
                              'rikkahub', 'ui', 'theme', 'presets')
    if not os.path.isdir(presets_dir):
        print('error: RikkaHub presets not found at %s\n'
              '       pass --src <rikkahub repo root> or set RIKKAHUB_REF' % presets_dir,
              file=sys.stderr)
        return 1

    zh = read_strings(os.path.join(src, 'app', 'src', 'main', 'res', 'values-zh', 'strings.xml'))
    en = read_strings(os.path.join(src, 'app', 'src', 'main', 'res', 'values', 'strings.xml'))

    entries = []
    for filename, var_name in PRESETS:
        path = os.path.join(presets_dir, filename)
        with open(path, 'r', encoding='utf-8') as f:
            text = f.read()
        colors = {name: hex_value for name, hex_value in COLOR_RE.findall(text)}
        pid = ID_RE.search(text)
        name_key = NAME_KEY_RE.search(text)
        if pid is None or name_key is None:
            print('error: %s: missing id/name string' % filename, file=sys.stderr)
            return 1
        pid = pid.group(1)
        en_name = en.get(name_key.group(1), pid)
        zh_name = zh.get(name_key.group(1), en_name)
        light = scheme_args(text, 'light', colors)
        dark = scheme_args(text, 'dark', colors)
        if not light or not dark:
            print('error: %s: empty scheme' % filename, file=sys.stderr)
            return 1
        entries.append((var_name, pid, zh_name, en_name, light, dark))

    out = []
    out.append('// GENERATED by tools/rikkahub_presets_gen.py from RikkaHub '
               '(app/src/main/java/me/rerere/rikkahub/ui/theme/presets/) - DO NOT EDIT')
    out.append('//')
    out.append('// RikkaHub 的 7 套预设主题（AGPL-3.0，与本工程同许可），用户 2026-09-13 点名')
    out.append('// 「我们这个八个效果不好，用 RikkaHub 那个主题，他那个更全面」，同时要求：')
    out.append('// **Memo 默认主题保留**、**主题设置页的 UI/UX 不动**（只换列表内容）。')
    out.append('//')
    out.append('// 有意决定（勿"修回"）：预设只提供**配色身份**，面板层次仍走 Memo 自己的')
    out.append('// SurfaceLadder —— MemoTheme.colorScheme() 会重算 surfaceContainer*（面板/卡片那一层）。')
    out.append('// 理由：Memo 全站 226 处面板用的是语义 token（surfaceCard/surfaceFill，由 SurfaceLadder')
    out.append('// 派生），只有约 25 处直接读 colorScheme.surfaceContainer*；若给预设开"原样表面"通道，')
    out.append('// 就会变成"少数面板是 RikkaHub 的色、大多数是 Memo 的色"的混搭。所以')
    out.append('// RikkaHub 预设里声明的 surfaceDim/surfaceBright/surfaceContainer* 照样进入 ColorScheme，')
    out.append('// 但运行时由 Memo 的面板系统统一覆盖 —— 这就是用户要的「UI/UX 不改」。')
    out.append('package com.psyche.memo.ui.theme')
    out.append('')
    out.append('import androidx.compose.material3.darkColorScheme')
    out.append('import androidx.compose.material3.lightColorScheme')
    out.append('import androidx.compose.ui.graphics.Color')
    out.append('')

    for var_name, pid, zh_name, en_name, light, dark in entries:
        out.append('val %s = Palette(' % var_name)
        out.append('    id = "%s",' % pid)
        out.append('    zhName = "%s",' % zh_name)
        out.append('    enName = "%s",' % en_name)
        for role_args, ctor in ((light, 'lightColorScheme'), (dark, 'darkColorScheme')):
            out.append('    %s = %s(' % ('light' if ctor == 'lightColorScheme' else 'dark', ctor))
            for role, hex_value in role_args:
                out.append('        %s = Color(0x%s),' % (role, hex_value[2:].upper()))
            out.append('    ),')
        out.append(')')
        out.append('')

    out.append('/** RikkaHub 预设主题，顺序与上游 PresetTheme.kt 的注册表一致。 */')
    out.append('val rikkahubPresets: List<Palette> = listOf(')
    for var_name, _, _, _, _, _ in entries:
        out.append('    %s,' % var_name)
    out.append(')')
    out.append('')
    out.append('/**')
    out.append(' * 走「原样表面」通道的调色板：预设自带完整的中性阶梯')
    out.append(' * （surface、surfaceContainerLowest…surfaceContainerHighest、surfaceDim、')
    out.append(' * surfaceBright、surfaceVariant），')
    out.append(' * 直接采信它们，主题才铺满整个界面（`MemoTheme.authoredColorScheme` +')
    out.append(' * `AppSemanticColors.authored`）。Memo 自己那 9 套不走这条 ——')
    out.append(' * 它们的容器在生成时被压平到 surface，走原样会没有层次。')
    out.append(' *')
    out.append(' * 用户 2026-09-13：「语义 token（surfaceCard/surfaceFill）用得 226 处…')
    out.append(' * 我们要更 rikkhub 一样覆盖多，不然主题不好看」。')
    out.append(' */')
    out.append('val authoredSurfacePaletteIds: Set<String> = setOf(')
    for _, pid, _, _, _, _ in entries:
        out.append('    "%s",' % pid)
    out.append(')')
    out.append('')
    out.append('/**')
    out.append(' * 主题设置页列出的内置主题：Memo 默认主题 + RikkaHub 预设。')
    out.append(' * Memo 自己那 8 套（blue/green/purple/yellow/smokyRose/terracotta/monochrome/docTheme）')
    out.append(' * 不再出现在列表里，但 id 仍能解析（paletteById 兜底），老用户已选的主题不会失效。')
    out.append(' */')
    out.append('val themeChoices: List<Palette> = listOf(defaultPalette) + rikkahubPresets')
    out.append('')
    out.append('/** id -> Palette：RikkaHub 预设优先，其次 Memo 生成的调色板，最后回落默认。 */')
    out.append('fun themePaletteById(id: String): Palette =')
    out.append('    rikkahubPresets.firstOrNull { it.id == id } ?: paletteById(id)')
    out.append('')

    os.makedirs(os.path.dirname(OUT_KT), exist_ok=True)
    with open(OUT_KT, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(out))
    print('wrote %s (%d presets)' % (OUT_KT, len(entries)))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
