package eu.kanade.tachiyomi.extension.tr.merlinscans

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class MerlinScans : ParsedHttpSource() {

    override val name = "MerlinScans"
    override val baseUrl = "https://merlinscans.com"
    override val lang = "tr"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }
    private val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("tr"))
    private val alternativeDateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    // Extension fonksiyonları eklendi
    private inline fun <reified T> Response.parseAs(): T {
        return json.decodeFromString(body.string())
    }

    private fun SimpleDateFormat.tryParse(dateStr: String): Long? {
        return try {
            parse(dateStr)?.time
        } catch (e: Exception) {
            null
        }
    }

    private fun normalizeUrl(url: String): String {
        return when {
            url.contains("/series.php?slug=") -> {
                val httpUrl = url.toHttpUrl()
                val slug = httpUrl.queryParameter("slug") ?: return url
                httpUrl.newBuilder()
                    .encodedPath("/series/$slug")
                    .query(null)
                    .build()
                    .encodedPath
            }
            url.startsWith("/series/") -> url
            else -> url
        }
    }

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/all-series.php".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "views")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaSelector() = "div.series-grid a.series-card"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            val originalUrl = element.attr("href")
            setUrlWithoutDomain(normalizeUrl(originalUrl))
            title = element.select("div.series-title").text()
            thumbnail_url = element.select("img").attr("src")
        }
    }

    override fun popularMangaNextPageSelector() = "li:not(.disabled) a i.fa-chevron-right"

    override fun latestUpdatesRequest(page: Int): Request {
        return GET(baseUrl, headers)
    }

    override fun latestUpdatesSelector() =
        "div.latest-chapters-grid div.latest-series-horizontal-card"

    override fun latestUpdatesFromElement(element: Element): SManga {
        return SManga.create().apply {
            val seriesLink = element.select("h3.series-title a").attr("href")
            setUrlWithoutDomain(normalizeUrl(seriesLink))
            title = element.select("h3.series-title a").text()
            thumbnail_url = element.select("div.series-cover-thumbnail img").attr("src")
        }
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotEmpty()) {
            val url = "$baseUrl/ajax/search-preview.php".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .build()
            return GET(url, headers)
        }

        val url = "$baseUrl/all-series.php".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        var sortApplied = false

        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("type", filter.toUriPart())
                    }
                }

                is CategoryFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("category", filter.toUriPart())
                    }
                }

                is StatusFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("status", filter.toUriPart())
                    }
                }

                is SortFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("sort", filter.toUriPart())
                        sortApplied = true
                    }
                }

                else -> {}
            }
        }

        if (!sortApplied) {
            url.addQueryParameter("sort", "views")
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val requestUrl = response.request.url.toString()

        return if (requestUrl.contains("search-preview.php")) {
            parseSearchJson(response)
        } else {
            super.searchMangaParse(response)
        }
    }

    private fun parseSearchJson(response: Response): MangasPage {
        val searchResponse: SearchResponse = response.parseAs()

        val mangas = if (searchResponse.success) {
            searchResponse.results.map { result ->
                SManga.create().apply {
                    title = result.title
                    thumbnail_url = result.coverImage
                    setUrlWithoutDomain(normalizeUrl(result.url))

                    if (result.categories.isNotEmpty()) {
                        genre = result.categories.split(", ")
                            .distinct()
                            .take(10)
                            .joinToString(", ")
                    }
                }
            }
        } else {
            emptyList()
        }

        return MangasPage(mangas, false)
    }

    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.select("h1.series-title").text()
            thumbnail_url = document.select(".cover-image").attr("src")
            description = document.select(".description-text").text()

            author = document.select(".metadata-item .metadata-icon-label:contains(Yazar) + .metadata-value").text()

            val statusText = document.select(".metadata-item .metadata-icon-label:contains(Durum) + .metadata-value").text()

            status = when {
                statusText.contains("Devam Ediyor", true) -> SManga.ONGOING
                statusText.contains("Tamamlandı", true) -> SManga.COMPLETED
                statusText.contains("Ara Verildi", true) -> SManga.ON_HIATUS
                statusText.contains("İptal", true) -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }

            genre = document.select(".category-badge")
                .joinToString(", ") { it.text() }
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun chapterListSelector() = ".chapters-grid .chapter-item"

    override fun chapterFromElement(element: Element): SChapter {
        return SChapter.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            name = element.select(".chapter-title").text()

            val dateText = element.select(".chapter-date").text()
            date_upload = parseDate(dateText)
        }
    }

    private fun parseDate(dateStr: String): Long {
        return when {
            dateStr.contains("önce") -> {
                val now = System.currentTimeMillis()
                when {
                    dateStr.contains("dakika") -> {
                        val minutes = dateStr.filter { it.isDigit() }.toLongOrNull() ?: 0
                        now - (minutes * 60 * 1000)
                    }

                    dateStr.contains("saat") -> {
                        val hours = dateStr.filter { it.isDigit() }.toLongOrNull() ?: 0
                        now - (hours * 60 * 60 * 1000)
                    }

                    dateStr.contains("gün") -> {
                        val days = dateStr.filter { it.isDigit() }.toLongOrNull() ?: 0
                        now - (days * 24 * 60 * 60 * 1000)
                    }

                    else -> now
                }
            }

            else -> {
                dateFormat.tryParse(dateStr) ?: alternativeDateFormat.tryParse(dateStr) ?: 0L
            }
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val allChapters = mutableListOf<SChapter>()
        val originalUrl = response.request.url.toString()

        val httpUrl = originalUrl.toHttpUrl()
        val baseUrlBuilder = httpUrl.newBuilder()

        // Tüm query parametrelerini kaldır
        httpUrl.queryParameterNames.forEach { paramName ->
            baseUrlBuilder.removeAllQueryParameters(paramName)
        }

        val cleanBaseUrl = baseUrlBuilder.build().toString()

        val firstPageChapters =
            document.select(chapterListSelector()).map { chapterFromElement(it) }
        allChapters.addAll(firstPageChapters)

        val lastPageNumber = findLastPageNumber(document)

        for (page in 2..lastPageNumber) {
            try {
                val pageUrl = cleanBaseUrl.toHttpUrl().newBuilder()
                    .addQueryParameter("page", page.toString())
                    .addQueryParameter("sort", "desc")
                    .build()

                val pageResponse = client.newCall(GET(pageUrl, headers)).execute()
                val pageDocument = pageResponse.asJsoup()

                val pageChapters =
                    pageDocument.select(chapterListSelector()).map { chapterFromElement(it) }

                if (pageChapters.isEmpty()) {
                    break
                }

                allChapters.addAll(pageChapters)

                pageResponse.close()
            } catch (e: Exception) {
                break
            }
        }

        return allChapters.distinctBy { it.url }
    }

    private fun findLastPageNumber(document: Document): Int {
        return try {
            val pageNumbers = document.select(".pagination-buttons .page-btn")
                .mapNotNull { element ->
                    val text = element.text().trim()
                    if (text.matches("\\d+".toRegex())) {
                        text.toIntOrNull()
                    } else {
                        null
                    }
                }
            pageNumbers.maxOrNull() ?: 1
        } catch (e: Exception) {
            1
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        return GET(baseUrl + chapter.url, headers)
    }

    override fun pageListParse(document: Document): List<Page> {
        return document.select("#chapter-images img, .chapter-content img, .reader-container img")
            .mapIndexed { index, element ->
                val imageUrl = element.attr("data-src").ifEmpty {
                    element.attr("src").ifEmpty { element.attr("data-original") }
                }
                Page(index, "", imageUrl)
            }
    }

    override fun imageUrlParse(document: Document): String = ""

    override fun getFilterList(): FilterList {
        return FilterList(
            TypeFilter(),
            CategoryFilter(),
            StatusFilter(),
            SortFilter(),
        )
    }

    private class TypeFilter : UriPartFilter(
        "Tür",
        arrayOf(
            Pair("Tümü", ""),
            Pair("Anime", "anime"),
            Pair("Manga", "manga"),
            Pair("Novel", "novel"),
            Pair("Webtoon", "webtoon"),
        ),
    )

    private class CategoryFilter : UriPartFilter(
        "Kategori",
        arrayOf(
            Pair("Tümü", ""),
            Pair("Aksiyon", "aksiyon"),
            Pair("Bilim Kurgu", "bilim-kurgu"),
            Pair("Canavar", "canavar"),
            Pair("Dahi MC", "dahi-mc"),
            Pair("Doğaüstü", "dogaustu"),
            Pair("Dövüş Sanatları", "dovus-sanatlari"),
            Pair("Dram", "dram"),
            Pair("Fantastik", "fantastik"),
            Pair("Fantezi", "fantezi"),
            Pair("Gender Bender", "gender-bender"),
            Pair("Geri Dönüş", "geri-donus"),
            Pair("Gizem", "gizem"),
            Pair("Harem", "harem"),
            Pair("Hayattan Kesitler", "hayattan-kesitler"),
            Pair("İntikam", "intikam"),
            Pair("Josei", "josei"),
            Pair("Komedi", "komedi"),
            Pair("Korku", "korku"),
            Pair("Macera", "macera"),
            Pair("Murim", "murim"),
            Pair("Okul Yaşamı", "okul-yasami"),
            Pair("Psikolojik", "psikolojik"),
            Pair("Reenkarne", "reenkarne"),
            Pair("Romantik", "romantik"),
            Pair("Romantizm", "romantizm"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shounen", "shounen"),
            Pair("Spor", "spor"),
            Pair("Tarihi", "tarihi"),
            Pair("Trajedi", "trajedi"),
        ),
    )

    private class StatusFilter : UriPartFilter(
        "Durum",
        arrayOf(
            Pair("Tümü", ""),
            Pair("Devam Ediyor", "ongoing"),
            Pair("Tamamlandı", "completed"),
            Pair("Ara Verildi", "hiatus"),
            Pair("İptal Edildi", "cancelled"),
        ),
    )

    private class SortFilter : UriPartFilter(
        "Sırala",
        arrayOf(
            Pair("Varsayılan", ""),
            Pair("En Yeni", "latest"),
            Pair("En Eski", "oldest"),
            Pair("İsme Göre (A-Z)", "alphabetical"),
            Pair("Puana Göre", "rating"),
            Pair("Görüntülenmeye Göre", "views"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}
