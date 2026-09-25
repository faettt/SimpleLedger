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
    /** 校验不通过返回问题码（UI 映射文案）；null = 通过 */
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
