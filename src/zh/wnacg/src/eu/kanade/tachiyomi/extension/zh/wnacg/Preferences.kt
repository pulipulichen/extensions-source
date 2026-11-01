package eu.kanade.tachiyomi.extension.zh.wnacg

import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

private const val DEFAULT_LIST = "https://www.wn10.cfd,https://www.wn07.shop"
private const val AUTO_URL_OPTION = "__auto__"
private const val CUSTOM_URL_OPTION = "__custom__"


fun getPreferencesInternal(
    context: Context,
    preferences: SharedPreferences,
    isUrlUpdated: Boolean,
): Array<Preference> {
    val selectedUrl = preferences.getString(URL_SELECTION_PREF, null)
        ?.takeUnless { it == AUTO_URL_OPTION || it == CUSTOM_URL_OPTION }
        ?.toValidBaseUrlOrNull()
    // Keep a manually selected mirror visible even when the remote list changes.
    val availableUrls = (preferences.urlList + listOfNotNull(selectedUrl)).distinct()

    val urlPreference = ListPreference(context).apply {
        key = URL_SELECTION_PREF
        title = "网站网址"
        entries = (listOf("自动选择（随网站更新）") + availableUrls + "自定义网址").toTypedArray()
        entryValues = (listOf(AUTO_URL_OPTION) + availableUrls + CUSTOM_URL_OPTION).toTypedArray()
        summary = if (isUrlUpdated) "%s\\n网址列表已自动更新，请重启应用。" else "%s\\n切换网址后请重启应用。"
        setDefaultValue(AUTO_URL_OPTION)
        setOnPreferenceChangeListener { _, value ->
            if (value == CUSTOM_URL_OPTION && preferences.customBaseUrl == null) {
                Toast.makeText(context, "请先在下方输入自定义网址。", Toast.LENGTH_SHORT).show()
                false
            } else {
                true
            }
        }
    }

    val customUrlPreference = EditTextPreference(context).apply {
        key = CUSTOM_URL_PREF
        title = "自定义网址"
        dialogTitle = "输入网站网址"
        summary = customUrlSummary(preferences.customBaseUrl)
        setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            editText.setSingleLine(true)
            editText.hint = "https://www.wn10.cfd"
        }
        setOnPreferenceChangeListener { _, value ->
            val normalizedUrl = (value as String).toValidBaseUrlOrNull()
            if (normalizedUrl == null) {
                Toast.makeText(context, "请输入有效的 HTTPS 网址（仅域名，不含路径）。", Toast.LENGTH_LONG).show()
                false
            } else {
                summary = customUrlSummary(normalizedUrl)
                // Entering a valid custom URL selects it immediately; restart to apply.
                urlPreference.value = CUSTOM_URL_OPTION
                true
            }
        }
    }

    return arrayOf(
        urlPreference,
        customUrlPreference,
    EditTextPreference(context).apply {
        key = TITLE_BLACKLIST_PREF
        title = "标题屏蔽关键词"
        summary = titleBlacklistSummary(preferences.getString(TITLE_BLACKLIST_PREF, ""))
        setOnPreferenceChangeListener { _, newValue ->
            summary = titleBlacklistSummary(newValue as String)
            true
        }
        dialogTitle = "标题屏蔽关键词"
        setDefaultValue("")
    },

    EditTextPreference(context).apply {
        key = BLACKLIST_MAX_SCAN_PAGES_PREF
        title = "黑名单过滤最大扫描页数"
        summary = maxScanPagesSummary(preferences.blacklistMaxScanPages)
        setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
        }
        setOnPreferenceChangeListener { _, newValue ->
            val value = (newValue as String).toIntOrNull()
            if (value == null || value < 1) {
                Toast.makeText(context, "请输入不小于 1 的正整数。", Toast.LENGTH_SHORT).show()
                false
            } else {
                summary = maxScanPagesSummary(value)
                true
            }
        }
        dialogTitle = "黑名单过滤最大扫描页数"
        setDefaultValue(DEFAULT_MAX_SCAN_PAGES.toString())
    },

    SwitchPreferenceCompat(context).apply {
        key = FILTER_SEARCH_RESULTS_PREF
        title = "在搜索结果中使用标题屏蔽"
        summary = "开启后，关键词、分类和标签搜索结果也会应用标题屏蔽关键词。"
        setDefaultValue(true)
    },
)
}


