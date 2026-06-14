package eu.kanade.tachiyomi.extension.pt.littletyrant

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.seconds

class LittleTyrant :
    Madara(
        "Little Tyrant",
        "https://tiraninha.world",
        "pt-BR",
        dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale("pt", "BR")),
    ) {

    override fun headersBuilder() = super.headersBuilder()
        .add("X-Reader-Sec", "tiraninha-web")

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain -> imageIntercept(chain) }
        .rateLimit(3, 1.seconds)
        .build()

    private val decoder by lazy { Decoder() }

    override val useLoadMoreRequest = LoadMoreStrategy.Never

    // =============================== Popular =================================

    override fun popularMangaSelector() = "[id*=manga-item-]"
    override val popularMangaUrlSelector = ".card-title a"

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        title = element.selectFirst("h3")!!.text()
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
        setUrlWithoutDomain(element.selectFirst(popularMangaUrlSelector)!!.absUrl("href"))
    }

    // =============================== Details =================================

    override val mangaDetailsSelectorGenre = ".mc-genres-pills a"
    override val mangaDetailsSelectorDescription = ".mc-description-box"
    override val mangaDetailsSelectorAuthor = ".mc-meta-grid .attr-item:has(.attr-label:contains(AUTOR)) .attr-value"
    override val mangaDetailsSelectorArtist = ".mc-meta-grid .attr-item:has(.attr-label:contains(ARTISTA)) .attr-value"
    override val mangaDetailsSelectorStatus = ".mc-meta-grid .attr-item:has(.attr-label:contains(STATUS)) .attr-value"

    override fun mangaDetailsParse(document: Document): SManga = super.mangaDetailsParse(document).apply {
        author = author?.replace(COMMA_REGEX, ", ")?.takeUnless { it.contains("---") }
        artist = artist?.replace(COMMA_REGEX, ", ")?.takeUnless { it.contains("---") }
    }

    // =============================== Chapters =================================

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val document = client.newCall(mangaDetailsRequest(manga)).execute().asJsoup()
        val mangaId = document.selectFirst("a.wp-manga-action-button")!!.attr("data-post")
        val chapters = mutableListOf<SChapter>()
        val url = "$baseUrl/wp-admin/admin-ajax.php"
        var offset = 0
        do {
            val form = FormBody.Builder()
                .add("action", "load_more_chapters")
                .add("manga_id", mangaId)
                .add("offset", offset.toString())
                .build()
            offset += 12
            val dto = client.newCall(POST(url, headers, form)).execute().parseAs<ChapterDto>()
            val chapterElements = dto.toJsoup(baseUrl).select(chapterListSelector())
            chapters += chapterElements.map(::chapterFromElement)
        } while (!dto.isEmpty())

        chapters.sortedByDescending(SChapter::chapter_number)
    }

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        name = element.selectFirst("span.mc-chapter-title")!!.text()
        date_upload = parseChapterDate(element.selectFirst(".mc-chapter-date")?.text())
        CHAPTER_NUMBER_REGEX.find(name)?.groupValues?.last()?.toFloatOrNull()?.let {
            chapter_number = it
        }
        setUrlWithoutDomain(element.selectFirst("a.mc-chapter-link")!!.absUrl("href"))
    }

    // =============================== Pages =================================

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = client.newCall(pageListRequest(chapter))
        .asObservableSuccess()
        .map { response ->
            val doc = response.asJsoup()
            launchIO { countViews(doc) }

            val referer = response.request.url.toString()
            ensureValidToken(referer)

            decoder.extractPaths(doc, baseUrl).mapIndexed { idx, url ->
                Page(idx, url = referer, imageUrl = url)
            }
        }

    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()

    // =============================== Images =================================

    @Volatile private var imageSecToken = ""
    private val tokenLock = ReentrantLock()

    private fun fetchGatekeeperToken(referer: String): String? {
        val gatekeeperUrl = "$baseUrl/wp-content/themes/madara2/gatekeeper.php?t=${System.currentTimeMillis()}"
        val gatekeeperReq = GET(
            gatekeeperUrl,
            headers.newBuilder()
                .set("Accept", "*/*")
                .set("Accept-Language", "pt-BR,pt;q=0.9")
                .set("Sec-Fetch-Dest", "empty")
                .set("Sec-Fetch-Mode", "cors")
                .set("Sec-Fetch-Site", "same-origin")
                .set("Referer", referer)
                .build(),
        )

        return try {
            network.client.newCall(gatekeeperReq).execute().use { res ->
                if (res.isSuccessful) {
                    res.parseAs<TokenDto>().token
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun ensureValidToken(referer: String, force: Boolean = false): Boolean {
        if (!force && imageSecToken.isNotEmpty()) return true

        return tokenLock.withLock {
            if (!force && imageSecToken.isNotEmpty()) return true
            val token = fetchGatekeeperToken(referer)
            if (token != null) {
                imageSecToken = token
                android.util.Log.d("LittleTyrant", "Token Refreshed: $imageSecToken")
                true
            } else {
                android.util.Log.e("LittleTyrant", "Failed to refresh token")
                false
            }
        }
    }

    private fun imageIntercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (!request.url.encodedPath.contains("image-loader.php")) {
            return chain.proceed(request)
        }

        val referer = request.header("Referer") ?: "$baseUrl/"
        ensureValidToken(referer)

        fun updateCookieJarWithToken() {
            if (imageSecToken.isNotEmpty()) {
                val cookie = Cookie.Builder()
                    .domain(request.url.host)
                    .path("/")
                    .name("lt_sec_val")
                    .value(imageSecToken)
                    .build()

                network.client.cookieJar.saveFromResponse(request.url, listOf(cookie))
            }
        }

        fun proceedWithToken(): Response {
            val requestBuilder = request.newBuilder()

            if (imageSecToken.isNotEmpty()) {
                requestBuilder.header("Cookie", "lt_sec_val=$imageSecToken")
            }

            return chain.proceed(requestBuilder.build())
        }

        var response = proceedWithToken()

        if (response.code == 403) {
            response.close()
            android.util.Log.d("LittleTyrant", "Got 403, forcing token refresh...")
            ensureValidToken(referer, force = true)
            response = proceedWithToken()
        }

        if (!response.isSuccessful) return response

        if (response.header("Content-Type") == "application/octet-stream") {
            return response.newBuilder()
                .header("Content-Type", "image/jpeg")
                .build()
        }

        return response
    }

    override fun imageRequest(page: Page): Request = GET(
        page.imageUrl!!,
        headers.newBuilder()
            .set("Referer", page.url)
            .set("Accept", "image/avif,image/webp,image/apng,image/jpeg,image/png,*/*;q=0.8")
            .set("Sec-Fetch-Dest", "image")
            .set("Sec-Fetch-Mode", "no-cors")
            .set("Sec-Fetch-Site", "same-origin")
            .build(),
    )

    companion object {
        private val CHAPTER_NUMBER_REGEX = """\d+(?:\.\d+)?""".toRegex()
        private val COMMA_REGEX = """,\s*""".toRegex()
    }
}
