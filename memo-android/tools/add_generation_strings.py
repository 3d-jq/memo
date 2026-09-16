# -*- coding: utf-8 -*-
"""一次性把「生成服务」（自研功能）的 ARB 文案追加进三个 locale。

为什么要脚本：ARB 是 JSON，但**必须保持原有排版**（两空格缩进 + @key 元数据块），
json.dump 会把整个文件重排成一行行的巨大 diff。这里只在最后一个 key 后面追加。
"""
import io
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
L10N = os.path.normpath(os.path.join(HERE, '..', '..', 'lib', 'l10n'))

# (key, en, zh, zh_Hant) —— 需要占位符时在下面 PLACEHOLDERS 里声明。
KEYS = [
    ("settingsPageImageGeneration", "Image generation", "生成图片", "生成圖片"),
    ("settingsPageVideoGeneration", "Video generation", "生成视频", "生成影片"),
    ("generationServicesPageImageTitle", "Image generation", "生成图片", "生成圖片"),
    ("generationServicesPageVideoTitle", "Video generation", "生成视频", "生成影片"),
    ("generationServicesPageAdd", "Add service", "添加服务", "新增服務"),
    ("generationServicesPageEmpty",
     "No services yet. Add one to let the assistant generate images.",
     "还没有服务。添加一个，助手就能生成图片。",
     "還沒有服務。新增一個，助手就能生成圖片。"),
    ("generationServicesPageEmptyVideo",
     "No services yet. Add one to let the assistant generate videos.",
     "还没有服务。添加一个，助手就能生成视频。",
     "還沒有服務。新增一個，助手就能生成影片。"),
    ("generationServicesPageTestTooltip", "Test connection", "测试连接", "測試連線"),
    ("generationServicesPageTesting", "Testing…", "测试中…", "測試中…"),
    ("generationServicesStatusUntested", "Not tested", "未测试", "未測試"),
    ("generationServicesStatusOk", "Available", "可用", "可用"),
    ("generationServicesStatusFailed", "Failed", "连接失败", "連線失敗"),
    ("generationServicesDeleteTitle", "Delete service", "删除服务", "刪除服務"),
    ("generationServicesDeleteMessage",
     "Delete \"{name}\"? This cannot be undone.",
     "删除「{name}」？此操作不可撤销。",
     "刪除「{name}」？此操作無法復原。"),
    ("generationServiceEditorAddTitle", "New service", "新增服务", "新增服務"),
    ("generationServiceEditorEditTitle", "Edit service", "编辑服务", "編輯服務"),
    ("generationServiceEditorBasicSection", "Service", "服务", "服務"),
    ("generationServiceEditorNameLabel", "Name", "名称", "名稱"),
    ("generationServiceEditorNameHint", "Shown in the assistant tab", "显示在助手页", "顯示在助手頁"),
    ("generationServiceEditorBaseUrlLabel", "Base URL", "接口地址", "介面位址"),
    ("generationServiceEditorApiKeyLabel", "API key", "API Key", "API Key"),
    ("generationServiceEditorModelLabel", "Model", "模型", "模型"),
    ("generationServiceEditorModelHint", "e.g. gpt-image-1", "例如 gpt-image-1", "例如 gpt-image-1"),
    ("generationServiceEditorModelHintVideo", "e.g. sora-2", "例如 sora-2", "例如 sora-2"),
    ("generationServiceEditorParamsSection", "Defaults", "默认参数", "預設參數"),
    ("generationServiceEditorSizeLabel", "Size", "尺寸", "尺寸"),
    ("generationServiceEditorSizeHint",
     "Leave empty for the provider default (e.g. 1024x1024)",
     "留空用服务端默认（如 1024x1024）",
     "留空用伺服器預設（如 1024x1024）"),
    ("generationServiceEditorCountLabel", "Images per request", "每次张数", "每次張數"),
    ("generationServiceEditorSecondsLabel", "Duration", "时长", "時長"),
    ("generationServiceEditorSecondsValue", "{count}s", "{count}s", "{count}s"),
    ("generationServiceEditorTestSection", "Connection", "连接", "連線"),
    ("generationServiceEditorTestAction", "Test", "测试", "測試"),
    ("generationServiceEditorTestOk",
     "Endpoint reachable ({count} models)",
     "接口可达（{count} 个模型）",
     "介面可達（{count} 個模型）"),
    ("generationServiceEditorTestNoModels",
     "Endpoint reachable, but it does not expose /models",
     "接口可达，但没有 /models 列表",
     "介面可達，但沒有 /models 清單"),
    ("generationServiceEditorTestFailed",
     "Connection failed: {message}",
     "连接失败：{message}",
     "連線失敗：{message}"),
    ("generationServiceEditorRequired", "Required", "必填", "必填"),
    ("generationServiceEditorSaved", "Saved", "已保存", "已儲存"),
]

# 第 2 阶段（助手编辑页两个 tab）。同一个脚本继续追加，已存在的键自动跳过。
KEYS_PHASE2 = [
    ("assistantEditPageImageGenerationTab", "Image generation", "生成图片", "生成圖片"),
    ("assistantEditPageVideoGenerationTab", "Video generation", "生成视频", "生成影片"),
    ("assistantGenerationSection", "Service", "服务", "服務"),
    ("assistantGenerationOverridesSection", "Overrides", "参数覆盖", "參數覆寫"),
    ("assistantGenerationOverrideHint",
     "Leave empty to use the service value",
     "留空用服务里的值",
     "留空用服務裡的值"),
    ("assistantGenerationNoServices",
     "No service yet. Add one in Settings → Image generation.",
     "还没有服务。先到「设置 → 生成图片」里添加。",
     "還沒有服務。先到「設定 → 生成圖片」裡新增。"),
    ("assistantGenerationNoServicesVideo",
     "No service yet. Add one in Settings → Video generation.",
     "还没有服务。先到「设置 → 生成视频」里添加。",
     "還沒有服務。先到「設定 → 生成影片」裡新增。"),
    ("assistantGenerationDisabledHint",
     "The assistant only gets this tool while a service is selected.",
     "只有选了服务，助手才会拿到这个工具。",
     "只有選了服務，助手才會拿到這個工具。"),
]

