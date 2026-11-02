package eu.kanade.tachiyomi.extension.zh.manhuaren

import android.content.SharedPreferences
import eu.kanade.tachiyomi.extension.zh.manhuaren.Manhuaren.Companion.TOKEN_PREF
import eu.kanade.tachiyomi.extension.zh.manhuaren.Manhuaren.Companion.USER_ID_PREF
import keiyoushi.utils.parseAs
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

class ErrorResponseInterceptor(private val baseUrl: String, private val preferences: SharedPreferences) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!request.url.toString().startsWith(baseUrl)) return response

        val body = response.body
        val content = body.string()
        val error = runCatching { content.parseAs<ErrorResponse>() }.getOrNull()?.errorResponse
        val hasError = error != null
        if (hasError) {
            // API errors do not necessarily mean that saved credentials are invalid.\n            // Preserve them so users can retry and diagnose server-side failures.\n            throw IOException("漫畫人 API 請求失敗：${error}。已保留使用者 ID 與 Token；請稍後重試或檢查擴充套件設定。")
        }

        return response.newBuilder()
            .body(content.toResponseBody(body.contentType()))
            .build()
    }
}
