package com.simpleledger.app.sync.account

import android.content.Context
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * WebDAV 凭证（R-01：服务器地址 + 账号 + 应用密码）。
 *
 * **仅存本地、不上传**（§7-10）：明文存应用私有 SharedPreferences——口令保护的是云端，
 * root 级威胁不在 PRD 范围；不引 security-crypto（官方已废弃）。
 */
data class WebDavCred(
    val baseUrl: String,
    val username: String,
    val appPassword: String,
) {
    /**
     * 校验不通过返回问题码（UI 映射文案）；null = 通过。
     *
     * 明文传输（http）**不在校验拦截范围**（U-8 裁定）：局域网 NAS / 群晖等自建场景
     * 合法，硬禁会误伤；但 Basic 认证凭据会明文上网（同步内容本身有 AES-GCM 加密，
     * 认证没有）——该风险由 UI 层知情确认兜底（设置页警示 + 接入/测连前一次性确认，
     * 见 `isPlaintextHttp` 与 `SyncSettingsViewModel.gateOnInsecureHttp`）。
     */
    fun validate(): WebDavCredIssue? = when {
        baseUrl.isBlank() -> WebDavCredIssue.URL_EMPTY
        normalizedUrl() == null -> WebDavCredIssue.URL_INVALID
        username.isBlank() -> WebDavCredIssue.USERNAME_EMPTY
        appPassword.isBlank() -> WebDavCredIssue.APP_PASSWORD_EMPTY
        else -> null
    }

    /** 归一化后的 HttpUrl（尾斜杠保证相对路径解析正确）；非法返回 null */
    fun httpUrl(): HttpUrl? = normalizedUrl()

    private fun normalizedUrl(): HttpUrl? =
        baseUrl.trim().removeSuffix("/").plus("/").toHttpUrlOrNull()
}

/**
 * U-8 明文传输判定：URL scheme 为 http（非 https）→ true。
 *
 * 纯函数（JVM 可测）：大小写不敏感、容忍首尾空白；空串 / 无 scheme / 畸形输入
 * 一律 false——畸形 URL 的拦截是 [WebDavCred.validate] 的职责，这里只回答
 * 「已解析出 http scheme 吗」。Basic 认证凭据在 http 下明文上网（内容加密不覆盖认证头），
 * UI 层据此出警示与知情确认。
 *
 * U-14：判定必须与 [WebDavCred.validate]/`httpUrl()` **同源解析**（OkHttp 宽容解析）。
 * 旧实现按 `"://"` 字面切 scheme，`"http:/host"`（少写一个斜杠）被误判为非 http；
 * 但 OkHttp 会把缺斜杠 URL 正常解析为 http 明文连接——警示横幅与知情确认门全部
 * Pass，Basic 凭证明文上网。现直接归一化解析后读 scheme：凡 OkHttp 认可的
 * http URL（含缺斜杠宽容形态）一律判明文，判不出 http 的输入维持 false。
 */
fun isPlaintextHttp(url: String): Boolean {
    val normalized = url.trim().removeSuffix("/").plus("/").toHttpUrlOrNull() ?: return false
    return normalized.scheme == "http"
}

/** 凭证校验问题码（数据层不写死文案，UI 从 strings.xml 取） */
enum class WebDavCredIssue {
    URL_EMPTY,
    URL_INVALID,
    USERNAME_EMPTY,
    APP_PASSWORD_EMPTY,
}

/**
 * 凭证持久化端口（T-4 接线：`SyncManager` 经此读写凭证；JVM 测试注入内存实现）。
 */
interface AccountStore {
    fun load(): WebDavCred?
    fun save(cred: WebDavCred)
    fun clear()
}

/**
 * 凭证本地持久化（SharedPreferences `simple_ledger_sync_account`），实现 [AccountStore]。
 * 与 [SyncPrefs] 分文件存放：重置同步（R-05）清同步状态不动凭证，换凭证不动 KDF 材料。
 */
class SyncAccount(context: Context) : AccountStore {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): WebDavCred? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        val username = prefs.getString(KEY_USERNAME, null) ?: return null
        val password = prefs.getString(KEY_APP_PASSWORD, null) ?: return null
        return WebDavCred(baseUrl = url, username = username, appPassword = password)
    }

    override fun save(cred: WebDavCred) {
        prefs.edit()
            .putString(KEY_URL, cred.baseUrl.trim().removeSuffix("/").plus("/"))
            .putString(KEY_USERNAME, cred.username)
            .putString(KEY_APP_PASSWORD, cred.appPassword)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "simple_ledger_sync_account"
        private const val KEY_URL = "base_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_APP_PASSWORD = "app_password"
    }
}
