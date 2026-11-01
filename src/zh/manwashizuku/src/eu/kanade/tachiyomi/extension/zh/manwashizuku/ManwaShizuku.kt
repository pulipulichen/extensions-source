package eu.kanade.tachiyomi.extension.zh.manwashizuku

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.buffer
import okio.cipherSource
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@Source
abstract class ManwaShizuku :
    KeiSource(),
    ConfigurableSource {
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH)
    private val json = Json { encodeDefaults = true }

    private val preferences by getPreferencesLazy()

    // Use the selected mirror, or a valid custom URL. Restart reader after changes.
    override val baseUrl: String = getConfiguredBaseUrl()

    private fun getConfiguredBaseUrl(): String {
        val custom = preferences.getString(CUSTOM_URL_KEY, "")?.trim()?.trimEnd('/').orEmpty()
        if (custom.startsWith("https://") && custom.toHttpUrlOrNull() != null) return custom
        val mirror = preferences.getString(MIRROR_URL_KEY, MIRROR_URLS[0])?.trim()?.trimEnd('/')
        return mirror?.takeIf { it.startsWith("https://") && it.toHttpUrlOrNull() != null } ?: MIRROR_URLS[0]
    }

    override fun OkHttpClient.Builder.configureClient() = addInterceptor(rewriteOctetStream)

    private val rewriteOctetStream: Interceptor = Interceptor { chain ->
        val response: Response = chain.proceed(chain.request())
        val imageHost = preferences.getString(IMAGE_HOST_KEY, IMAGE_HOST_ENTRIES[0])!!.substringBefore(":")
        if (response.request.url.host != imageHost || !response.request.url.encodedPath.endsWith(".jpg", ignoreCase = true) || !response.isSuccessful) {
            return@Interceptor response
        }
        // Cover images are ordinary JPEGs, while chapter images may be AES-encrypted.\n        // Inspect the response magic bytes before attempting decryption.\n        val source = response.body.source()\n        source.request(16)\n        val prefix = source.buffer.snapshot().toByteArray()\n        if (prefix.size < 16 ||\n            (prefix[0] == 0xFF.toByte() && prefix[1] == 0xD8.toByte()) ||\n            (prefix[0] == 0x89.toByte() && prefix[1] == 0x50.toByte() && prefix[2] == 0x4E.toByte() && prefix[3] == 0x47.toByte()) ||\n            (prefix[0] == 0x52.toByte() && prefix[1] == 0x49.toByte() && prefix[2] == 0x46.toByte() && prefix[3] == 0x46.toByte() && prefix[8] == 0x57.toByte() && prefix[9] == 0x45.toByte() && prefix[10] == 0x42.toByte() && prefix[11] == 0x50.toByte())\n        ) return@Interceptor response
        val iv = source.readByteArray(16)
        val key = SecretKeySpec("0B6666A0-BB59-1381-B746-a0E4C9AC".toByteArray(), "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        val result = source.cipherSource(cipher).buffer()
        val newBody = result.asResponseBody(response.body.contentType())
        response.newBuilder()
            .body(newBody)
            .build()
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val body = MangaPayload(page = PagePayload(page), sort = 3)
            .toJsonRequestBody(json)
        val response = client.post("$baseUrl/api/cate/", headers, body).parseAs<MangaResponseDto>()
        val mangas = response.data.list.map { it.toSManga() }
        val pages = (response.data.total + 35) / 36
        return MangasPage(mangas, page < pages)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val body = MangaPayload(page = PagePayload(page), sort = 0)
            .toJsonRequestBody(json)
        val response = client.post("$baseUrl/api/cate/", headers, body).parseAs<MangaResponseDto>()
        val mangas = response.data.list.map { it.toSManga() }
        val pages = (response.data.total + 35) / 36
        return MangasPage(mangas, page < pages)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("type", "mh")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("pageSize", "20")
            .addQueryParameter("keyword", query)
            .build()
        val response = client.get(url, headers).parseAs<MangaResponseDto>()
        val mangas = response.data.list.map { it.toSManga() }
        val pages = (response.data.total + 19) / 20
        return MangasPage(mangas, page < pages)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() != "comic") return null
        val id = url.pathSegments.getOrNull(1)?.takeIf { it.toLongOrNull() != null } ?: return null
        val manga = SManga.create().apply { this.url = "/comic/$id" }
        return getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get("$baseUrl${manga.url}", headers).asJsoup()
        manga.apply {
            title = document.selectFirst("#page-title")?.text() ?: document.title()
            thumbnail_url = document.selectFirst("img.comic-cover")?.absUrl("src")
            author = document.selectFirst("#author-container")?.text()
            artist = author
            genre = document.select("#tagsContainer span.tag").eachText().joinToString()
            status = when (document.selectFirst("#status")?.text()) {
                "连载中" -> SManga.ONGOING
                "已完结" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            description = document.selectFirst("p.comic-desc")?.text()
        }
        val chapters = document.select("#chapter-grid-container > a").map {
            SChapter.create().apply {
                setUrlWithoutDomain(it.absUrl("href"))
                name = it.selectFirst("div.chapter-name")?.text() ?: it.text()
                date_upload = runCatching {
                    LocalDate.parse(it.select("div.chapter-meta span").last()?.text(), dateFormat)
                        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                }.getOrDefault(0L)
            }
        }.asReversed()
        return SMangaUpdate(manga, chapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val imageHost = preferences.getString(IMAGE_HOST_KEY, IMAGE_HOST_ENTRIES[0])
        val url = "$baseUrl/api/comic/image/${chapter.url.substringAfterLast("/")}".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("page_size", "9999")
            .addQueryParameter("image_source", "https://$imageHost")
            .build()
        val response = client.get(url, headers).parseAs<PageListResponseDto>()
        return response.data.images.mapIndexed { index, it ->
            Page(index, imageUrl = it.url)
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = MIRROR_URL_KEY
            title = "鏡像網址"
            entries = MIRROR_URLS
            entryValues = MIRROR_URLS
            setDefaultValue(MIRROR_URLS[0])
            summary = "選擇後請重新啟動閱讀器"
        }.let { screen.addPreference(it) }

        EditTextPreference(screen.context).apply {
            key = CUSTOM_URL_KEY
            title = "自訂 Base URL"
            summary = "優先使用自訂網址（僅接受有效的 HTTPS 網址），修改後請重新啟動閱讀器"
        }.let { screen.addPreference(it) }

        ListPreference(screen.context).apply {
            key = IMAGE_HOST_KEY
            title = "图源"
            entries = IMAGE_HOST_ENTRIES
            entryValues = IMAGE_HOST_ENTRIES
            setDefaultValue(IMAGE_HOST_ENTRIES[0])
        }.let { screen.addPreference(it) }
    }

    companion object {
        private val MIRROR_URLS = arrayOf(
            "https://manwadz.cc",
            "https://mwmwbu.cc",
        )
        private const val MIRROR_URL_KEY = "MIRROR_URL"
        private const val CUSTOM_URL_KEY = "CUSTOM_URL"

        private val IMAGE_HOST_ENTRIES
            get() = arrayOf(
                "tu.mwzu.cc",
                "mwtuwu.cc",
                "124.221.66.202:36662",
            )
        private const val IMAGE_HOST_KEY = "IMG_HOST"
    }
}
