package eu.kanade.tachiyomi.extension.vi.vinahentai

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response

fun getFilters(genres: List<Pair<String, String>>): FilterList = FilterList(
    buildList {
        add(Filter.Header("Nhấn 'Làm mới' để làm mới thể loại"))
        if (genres.isNotEmpty()) {
            add(GenreFilter(genres))
        }
        add(SortFilter())
        add(StatusFilter())
    },
)

fun parseGenresFromHtml(response: Response): List<Pair<String, String>> {
    val document = response.asJsoup()
    val seenSlugs = mutableSetOf<String>()

    return document.select("a[href*=/genres/]")
        .mapNotNull { element ->
            val url = element.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNull null
            val segments = url.pathSegments
            val genreIndex = segments.indexOf("genres")

            val slug = if (genreIndex != -1 && genreIndex + 1 < segments.size) {
                segments[genreIndex + 1]
            } else {
                return@mapNotNull null
            }

            val name = element.text()
            if (slug.isNotEmpty() && name.isNotEmpty() && seenSlugs.add(slug)) {
                Pair(name, slug)
            } else {
                null
            }
        }
        .sortedBy { it.first.lowercase() }
}

class GenreFilter(private val genres: List<Pair<String, String>>) :
    Filter.Select<String>(
        "Thể loại",
        arrayOf("Tất cả") + genres.map { it.first }.toTypedArray(),
    ) {
    val selected get() = if (state == 0) null else genres[state - 1].second
}

class SortFilter :
    Filter.Select<String>(
        "Sắp xếp theo",
        arrayOf("Mới cập nhật", "Xem nhiều", "Đánh giá cao", "Cũ nhất"),
    ) {
    fun toUriPart() = when (state) {
        1 -> "views"
        2 -> "likes"
        3 -> "oldest"
        else -> "updatedAt"
    }
}

class StatusFilter :
    Filter.Select<String>(
        "Tình trạng",
        arrayOf("Tất cả", "Đang tiến hành", "Đã hoàn thành"),
    ) {
    fun toUriPart() = when (state) {
        1 -> "ongoing"
        2 -> "completed"
        else -> ""
    }
}
