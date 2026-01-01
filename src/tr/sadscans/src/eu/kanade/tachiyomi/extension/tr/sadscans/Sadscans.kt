package eu.kanade.tachiyomi.extension.tr.sadscans

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder
import java.util.Calendar

class Sadscans : ParsedHttpSource() {

    override val name = "Sadscans"

    override val baseUrl = "https://sadscans.net"

    override val lang = "tr"

    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val json: Json by injectLazy()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/seriler", headers)

    override fun popularMangaSelector() = "a.block.series-card, a[href^='/seriler/']:has(img)"

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        title = element.selectFirst("h3")?.text()
            ?: element.selectFirst("span.font-semibold, span.font-bold")?.text()
            ?: element.text().trim().split("\n").firstOrNull() ?: ""

        // Look for series cover (thumb_ in series folder, not chapter folder)
        // Skip chapter thumbnails which have /images/ in path
        thumbnail_url = element.select("img").firstOrNull { img ->
            val src = img.absUrl("src").ifEmpty { img.absUrl("data-src") }
            src.isNotEmpty() && (!src.contains("/images/") || !src.contains("thumb_"))
        }?.let { img ->
            img.absUrl("src").ifEmpty { img.absUrl("data-src") }
        }
    }

    override fun popularMangaNextPageSelector(): String? = null

    // ============================== Latest ==============================

    override fun latestUpdatesRequest(page: Int) = GET(baseUrl, headers)

    // Select the container divs that contain series links in the latest updates grid
    override fun latestUpdatesSelector() = "div.grid > div.relative:has(a[href^='/seriler/'])"

    override fun latestUpdatesFromElement(element: Element) = SManga.create().apply {
        // Get the series link from the container - the manga title h3 is INSIDE the series link
        val seriesLink = element.selectFirst("a[href^='/seriler/']")
        url = seriesLink?.attr("href") ?: ""

        // The manga title is in the h3 element INSIDE the series link (not chapter titles)
        title = seriesLink?.selectFirst("h3")?.text()
            ?: element.selectFirst("a[href^='/seriler/'] h3")?.text()
            ?: ""

        // Look for series cover (thumb_ in series folder, not chapter folder)
        // Skip chapter thumbnails which have /images/ in path
        thumbnail_url = element.select("img").firstOrNull { img ->
            val src = img.absUrl("src").ifEmpty { img.absUrl("data-src") }
            src.contains("thumb_") && !src.contains("/images/")
        }?.let { img ->
            img.absUrl("src").ifEmpty { img.absUrl("data-src") }
        }
        // If no series cover found, leave null - mangaDetailsParse will fetch it
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    // ============================== Search ==============================

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith(SLUG_SEARCH_PREFIX)) {
            val slug = query.substringAfter(SLUG_SEARCH_PREFIX)
            val url = "/seriler/$slug"
            return fetchMangaDetails(SManga.create().apply { this.url = url })
                .map {
                    it.url = url
                    MangasPage(listOf(it), false)
                }
        }
        // Use API-based search
        return client.newCall(searchMangaRequest(page, query, filters))
            .asObservableSuccess()
            .map { response -> parseSearchResponse(response) }
    }

    private fun parseSearchResponse(response: Response): MangasPage {
        val responseBody = response.body.string()
        val searchResults = json.decodeFromString<List<SearchApiResponse>>(responseBody)
        val mangas = searchResults.firstOrNull()?.result?.data?.json?.map { series ->
            SManga.create().apply {
                url = series.href ?: "/seriler/${series.sef}"
                title = series.name
                thumbnail_url = when {
                    series.thumb.startsWith("http") -> series.thumb
                    series.thumb.startsWith("/") -> "$baseUrl${series.thumb}"
                    else -> "$baseUrl/${series.thumb}"
                }
                author = series.author
                status = when {
                    series.status.contains("Devam", ignoreCase = true) -> SManga.ONGOING
                    series.status.contains("Tamamlandı", ignoreCase = true) -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
            }
        } ?: emptyList()
        return MangasPage(mangas, false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val input = """{"0":{"json":{"search":"$query"}}}"""
        val encodedInput = URLEncoder.encode(input, "UTF-8")
        return GET("$baseUrl/api/trpc/srs.getSeries?batch=1&input=$encodedInput", headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector(): String? = null

    override fun searchMangaParse(response: Response): MangasPage = parseSearchResponse(response)

    // ============================== Details ==============================

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        title = document.selectFirst("h1")?.text() ?: ""

        // Find cover image - use the specific container with rounded-xl class
        // Avoid site logo (/images/logo) and banner images
        thumbnail_url = document.selectFirst("div.relative.rounded-xl img")?.absUrl("src")
            ?: document.select("img").firstOrNull { img ->
                val src = img.absUrl("src")
                src.isNotEmpty() &&
                    !src.contains("/images/logo") &&
                    !src.contains("banner") &&
                    !src.contains("/images/") &&
                    (src.contains("/assets/series/") || src.contains("/seriler/"))
            }?.absUrl("src")
            ?: extractCoverFromScript(document)

        // Site uses Next.js - content is dynamically loaded, so use meta tags
        // Description is in og:description or meta description
        description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?.let { cleanDescription(it) }
            ?: document.selectFirst("meta[name=description]")?.attr("content")
                ?.let { cleanDescription(it) }
            ?: extractDescriptionFromScript(document)

        // Extract author/artist - try DOM first, then scripts
        // Site uses dynamic rendering so DOM might be empty
        val metadataContainer = document.select("div.flex.items-center.gap-2")
        var authorArtistSpan = metadataContainer.find { div ->
            div.text().contains("Yazar", ignoreCase = true)
        }?.select("span")?.lastOrNull()?.text()

        // Fallback: extract from script data
        if (authorArtistSpan == null || authorArtistSpan.contains("Yazar")) {
            authorArtistSpan = extractAuthorFromScript(document)
        }

        if (authorArtistSpan != null && !authorArtistSpan.contains("Yazar")) {
            val parts = authorArtistSpan.split(",").map { it.trim() }
            author = parts.firstOrNull()
            artist = if (parts.size > 1) parts[1] else author
        }

        // Extract status - try DOM first, then default
        val statusSpan = metadataContainer.find { div ->
            div.text().contains("Durum", ignoreCase = true)
        }?.select("span")?.lastOrNull()?.text()
            ?: extractStatusFromScript(document)
            ?: ""

        status = when {
            statusSpan.contains("Devam", ignoreCase = true) -> SManga.ONGOING
            statusSpan.contains("Tamamlandı", ignoreCase = true) -> SManga.COMPLETED
            statusSpan.contains("Bitti", ignoreCase = true) -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }

        // Extract genres from links (if available in initial HTML)
        genre = document.select("a[href*='/tur/'], a[href*='/genre/']").joinToString { it.text() }
    }

    private fun extractCoverFromScript(document: Document): String? {
        val scripts = document.select("script").map { it.data() }.joinToString("")

        // Look for thumb in series data: "thumb":"thumb_XXXXX.jpg"
        val thumbPattern = """"thumb"\s*:\s*"(thumb_[^"]+)"""".toRegex()
        val match = thumbPattern.find(scripts)

        if (match != null) {
            val thumbFile = match.groupValues[1]
            // Need to find the series ID to construct full URL
            val idPattern = """"id"\s*:\s*"([A-Za-z0-9_-]+)"""".toRegex()
            val idMatch = idPattern.find(scripts)
            if (idMatch != null) {
                val seriesId = idMatch.groupValues[1]
                return "$baseUrl/api/uploads/assets/series/$seriesId/$thumbFile"
            }
        }

        return null
    }

    // Clean site prefix from meta description
    // Format: "Manga Adı - Türkçe Manga SadScans üzerinden oku. [Actual description]"
    private fun cleanDescription(desc: String): String? {
        // Remove common prefixes
        val cleaned = desc
            .replace(Regex("^.*?üzerinden oku\\.\\s*"), "")
            .replace(Regex("^.*?türkçe oku\\.\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
        return cleaned.takeIf { it.length > 10 }
    }

    // Extract description from ld+json script
    private fun extractDescriptionFromScript(document: Document): String? {
        val ldJson = document.selectFirst("script[type=application/ld+json]")?.data()
        if (ldJson != null) {
            val descPattern = """"description"\s*:\s*"([^"]+)"""".toRegex()
            val match = descPattern.find(ldJson)
            if (match != null) {
                return match.groupValues[1].takeIf { it.length > 10 }
            }
        }
        return null
    }

    // Extract author from Next.js script data
    private fun extractAuthorFromScript(document: Document): String? {
        val scripts = document.select("script").map { it.data() }.joinToString("")
        // Look for "author":"Name" pattern in the script data
        val authorPattern = """"author"\s*:\s*"([^"]+)"""".toRegex()
        val match = authorPattern.find(scripts)
        return match?.groupValues?.get(1)
    }

    // Extract status from Next.js script data
    private fun extractStatusFromScript(document: Document): String? {
        val scripts = document.select("script").map { it.data() }.joinToString("")
        // Look for status in script data
        val statusPattern = """"status"\s*:\s*"([^"]+)"""".toRegex()
        val match = statusPattern.find(scripts)
        return match?.groupValues?.get(1)
    }

    // ============================== Chapters ==============================

    // Use API-based chapter fetching to get all chapters with pagination
    override fun chapterListSelector() = throw UnsupportedOperationException()
    override fun chapterFromElement(element: Element) = throw UnsupportedOperationException()

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body.string(), response.request.url.toString())
        // Extract series slug from URL
        val seriesSlug = response.request.url.pathSegments.lastOrNull() ?: return emptyList()

        // Fetch all chapters via API with pagination
        return fetchAllChapters(seriesSlug)
    }

    private fun fetchAllChapters(seriesSlug: String): List<SChapter> {
        val allChapters = mutableListOf<SChapter>()
        var page = 1
        var totalPages = 1

        do {
            val input = """{"0":{"json":{"sef":"$seriesSlug","page":$page,"limit":20}}}"""
            val encodedInput = URLEncoder.encode(input, "UTF-8")
            val apiUrl = "$baseUrl/api/trpc/srsDtl.getSeriesData?batch=1&input=$encodedInput"

            val request = GET(apiUrl, headers)
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) break

            val responseBody = response.body.string()
            val apiResponse = json.decodeFromString<List<ChapterApiResponse>>(responseBody)
            val chapterData = apiResponse.firstOrNull()?.result?.data?.json ?: break

            totalPages = chapterData.pagination?.totalPages ?: 1

            chapterData.chapters?.forEach { chapter ->
                val chapterNum = if (chapter.no == chapter.no.toLong().toDouble()) {
                    chapter.no.toLong().toString()
                } else {
                    chapter.no.toString()
                }
                allChapters.add(
                    SChapter.create().apply {
                        url = chapter.href ?: "/reader/$seriesSlug/$chapterNum-bolum/${chapter.chap_id}"
                        name = if (chapter.name.isNotEmpty()) {
                            "$chapterNum. Bölüm - ${chapter.name}"
                        } else {
                            "$chapterNum. Bölüm"
                        }
                        date_upload = parseApiDate(chapter.date)
                    },
                )
            }

            page++
        } while (page <= totalPages)

        return allChapters
    }

    private fun parseApiDate(dateStr: String): Long {
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // ============================== Pages ==============================

    override fun pageListParse(document: Document): List<Page> {
        // Images are stored in Next.js hydration data with escaped JSON
        // Format: \"images\":[{\"page\":1,\"src\":\"/api/uploads/...\"}]
        val scripts = document.select("script").map { it.data() }
        val allScriptContent = scripts.joinToString("")

        val imageUrls = extractImageUrls(allScriptContent)
        if (imageUrls.isNotEmpty()) {
            return imageUrls.mapIndexed { index, url ->
                val fullUrl = if (url.startsWith("http")) url else "$baseUrl$url"
                Page(index, imageUrl = fullUrl)
            }
        }

        // Fallback: try to find any images on the page
        val fallbackImages = document.select("img[src*='/images/'], img[src*='/uploads/']")
        if (fallbackImages.isNotEmpty()) {
            return fallbackImages.mapIndexed { index, img ->
                Page(index, imageUrl = img.absUrl("src"))
            }
        }

        return emptyList()
    }

    private fun extractImageUrls(script: String): List<String> {
        val pageUrlPairs = mutableListOf<Pair<Int, String>>()

        // Pattern for escaped JSON format: \"page\":1,\"src\":\"/api/uploads/...\"
        // In Kotlin raw strings, we need to match the literal backslash-quote
        val escapedPattern = """\\?"page\\?":\s*(\d+)\s*,\s*\\?"src\\?":\s*\\?"([^"\\]+(?:\.avif|\.jpg|\.jpeg|\.png|\.webp|\.pdf))\\?"""".toRegex()

        escapedPattern.findAll(script).forEach { match ->
            val page = match.groupValues[1].toIntOrNull() ?: 0
            var url = match.groupValues[2]
            // Unescape the URL
            url = url.replace("\\/", "/").replace("\\u002F", "/")
            if (url.contains("/api/uploads/") || url.contains("/images/")) {
                pageUrlPairs.add(Pair(page, url))
            }
        }

        // If escaped pattern didn't work, try regular JSON format
        if (pageUrlPairs.isEmpty()) {
            val regularPattern = """"page"\s*:\s*(\d+)\s*,\s*"src"\s*:\s*"([^"]+(?:\.avif|\.jpg|\.jpeg|\.png|\.webp|\.pdf))"""".toRegex()
            regularPattern.findAll(script).forEach { match ->
                val page = match.groupValues[1].toIntOrNull() ?: 0
                val url = match.groupValues[2]
                if (url.contains("/api/uploads/") || url.contains("/images/")) {
                    pageUrlPairs.add(Pair(page, url))
                }
            }
        }

        // Direct URL extraction as last resort
        if (pageUrlPairs.isEmpty()) {
            val directPattern = """(/api/uploads/[^"\\,\s]+(?:\.avif|\.jpg|\.jpeg|\.png|\.webp|\.pdf))""".toRegex()
            directPattern.findAll(script).forEach { match ->
                val url = match.groupValues[1].replace("\\/", "/")
                pageUrlPairs.add(Pair(pageUrlPairs.size, url))
            }
        }

        return pageUrlPairs.sortedBy { it.first }.map { it.second }.distinct()
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================== Utilities ==============================

    private fun parseRelativeDate(dateStr: String): Long {
        val now = Calendar.getInstance()
        val lowerDate = dateStr.lowercase()

        return when {
            lowerDate.contains("saat") -> {
                val hours = NUMBER_REGEX.find(lowerDate)?.value?.toIntOrNull() ?: 1
                now.add(Calendar.HOUR, -hours)
                now.timeInMillis
            }
            lowerDate.contains("dakika") -> {
                val minutes = NUMBER_REGEX.find(lowerDate)?.value?.toIntOrNull() ?: 1
                now.add(Calendar.MINUTE, -minutes)
                now.timeInMillis
            }
            lowerDate.contains("gün") -> {
                val days = NUMBER_REGEX.find(lowerDate)?.value?.toIntOrNull() ?: 1
                now.add(Calendar.DAY_OF_MONTH, -days)
                now.timeInMillis
            }
            lowerDate.contains("hafta") -> {
                val weeks = NUMBER_REGEX.find(lowerDate)?.value?.toIntOrNull() ?: 1
                now.add(Calendar.WEEK_OF_YEAR, -weeks)
                now.timeInMillis
            }
            lowerDate.contains("ay") -> {
                val months = NUMBER_REGEX.find(lowerDate)?.value?.toIntOrNull() ?: 1
                now.add(Calendar.MONTH, -months)
                now.timeInMillis
            }
            lowerDate.contains("yıl") -> {
                val years = NUMBER_REGEX.find(lowerDate)?.value?.toIntOrNull() ?: 1
                now.add(Calendar.YEAR, -years)
                now.timeInMillis
            }
            else -> 0L
        }
    }

    companion object {
        const val SLUG_SEARCH_PREFIX = "slug:"

        private val NUMBER_REGEX = """\d+""".toRegex()
        private val DATE_PATTERN = """\d+\s*(saat|dakika|gün|hafta|ay|yıl)\s*önce""".toRegex(RegexOption.IGNORE_CASE)
        private val AUTHOR_REGEX = """Yazar(?:/Çizer)?:\s*([^\n]+)""".toRegex(RegexOption.IGNORE_CASE)
        private val ARTIST_REGEX = """Çizer:\s*([^\n]+)""".toRegex(RegexOption.IGNORE_CASE)
    }
}

// API Response Data Classes
@Serializable
data class SearchApiResponse(
    val result: SearchResult? = null,
)

@Serializable
data class SearchResult(
    val data: SearchData? = null,
)

@Serializable
data class SearchData(
    val json: List<SeriesInfo>? = null,
)

@Serializable
data class SeriesInfo(
    val id: String = "",
    val name: String = "",
    val sef: String = "",
    val type: String = "",
    val status: String = "",
    val author: String = "",
    val thumb: String = "",
    val views: Int = 0,
    val href: String? = null,
    val summary: String = "",
)

// Chapter API Response Data Classes
@Serializable
data class ChapterApiResponse(
    val result: ChapterResult? = null,
)

@Serializable
data class ChapterResult(
    val data: ChapterDataWrapper? = null,
)

@Serializable
data class ChapterDataWrapper(
    val json: ChapterData? = null,
)

@Serializable
data class ChapterData(
    val chapters: List<ChapterInfo>? = null,
    val pagination: PaginationInfo? = null,
)

@Serializable
data class ChapterInfo(
    val chap_id: String = "",
    val name: String = "",
    val no: Double = 0.0,
    val href: String? = null,
    val date: String = "",
    val views: Int = 0,
)

@Serializable
data class PaginationInfo(
    val limit: Int = 20,
    val page: Int = 1,
    val total: Int = 0,
    val totalPages: Int = 1,
)
