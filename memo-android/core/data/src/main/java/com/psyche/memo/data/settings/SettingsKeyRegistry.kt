package com.psyche.memo.data.settings

// 偏好 / 实体键清单，直接手维（早期由 Dart 侧生成，2026-09-24 解耦后改为手维）。
// 键的原始定义仍可照对 Flutter 版：
//   lib/core/database/business_settings_router.dart
//   lib/core/database/business_data.dart (entity sourceKeys)
object SettingsKeyRegistry {
    val LOCAL_ONLY_KEYS: Set<String> = setOf(
        "desktop_hotkeys_commands_v1", "desktop_hotkeys_enabled_v1", "display_chat_font_scale_v1",
        "flutter_log_enabled_v1", "window_height_v1", "window_maximized_v1",
        "window_pos_x_v1", "window_pos_y_v1", "window_width_v1",
    )

    val DISCARDED_KEYS: Set<String> = setOf(
        "chat_titles_map", "instruction_injections_active_id_v1", "instruction_injections_active_ids_v1",
        "migrations_version_v1", "pinned_chat_ids", "provider_configs_backup_v1",
    )

    val PREFERENCE_KEYS: Set<String> = setOf(
        "agent_browser_enabled_v1", "android_background_chat_mode_v1", "app_launch_count_v1", "app_locale_v1",
        "asr_selected_service_id_v1", "asr_services_v1", "assistant_tag_collapsed_v1",
        "assistant_tag_map_v1", "avatar_type", "avatar_value",
        "backup_reminder_enabled_at_v1", "backup_reminder_enabled_v1", "backup_reminder_interval_days_v1",
        "backup_reminder_last_backup_at_v1", "backup_reminder_minutes_of_day_v1", "chat_bubble_style_overrides_user_v1",
        "chat_bubble_style_overrides_v1", "compress_generation_thinking_enabled_v1", "compress_model_v1",
        "compress_prompt_v1", "current_assistant_id_v1", "desktop_right_sidebar_open_v1",
        "desktop_right_sidebar_width_v1", "desktop_send_shortcut_v1", "desktop_sidebar_open_v1",
        "desktop_sidebar_width_v1", "desktop_topic_position_v1", "display_app_font_family_v1",
        "display_app_font_is_google_v1", "display_app_font_local_alias_v1", "display_app_font_local_path_v1",
        "display_code_font_family_v1", "display_code_font_is_google_v1", "display_code_font_local_alias_v1",
        "display_code_font_local_path_v1", "global_proxy_bypass_v1", "global_proxy_enabled_v1",
        "global_proxy_host_v1", "global_proxy_password_v1", "global_proxy_port_v1",
        "global_proxy_type_v1", "global_proxy_username_v1", "image_compress_custom_quality_v1",
        "image_compress_transparent_enabled_v1", "image_cropper_enabled_v1", "image_upload_quality_v1",
        "instruction_injection_group_collapsed_v1", "instruction_injections_active_ids_by_assistant_v1", "ios_background_generation_enabled_v1",
        "ios_background_notifications_enabled_v1", "ios_background_task_refresh_enabled_v1", "ios_live_activity_enabled_v1",
        "learning_mode_enabled_v1", "learning_mode_prompt_v1", "log_auto_delete_days_v1",
        "log_max_size_mb_v1", "log_save_output_v1", "mcp_request_timeout_ms_v1",
        "memory_extract_prompt_en_v1", "memory_extract_prompt_zh_v1", "memory_gate_prompt_en_v1",
        "memory_gate_prompt_zh_v1", "memory_legacy_mode_v1", "memory_legacy_prompt_en_v1",
        "memory_legacy_prompt_zh_v1", "memory_migrate_prompt_en_v1", "memory_migrate_prompt_zh_v1",
        "memory_migration_batch_size_v1", "memory_model_thinking_enabled_v1", "memory_model_v1",
        "memory_profile_distill_prompt_en_v1", "memory_profile_distill_prompt_zh_v1", "memory_prompt_lang_v1",
        "memory_rules_prompt_en_v1", "memory_rules_prompt_zh_v1", "memory_smart_add_batch_prompt_en_v1",
        "memory_smart_add_batch_prompt_zh_v1", "memory_smart_add_prompt_en_v1", "memory_smart_add_prompt_zh_v1",
        "memory_trace_enabled_v1", "mobile_assistant_detail_outline_enabled_v1", "mobile_assistant_edit_tab_hidden_v1",
        "mobile_assistant_edit_tab_order_v1", "ocr_enabled_v1", "ocr_generation_thinking_enabled_v1",
        "ocr_model_v1", "ocr_prompt_v1", "pinned_models_v1",
        "provider_group_collapsed_v1", "provider_group_map_v1", "provider_ungrouped_position_v1",
        "request_log_enabled_v1", "s3_config_v1", "search_auto_test_on_launch_v1",
        "search_common_v1", "search_enabled_v1", "search_selected_v1",
        "selected_model_v1", "suggestion_generation_enabled_v1", "suggestion_generation_thinking_enabled_v1",
        "suggestion_insert_on_tap_only_v1", "suggestion_model_v1", "suggestion_prompt_v1",
        "summary_generation_thinking_enabled_v1", "summary_model_v1", "summary_prompt_v1",
        "theme_mode_v1", "theme_palette_v1", "thinking_budget_v1",
        "title_generation_enabled_v1", "title_generation_thinking_enabled_v1", "title_model_v1",
        "title_prompt_v1", "tool_schema_overrides_v1", "translate_generation_thinking_enabled_v1",
        "translate_model_v1", "translate_prompt_v1", "translate_target_lang_v1",
        "tts_auto_play_assistant_replies_v1", "tts_cache_network_audio_for_replay_v1", "tts_engine_v1",
        "tts_language_v1", "tts_pitch_v1", "tts_selected_service_id_v1",
        "tts_selected_v1", "tts_speech_rate_v1", "tts_text_selection_mode_v1",
        "use_dynamic_color_v1", "user_name", "webdav_config_v1",
        "world_books_active_ids_by_assistant_v1", "world_books_collapsed_v1",
    )

