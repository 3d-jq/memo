#!/usr/bin/env bash
# 把开发仓最近两个提交改动的文件同步进发布仓工作树，提交并推送。
set -uo pipefail
export PATH="/c/Users/邓嘉权/.workbuddy/binaries/PortableGit/versions/1.2.0/usr/bin:$PATH"

DEV=/d/program/memo
PUB=/d/program/memo-public

cd "$DEV" || exit 1
git diff --name-only HEAD~2 HEAD | grep '^memo-android/' | sed 's|^memo-android/||' > /tmp/pub_files2.txt
cp CHANGELOG.md "$PUB/CHANGELOG.md"
while read -r f; do
  [ -z "$f" ] && continue
  mkdir -p "$PUB/$(dirname "$f")"
  cp "$DEV/memo-android/$f" "$PUB/$f"
done < /tmp/pub_files2.txt

cd "$PUB" || exit 1
echo "--- 发布仓待提交 ---"
git status --short
cat > /tmp/msg_pub2.txt << 'EOF'
修复：流式提示抖动（贴底改布局驱动）+ 聊天项开关点了不生效

- 贴底触发源从「数据事件」换成「布局变化」（参照 RikkaHub / deepseek-harness）：
  新增 ui/chat/PinnedFollow.kt 纯函数判定 + 契约测试；顺带修掉哨兵下标漏算流式提示项（少滚一行）。
- 「显示助手头像」等聊天项开关点了不生效：显示设置是同屏叠层，宿主缓存的设置不刷新 ——
  加 DisplayPrefs.revision 作为缓存 key，写入即失效；布尔读数统一走 DisplayPrefs.decodeBool。
- CHANGELOG.md 改为「新增 / 修复 / 改进」分类。
EOF
git add -A
git commit -q -F /tmp/msg_pub2.txt
git log --oneline -1
echo "--- 推送 ---"
GIT_SSL_NO_VERIFY=true git -c http.schannelCheckRevoke=false push origin master 2>&1 | tail -3