val SharedPreferences.baseUrl: String
    get() {
        val mirrors = urlList
        return when (val selected = getString(URL_SELECTION_PREF, AUTO_URL_OPTION)) {
            AUTO_URL_OPTION -> mirrors.first()
            CUSTOM_URL_OPTION -> customBaseUrl ?: mirrors.first()
            else -> selected?.toValidBaseUrlOrNull() ?: mirrors.first()
        }
    }

private val SharedPreferences.customBaseUrl: String?
    get() = getString(CUSTOM_URL_PREF, null)?.toValidBaseUrlOrNull()

private val SharedPreferences.isAutomaticUrl: Boolean
    get() = getString(URL_SELECTION_PREF, AUTO_URL_OPTION) == AUTO_URL_OPTION

private val SharedPreferences.legacyUrlIndex: Int
    get() = getString(URL_INDEX_PREF, "-1")?.toIntOrNull() ?: -1

val SharedPreferences.urlList: List<String>
    get() = getString(URL_LIST_PREF, DEFAULT_LIST)
        .orEmpty()
        .toValidUrlList()
        .ifEmpty { DEFAULT_LIST.toValidUrlList() }

var SharedPreferences.extraCategories: List<Pair<String, String>>
    get() = getString(EXTRA_CATEGORIES_PREF, null)?.parseAs<List<Pair<String, String>>>().orEmpty()
    set(value) = edit().putString(EXTRA_CATEGORIES_PREF, value.toJsonString()).apply()

val SharedPreferences.titleBlacklist: List<String>
    get() = getString(TITLE_BLACKLIST_PREF, "")
        .orEmpty()
        .split(',', '\n', '\r')
        .map(String::trim)
        .filter(String::isNotEmpty)

val SharedPreferences.filterSearchResults: Boolean
    get() = getBoolean(FILTER_SEARCH_RESULTS_PREF, true)

val SharedPreferences.blacklistMaxScanPages: Int
    get() = getString(BLACKLIST_MAX_SCAN_PAGES_PREF, DEFAULT_MAX_SCAN_PAGES.toString())
        ?.toIntOrNull()
        ?.takeIf { it >= 1 }
        ?: DEFAULT_MAX_SCAN_PAGES

private fun customUrlSummary(url: String?) = url?.let { "$it\\n保存后自动使用此网址，重启应用生效。" }
    ?: "尚未设置。请输入完整的 HTTPS 网址，或直接输入域名。"

private fun titleBlacklistSummary(value: String?) = value?.takeIf(String::isNotBlank)?.let { "$it\n$TITLE_BLACKLIST_SUMMARY" }
    ?: TITLE_BLACKLIST_SUMMARY

private fun maxScanPagesSummary(value: Int) = "当前值：$value\n$BLACKLIST_MAX_SCAN_PAGES_SUMMARY"


fun SharedPreferences.preferenceMigration() {
    val previousSelection = getString(URL_SELECTION_PREF, null)
    val defaultListChanged = getString(DEFAULT_LIST_PREF, null) != DEFAULT_LIST
    if (previousSelection != null && !defaultListChanged) return

    // Import the mirror choice made in older versions before replacing the old list.
    val previouslySelectedUrl = urlList.getOrNull(legacyUrlIndex)
    edit().apply {
        if (previousSelection == null) {
            putString(URL_SELECTION_PREF, previouslySelectedUrl ?: AUTO_URL_OPTION)
        }
        if (defaultListChanged) {
            putString(DEFAULT_LIST_PREF, DEFAULT_LIST)
            putString(URL_LIST_PREF, DEFAULT_LIST)
            remove("overrideBaseUrl")
        }
    }.apply()
}

