package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.User
import com.psyche.memo.data.settings.PreferenceRepository
import java.io.File

/**
 * 1:1 port of lib/core/providers/user_provider.dart —— 用户（user）侧的名字与
 * 头像，和助手头像（`Assistant.avatar` 单字段）不同，这里沿用原版的
 * type + value 两键设计：
 *
 * - `user_name`
 * - `avatar_type`  = `emoji` | `url` | `file`（未设置 = null）
 * - `avatar_value` = emoji 字符 / http(s) 链接 / 应用内文件路径
 *
 * 存储走 [PreferenceRepository.readLocal]/`writeLocal`，与设置页的
 * `display_show_user_*` 开关、时间线设置、导出用的 `user_name` 读取保持一致
 * （这些键在 classifyBusinessKey 里归 PREFERENCE，但既有代码统一存在
 * SharedPreferences）。
 */
class UserProfileStore(private val prefs: PreferenceRepository) {

    /** [name] 为空表示用户没设置过，展示层用本地化默认名兜底。 */
    data class Profile(
        val name: String = "",
        val avatarType: String? = null,
        val avatarValue: String? = null,
    )

    private val _profile = mutableStateOf(load())
    val profile: State<UserProfileStore.Profile> get() = _profile

    private fun load(): Profile {
        val raw = prefs.readJson(KEY_AVATAR_VALUE)?.takeIf { it.isNotBlank() }
        return Profile(
            name = prefs.readJson(KEY_NAME)?.takeIf { it.isNotBlank() } ?: "",
            avatarType = prefs.readJson(KEY_AVATAR_TYPE)?.takeIf { it.isNotBlank() }
                ?.takeIf { raw != null },
            avatarValue = raw,
        )
    }

    private fun update(p: Profile) {
        _profile.value = p
    }

    /** 改名（side_drawer `_editUserName` → `setName`）：空串/未变化直接忽略。 */
    fun setName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == _profile.value.name) return
        prefs.writeJson(KEY_NAME, trimmed)
        update(_profile.value.copy(name = trimmed))
    }

    fun setAvatarEmoji(emoji: String) {
        val e = emoji.trim()
        if (e.isEmpty()) return
        prefs.writeJson(KEY_AVATAR_TYPE, TYPE_EMOJI)
        prefs.writeJson(KEY_AVATAR_VALUE, e)
        update(_profile.value.copy(avatarType = TYPE_EMOJI, avatarValue = e))
    }

    /** 链接头像（`setAvatarUrl`）；离线预取交给 Coil 的缓存。 */
    fun setAvatarUrl(url: String) {
        val u = url.trim()
        if (u.isEmpty()) return
        prefs.writeJson(KEY_AVATAR_TYPE, TYPE_URL)
        prefs.writeJson(KEY_AVATAR_VALUE, u)
        update(_profile.value.copy(avatarType = TYPE_URL, avatarValue = u))
    }

    /**
     * 本地文件头像（`setAvatarFilePath`）：原版把选中的图片复制进应用持久目录
     * 并删掉旧文件（避免 content:// 失效），这里保存的就是复制后的绝对路径。
     */
    fun setAvatarFilePath(path: String) {
        if (path.isBlank()) return
        val previous = _profile.value
        prefs.writeJson(KEY_AVATAR_TYPE, TYPE_FILE)
        prefs.writeJson(KEY_AVATAR_VALUE, path)
        update(previous.copy(avatarType = TYPE_FILE, avatarValue = path))
        // 旧头像文件与目录后缀一致时才清理，避免误删其它来源的文件。
        val old = previous.avatarValue
        if (previous.avatarType == TYPE_FILE && !old.isNullOrBlank() && old != path) {
            runCatching { File(old).delete() }
        }
    }

    fun resetAvatar() {
        prefs.remove(KEY_AVATAR_TYPE)
        prefs.remove(KEY_AVATAR_VALUE)
        update(_profile.value.copy(avatarType = null, avatarValue = null))
    }

    companion object {
        const val TYPE_EMOJI = "emoji"
        const val TYPE_URL = "url"
        const val TYPE_FILE = "file"

        private const val KEY_NAME = "user_name"
        private const val KEY_AVATAR_TYPE = "avatar_type"
        private const val KEY_AVATAR_VALUE = "avatar_value"
    }
}

/** 头像为空时的兜底：气泡用图标、抽屉用户栏用名字首字母（原版两处不同）。 */
enum class UserAvatarFallback { Icon, Initial }

/**
 * 用户头像 —— 对应原版两处渲染：
 * - `chat_message_widget.dart:1590-1660` `_buildUserAvatar`（空态 `Lucide.User` 图标）
 * - `side_drawer.dart:1670-1765` `avatarWidget`（空态名字首字母）
 *
 * emoji / url / file 三态共用，只有空态的不同由 [fallback] 决定。
 */
@Composable
internal fun UserAvatar(
    profile: UserProfileStore.Profile,
    name: String,
    size: Dp,
    fallback: UserAvatarFallback,
) {
    val cs = MaterialTheme.colorScheme
    val type = profile.avatarType
    val value = profile.avatarValue?.trim().orEmpty()

    Box(
        modifier = Modifier
            .size(size)
            .background(
                cs.primary.copy(alpha = if (fallback == UserAvatarFallback.Icon) 0.1f else 0.15f),
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            type == UserProfileStore.TYPE_EMOJI && value.isNotEmpty() -> Text(
                text = firstGrapheme(value),
                style = TextStyle(fontSize = (size.value * 0.5f).sp),
            )
            type == UserProfileStore.TYPE_URL && value.isNotEmpty() -> coil.compose.AsyncImage(
                model = value,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
            type == UserProfileStore.TYPE_FILE && value.isNotEmpty() -> coil.compose.AsyncImage(
                model = File(value),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
            fallback == UserAvatarFallback.Icon -> Icon(
                Lucide.User,
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(size * 0.56f),
            )
            else -> Text(
                text = name.trim().take(1).uppercase().ifEmpty { "?" },
                style = TextStyle(
                    fontSize = (size.value * 0.42f).sp,
                    fontWeight = FontWeight.Bold,
                    color = cs.primary,
                ),
            )
        }
    }
}
