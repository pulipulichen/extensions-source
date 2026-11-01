package eu.kanade.tachiyomi.extension.zh.manwashizuku

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MangaPayload(
    val page: PagePayload,
    val sort: Int,
    val comic: ComicPayload = ComicPayload(),
    val category: String = "comic",
)

@Serializable
data class ComicPayload(
    val areaId: Int = 0,
    val day: Int = 0,
    val level: Int = 0,
    val status: Int = -1,
    val tag: String = "",
)

@Serializable
data class PagePayload(
    val page: Int,
    val pageSize: Int = 36,
)

@Serializable
data class MangaResponseDto(val data: MangaDataDto)

@Serializable
data class MangaDataDto(val list: List<MangaDto>, val total: Int)

@Serializable
data class MangaDto(
    val title: String,
    val pic: String? = null,
    val cover: String? = null,
    val url: String,
) {
    fun toSManga(): SManga = SManga.create().apply {
        title = this@MangaDto.title
        thumbnail_url = pic ?: cover
        url = this@MangaDto.url
    }
}

@Serializable
data class PageListResponseDto(val data: PageListDataDto)

@Serializable
data class PageListDataDto(val images: List<ImageDto>, val pagination: PaginationDto)

@Serializable
data class ImageDto(val url: String)

@Serializable
data class PaginationDto(
    @SerialName("current_page") val currentPage: Int,
    @SerialName("total_pages") val totalPages: Int,
)