class UpdateUrlInterceptor(private val preferences: SharedPreferences) : Interceptor {
    private val baseUrl = preferences.baseUrl
    var isUpdated = false

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.toString().startsWith(baseUrl)) return chain.proceed(request)

        val failedResponse = try {
            val response = chain.proceed(request)
            if (response.isSuccessful && response.header("Server") != "Parking/1.0") return response
            response
        } catch (e: IOException) {
            if (chain.call().isCanceled()) throw e
            if (preferences.isAutomaticUrl && (isUpdated || updateUrl(chain))) {
                throw IOException("网址已自动更新，请重启应用", e)
            }
            throw e
        }

        val statusCode = failedResponse.code
        failedResponse.close()
        // A guest 404 isn't a dead mirror
        if (statusCode == 404 && isLoginOnlyPath(request.url.encodedPath)) {
            throw IOException(LOGIN_HINT)
        }
        if (preferences.isAutomaticUrl && (isUpdated || updateUrl(chain))) {
            throw IOException("网址已自动更新，请重启应用")
        }
        throw IOException("请求失败：HTTP $statusCode")
    }

    @Synchronized
    private fun updateUrl(chain: Interceptor.Chain): Boolean {
        if (isUpdated) return true
        return try {
            chain.proceed(GET("https://stevenyomi.github.io/source-domains/wnacg.txt")).use {
                if (!it.isSuccessful) return false
                val newList = it.body.string().toValidUrlList()
                if (newList.isEmpty() || newList == preferences.urlList) return false

                val urlChanged = newList.first() != preferences.baseUrl
                preferences.edit().putString(URL_LIST_PREF, newList.joinToString(",")).apply()
                isUpdated = urlChanged
                urlChanged
            }
        } catch (e: IOException) {
            if (chain.call().isCanceled()) throw e
            false
        }
    }
}

// Allow a bare hostname in the custom field; always use HTTPS and a root URL.
private fun String.toValidBaseUrlOrNull(): String? {
    val input = trim()
    if (input.isEmpty()) return null
    val parsed = (if ("://" in input) input else "https://$input").toHttpUrlOrNull() ?: return null
    if (parsed.scheme != "https" || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() ||
        parsed.encodedPath != "/" || parsed.query != null || parsed.fragment != null
    ) return null
    return parsed.toString().removeSuffix("/")
}

private fun String.toValidUrlList(): List<String> = split(',')
    .mapNotNull(String::toValidBaseUrlOrNull)
    .distinct()

// Guests get 404 on some galleries and on login-only categories
private fun isLoginOnlyPath(path: String) = path.startsWith("/photos-") || unlistedCategoryId(path) != null

const val LOGIN_HINT = "内容不存在或需要登录，请在 WebView 中登录后重试"

private const val DEFAULT_LIST_PREF = "defaultBaseUrl"
private const val URL_LIST_PREF = "baseUrlList"
private const val URL_INDEX_PREF = "baseUrlIndex" // legacy migration only
private const val URL_SELECTION_PREF = "baseUrlSelection"
private const val CUSTOM_URL_PREF = "customBaseUrl"
private const val EXTRA_CATEGORIES_PREF = "extraCategories"
private const val TITLE_BLACKLIST_PREF = "titleBlacklist"
private const val BLACKLIST_MAX_SCAN_PAGES_PREF = "blacklistMaxScanPages"
private const val FILTER_SEARCH_RESULTS_PREF = "filterSearchResults"
private const val DEFAULT_MAX_SCAN_PAGES = 5
private const val TITLE_BLACKLIST_SUMMARY = "使用英文逗号或换行分隔多个关键词；忽略大小写，始终作用于热门和最新列表。"
private const val BLACKLIST_MAX_SCAN_PAGES_SUMMARY =
    "每次加载最多检查多少个网站页面，包含当前页。找到未被屏蔽的内容后立即返回；只在启用黑名单过滤时生效。"
