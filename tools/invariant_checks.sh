#!/usr/bin/env bash
# 机械可检的不变式门禁 —— 每条规则都是某次真实事故的化石。
# 规范与本表的出处：docs/ENGINEERING_HARNESS.md §5（照 deepseek-harness 的
# 「把可机械检查的不变式接进真会跑的门禁，并证明它能拒绝无效输入」）。
#
# 用法：
#   bash tools/invariant_checks.sh             # 检查整个仓库
#   bash tools/invariant_checks.sh --self-test # 只喂反例，证明每条规则真的会红
#
# 加新规则时：① 在本文件加一条（硬规则或棘轮）；② 在 docs/ENGINEERING_HARNESS.md §5
# 补一行「规则 → 事故」；③ 在 --self-test 里给它一个故意的坏样例。

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SELF_TEST=0
[[ "${1:-}" == "--self-test" ]] && SELF_TEST=1

FAIL=0
pass() { printf '  \033[32m✅ %s\033[0m\n' "$1"; }
fail() { printf '  \033[31m❌ %s\033[0m\n' "$1"; FAIL=1; }
info() { printf '  \033[33m… %s\033[0m\n' "$1"; }

# 硬规则：命中即失败（存量已清零，必须保持 0）
hard_rule() {
  local id="$1" pattern="$2" target="$3" what="$4" sample="$5"
  if [[ $SELF_TEST == 1 ]]; then
    if printf '%s\n' "$sample" | grep -qE "$pattern"; then
      pass "$id 自检：反例被拦下"
    else
      fail "$id 自检失败：反例没被拦下（规则写错了）"
    fi
    return
  fi
  local hits
  hits="$(cd "$ROOT" && grep -rnE "$pattern" $target --include='*.kt' 2>/dev/null || true)"
  if [[ -z "$hits" ]]; then
    pass "$id $what"
  else
    fail "$id $what"
    printf '%s\n' "$hits" | head -10 | sed 's/^/      /'
  fi
}

# 棘轮：存量允许（逐文件计数），但**不许新增**（新文件出现也算新增）
ratchet_rule() {
  local id="$1" pattern="$2" target="$3" what="$4" baseline="$5" sample="$6"
  if [[ $SELF_TEST == 1 ]]; then
    if printf '%s\n' "$sample" | grep -qE "$pattern"; then
      pass "$id 自检：反例被拦下"
    else
      fail "$id 自检失败：反例没被拦下（规则写错了）"
    fi
    return
  fi
  local actual bad=0 line file count allowed
  actual="$(cd "$ROOT" && grep -rnE "$pattern" $target --include='*.kt' 2>/dev/null | cut -d: -f1 | sort | uniq -c || true)"
  while read -r count file; do
    [[ -z "${file:-}" ]] && continue
    allowed="$(printf '%s\n' "$baseline" | awk -v f="$file" '$1==f {print $2}')"
    if [[ -z "$allowed" ]]; then
      fail "$id 新增文件出现该模式：$file（$count 处）"
      bad=1
    elif [[ "$count" -gt "$allowed" ]]; then
      fail "$id $file 由 $allowed 增到 $count 处（不许新增，只许减少）"
      bad=1
    fi
  done <<< "$actual"
  if [[ $bad == 0 ]]; then pass "$id $what"; fi
}

echo "== 机械不变式门禁（docs/ENGINEERING_HARNESS.md §5）=="
[[ $SELF_TEST == 1 ]] && echo "   （自检模式：只喂反例）"

CHART_DIR='app/src/main/java/com/psyche/memo/provider/chart/VisualTools.kt
app/src/main/java/com/psyche/memo/provider/chart/MermaidTools.kt'

# C1 工具描述里禁止硬编码颜色（颜色的事实归主题，经 withThemeNote 在请求时注入）
hard_rule C1 '"[^"]*#[0-9A-Fa-f]{6}' "$CHART_DIR" \
  '工具描述里没有硬编码颜色（颜色由主题注入）' \
  'put("description", JsonPrimitive("use #2C2C2A for text"))'