    val ENTITY_SOURCE_KEYS: Set<String> = setOf(
        "assistant_memories_v1", "assistant_tags_v1", "assistants_v1",
        "instruction_injections_v1", "mcp_servers_v1", "memory_entries_v1",
        "provider_configs_v1", "provider_groups_v1", "quick_phrases_v1",
        "search_services_v1", "tts_services_v1", "user_profile_fields_v1",
        "world_books_v1",
    )

    const val PROVIDER_ORDER_KEY = "providers_order_v1"

    val ALL_KEYS: Set<String> =
        LOCAL_ONLY_KEYS + DISCARDED_KEYS + PREFERENCE_KEYS + ENTITY_SOURCE_KEYS + setOf(PROVIDER_ORDER_KEY)
}

enum class KeyDisposition { ENTITY, PROVIDER_ORDER, PREFERENCE, LOCAL_ONLY, DISCARDED, UNKNOWN }

// Mirrors BusinessKeyRegistry.classify() check order.
fun classifyBusinessKey(key: String): KeyDisposition {
    if (key in SettingsKeyRegistry.ENTITY_SOURCE_KEYS) return KeyDisposition.ENTITY
    if (key == SettingsKeyRegistry.PROVIDER_ORDER_KEY) return KeyDisposition.PROVIDER_ORDER
    if (key in SettingsKeyRegistry.LOCAL_ONLY_KEYS || key.startsWith("restore_")) return KeyDisposition.LOCAL_ONLY
    if (key in SettingsKeyRegistry.DISCARDED_KEYS) return KeyDisposition.DISCARDED
    if (key in SettingsKeyRegistry.PREFERENCE_KEYS || key.startsWith("display_")) return KeyDisposition.PREFERENCE
    return KeyDisposition.UNKNOWN
}