PLACEHOLDERS_PHASE2 = {}

# 第 3 阶段：工具描述页的新分组标题（工作区/技能/生成工具）。
KEYS_PHASE3 = [
    ("toolSchemaSettingsGroupWorkspace", "Workspace tools", "工作区工具", "工作區工具"),
    ("toolSchemaSettingsGroupSkill", "Skills", "技能", "技能"),
    ("toolSchemaSettingsGroupGeneration", "Generation", "生成", "生成"),
]

PLACEHOLDERS_PHASE3 = {}

# 第 4 阶段：对话 ➕ 面板的生成入口（sheet 文案）。
KEYS_PHASE4 = [
    ("generationSheetPromptHint", "Describe the image you want", "描述你想要的画面", "描述你想要的畫面"),
    ("generationSheetPromptHintVideo", "Describe the video you want", "描述你想要的视频", "描述你想要的影片"),
    ("generationSheetGenerate", "Generate", "生成", "生成"),
    ("generationSheetGenerating", "Generating…", "生成中…", "生成中…"),
    ("generationSheetFailed", "Generation failed: {message}", "生成失败：{message}", "生成失敗：{message}"),
    ("generationSheetInserted", "Added to the conversation", "已加入对话", "已加入對話"),
]

PLACEHOLDERS_PHASE4 = {
    "generationSheetFailed": [("message", "String")],
}

# 第 5 阶段：助手的生成工具（工具卡标题 + 工具描述）。
KEYS_PHASE5 = [
    ("chatMessageWidgetGenerateImage", "Generate image: {prompt}", "生成图片：{prompt}", "生成圖片：{prompt}"),
    ("chatMessageWidgetGenerateVideo", "Generate video: {prompt}", "生成视频：{prompt}", "生成影片：{prompt}"),
]

PLACEHOLDERS_PHASE5 = {
    "chatMessageWidgetGenerateImage": [("prompt", "String")],
    "chatMessageWidgetGenerateVideo": [("prompt", "String")],
}

# 需要占位符的键（key -> [(name, type)]）
PLACEHOLDERS = {
    "generationServicesDeleteMessage": [("name", "String")],
    "generationServiceEditorSecondsValue": [("count", "int")],
    "generationServiceEditorTestOk": [("count", "int")],
    "generationServiceEditorTestFailed": [("message", "String")],
}

FILES = [
    ("app_en.arb", 1, None),
    ("app_zh.arb", 2, None),
    ("app_zh_Hant.arb", 3, None),
]


def esc(text):
    return text.replace('\\', '\\\\').replace('"', '\\"')


def block_for(key, value, locale_index, placeholders):
    lines = ['  "%s": "%s"' % (key, esc(value))]
    if locale_index == 1 and key in placeholders:
        lines[-1] += ','
        lines.append('  "@%s": {' % key)
        lines.append('    "placeholders": {')
        ph = placeholders[key]
        for i, (name, ptype) in enumerate(ph):
            comma = ',' if i != len(ph) - 1 else ''
            lines.append('      "%s": { "type": "%s" }%s' % (name, ptype, comma))
        lines.append('    }')
        lines.append('  }')
    return lines


def patch(file_name, locale_index):
    path = os.path.join(L10N, file_name)
    with io.open(path, 'r', encoding='utf-8') as fh:
        text = fh.read()
    todo = [(k, en, zh, hant, PLACEHOLDERS) for (k, en, zh, hant) in KEYS]
    todo += [(k, en, zh, hant, PLACEHOLDERS_PHASE2) for (k, en, zh, hant) in KEYS_PHASE2]
    todo += [(k, en, zh, hant, PLACEHOLDERS_PHASE3) for (k, en, zh, hant) in KEYS_PHASE3]
    todo += [(k, en, zh, hant, PLACEHOLDERS_PHASE4) for (k, en, zh, hant) in KEYS_PHASE4]
    todo += [(k, en, zh, hant, PLACEHOLDERS_PHASE5) for (k, en, zh, hant) in KEYS_PHASE5]
    missing = [entry for entry in todo if ('"%s"' % entry[0]) not in text]
    if not missing:
        print('    %s already has every key — skipped' % file_name)
        return
    stripped = text.rstrip()
    assert stripped.endswith('}'), 'unexpected ARB tail in %s' % file_name
    head = stripped[:-1].rstrip()
    if not head.endswith(','):
        head += ','
    out_lines = [head]
    for i, (key, en, zh, hant, placeholders) in enumerate(missing):
        value = (en, zh, hant)[locale_index - 1]
        new_lines = block_for(key, value, locale_index, placeholders)
        if i != len(missing) - 1:
            new_lines[-1] += ','
        out_lines.extend(new_lines)
    out_lines.append('}')
    with io.open(path, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write('\n'.join(out_lines) + '\n')
    print('    %s: +%d keys' % (file_name, len(missing)))


def main():
    for name, index, _ in FILES:
        patch(name, index)
    return 0


if __name__ == '__main__':
    sys.exit(main())
