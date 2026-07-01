package eu.kanade.tachiyomi.extension.vi.vinahentai

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.firstInstanceOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Calendar

@Source
abstract class VinaHentai :
    HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(3)
        .build()

    private val imageUrlRegex by lazy {
        val baseDomain = baseUrl.toHttpUrl().host
        Regex("""https://cdn\.${Regex.escape(baseDomain)}/manga-images/[^"'\s\\]+""")
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/danh-sach?page=$page&sort=views", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseMangaListPage(response.asJsoup())

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/danh-sach?page=$page&sort=updatedAt", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaListPage(response.asJsoup())

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotEmpty()) {
            val url = "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .addQueryParameter("q", query)
                .build()
            return GET(url, headers)
        }

        val genreSlug = filters.firstInstanceOrNull<GenreFilter>()?.selected
        val sort = filters.firstInstanceOrNull<SortFilter>()?.toUriPart() ?: "updatedAt"
        val status = filters.firstInstanceOrNull<StatusFilter>()?.toUriPart() ?: ""

        val urlBuilder = if (genreSlug != null) {
            "$baseUrl/genres/$genreSlug".toHttpUrl().newBuilder()
        } else {
            "$baseUrl/danh-sach".toHttpUrl().newBuilder()
        }

        urlBuilder.addQueryParameter("page", page.toString())
            .addQueryParameter("sort", sort)

        if (status.isNotEmpty()) {
            urlBuilder.addQueryParameter("status", status)
        }

        return GET(urlBuilder.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.pathSegments.firstOrNull() == "search") {
            return parseSearchPage(response)
        }
        return parseMangaListPage(response.asJsoup())
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        return SManga.create().apply {
            title = document.selectFirst("h1")!!.text()

            author = document.select("a[href^=/authors/]")
                .mapNotNull { it.text().takeUnless { text -> text.startsWith("+") } }
                .joinToString()
                .ifEmpty { null }

            genre = document.select("a[href^=/genres/]")
                .mapNotNull { it.text().takeUnless { text -> text.startsWith("+") } }
                .joinToString()
                .ifEmpty { null }

            thumbnail_url = document.selectFirst("img[alt*=Bìa]")?.absUrl("src")
                ?: document.selectFirst("img[src*=story-images]")?.absUrl("src")

            description = document.selectFirst("#manga-description-section .text-txt-secondary")?.text()

            status = document.body().text().lowercase().let { bodyText ->
                when {
                    "đang tiến hành" in bodyText -> SManga.ONGOING
                    "đã hoàn thành" in bodyText -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
            }
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        return document.select("a.block[href^=/truyen-hentai/]")
            .filter { it.attr("href").count { char -> char == '/' } > 2 }
            .map { element ->
                SChapter.create().apply {
                    setUrlWithoutDomain(element.absUrl("href"))
                    name = element.selectFirst("span")?.text() ?: element.text()
                    date_upload = parseRelativeDate(element.selectFirst("time")?.text())
                }
            }
    }

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body.string()

        return imageUrlRegex.findAll(body)
            .map { it.value }
            .distinct()
            .mapIndexed { index, url -> Page(index, imageUrl = url) }
            .toList()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var genreList: List<Pair<String, String>> = emptyList()
    private var fetchGenresAttempts: Int = 0

    private fun fetchGenres() {
        if (genreList.isEmpty() && fetchGenresAttempts < 3) {
            scope.launch {
                try {
                    client.newCall(GET("$baseUrl/danh-sach", headers)).await()
                        .use { response ->
                            parseGenresFromHtml(response)
                                .takeIf { it.isNotEmpty() }
                                ?.let { genreList = it }
                        }
                } catch (_: Exception) {
                } finally {
                    fetchGenresAttempts++
                }
            }
        }
    }

    override fun getFilterList(): FilterList {
        fetchGenres()
        return getFilters(genreList)
    }

    private fun parseMangaListPage(document: Document): MangasPage {
        val mangaList = document.select("a[href^=/truyen-hentai/][data-variant]")
            .map(::mangaFromGridElement)

        return MangasPage(mangaList, mangaList.size >= MANGA_PER_PAGE)
    }

    private fun parseSearchPage(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangaList = document.select("a[href^=/truyen-hentai/]:has(h2)")
            .map { element ->
                SManga.create().apply {
                    setUrlWithoutDomain(element.absUrl("href"))
                    title = element.selectFirst("h2")!!.text()
                    thumbnail_url = element.selectFirst("img")?.absUrl("src")
                }
            }

        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = document.select("a[href*=page=]")
            .any { element ->
                val page = element.absUrl("href").toHttpUrlOrNull()?.queryParameter("page")?.toIntOrNull()
                page != null && page > currentPage
            }

        return MangasPage(mangaList, hasNextPage)
    }

    private fun mangaFromGridElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        val titleDiv = element.selectFirst("div.truncate.font-semibold[title]")
        title = titleDiv?.attr("title")?.takeIf { it.isNotEmpty() } ?: titleDiv?.text() ?: ""
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
    }

    private fun parseRelativeDate(dateStr: String?): Long {
        if (dateStr.isNullOrEmpty()) return 0L

        val calendar = Calendar.getInstance()
        val number = NUMBER_REGEX.find(dateStr)?.value?.toIntOrNull() ?: return 0L

        when {
            "giây" in dateStr -> calendar.add(Calendar.SECOND, -number)
            "phút" in dateStr -> calendar.add(Calendar.MINUTE, -number)
            "giờ" in dateStr -> calendar.add(Calendar.HOUR_OF_DAY, -number)
            "ngày" in dateStr -> calendar.add(Calendar.DAY_OF_MONTH, -number)
            "tuần" in dateStr -> calendar.add(Calendar.WEEK_OF_YEAR, -number)
            "tháng" in dateStr -> calendar.add(Calendar.MONTH, -number)
            "năm" in dateStr -> calendar.add(Calendar.YEAR, -number)
            else -> return 0L
        }

        return calendar.timeInMillis
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}

    companion object {
        private const val MANGA_PER_PAGE = 24
        private val NUMBER_REGEX = Regex("""\d+""")
    }
}