# C3 导航/回调参数禁止 = {} 默认值（漏传时点击会被静默吞掉）
ratchet_rule C3 'on[A-Z][A-Za-z]*: \(\) -> Unit = \{\}' 'app/src/main' \
  '新增的导航回调没有 = {} 默认值（存量 32 处待烧，只许减少）' \
  'app/src/main/java/com/psyche/memo/ui/BottomToolsSheet.kt 11
app/src/main/java/com/psyche/memo/ui/chat/ChatInputBar.kt 6
app/src/main/java/com/psyche/memo/ui/HomeScreen.kt 5
app/src/main/java/com/psyche/memo/ui/chat/ChatContent.kt 3
app/src/main/java/com/psyche/memo/ui/AssistantSettingsEditScreen.kt 3
app/src/main/java/com/psyche/memo/ui/SideDrawerContent.kt 2
app/src/main/java/com/psyche/memo/ui/ModelSelectSheet.kt 1
app/src/main/java/com/psyche/memo/ui/BackupScreen.kt 1' \
  'onOpenMemorySettings: () -> Unit = {},'

# C4 禁止一行空 catch（吞异常必须写明吞了什么、为什么到不了别处）
hard_rule C4 'catch \([^)]*\) \{ *\}' 'app/src/main
core/common/src/main
core/data/src/main
core/llm/src/main
core/ui/src/main
core/workspace/src/main' \
  '没有一行空 catch（吞异常都写明了原因）' \
  'try { sink?.flush() } catch (_: Exception) {}'

# C5 应用代码禁用系统 Toast（统一走 SnackbarManager / AppSnackBarOverlay）
hard_rule C5 'android\.widget\.Toast' 'app/src/main' \
  '没有系统 Toast（统一走项目自有 toast）' \
  'android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()'

# C7 strings.xml 里禁止未转义的单引号（aapt2: unescaped apostrophe → 资源编译失败）
hard_rule C7 "=[\"][^\"]*[A-Za-z]'[A-Za-z][^\"]*[\"]|>[^<]*[A-Za-z]'[A-Za-z][^<]*<" \
  'core/ui/src/main/res' \
  'strings.xml 里没有未转义的单引号' \
  "<string name=\"x\">Show the assistant's avatar</string>"


# C6 改动文件：无行尾空白、以一个换行结尾
if [[ $SELF_TEST == 1 ]]; then
  if printf 'a = 1 \n' | grep -qE ' +$'; then pass 'C6 自检：反例被拦下'; else fail 'C6 自检失败'; fi
else
  ws="$(cd "$ROOT" && git diff --check 2>/dev/null || true)"
  if [[ -z "$ws" ]]; then
    pass 'C6 无行尾空白/冲突标记（git diff --check）'
  else
    fail 'C6 有行尾空白或冲突标记'
    printf '%s\n' "$ws" | head -6 | sed 's/^/      /'
  fi
  changed="$(cd "$ROOT" && git status --porcelain 2>/dev/null | awk '{print $NF}' | grep -E '\.(kt|kts|md|xml)$' || true)"
  broke=0
  while read -r f; do
    [[ -z "${f:-}" || ! -f "$ROOT/$f" ]] && continue
    [[ "$(tail -c2 "$ROOT/$f" | od -An -c | tr -d ' \n')" == '\n\n' ]] && { fail "C6 $f 结尾多了空行"; broke=1; }
    [[ -n "$(tail -c1 "$ROOT/$f")" ]] && { fail "C6 $f 没有以换行结尾"; broke=1; }
  done <<< "$changed"
  [[ $broke == 0 ]] && pass 'C6 改动文件结尾换行正确'
fi

echo
if [[ $FAIL == 1 ]]; then
  echo "结果：❌ 有规则未通过（规范：docs/ENGINEERING_HARNESS.md §5）"
  exit 1
fi
echo "结果：✅ 全部通过"
