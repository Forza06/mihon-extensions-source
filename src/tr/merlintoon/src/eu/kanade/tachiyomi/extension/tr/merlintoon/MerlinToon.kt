package eu.kanade.tachiyomi.extension.tr.merlintoon

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MerlinToon : ParsedHttpSource() {

    override val name = "MerlinToon"
    override val baseUrl = "https://merlintoon.com"
    override val lang = "tr"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    // Resim boyutlarını (örn: -450x600, -193x278 vb.) silmek için dinamik Regex deseni
    // Bu desen "-SAYIxSAYI" formatındaki tüm uzantıları yakalar.
    private val imgDimensionRegex = Regex("-\\d+x\\d+")

    // ==============================
    // 1. POPÜLER MANGALAR (JSON API)
    // ==============================
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/wp-json/initmanga/v1/top-ranking?range=all_time", headers)
    }

    override fun popularMangaSelector() = throw UnsupportedOperationException("JSON kullanılıyor")
    override fun popularMangaFromElement(element: Element) = throw UnsupportedOperationException("JSON kullanılıyor")
    override fun popularMangaNextPageSelector() = null

    override fun popularMangaParse(response: Response): MangasPage {
        val jsonString = response.body.string()
        val result = json.decodeFromString<TopRankingResponse>(jsonString)

        val mangas = result.posts.map { post ->
            val doc = Jsoup.parseBodyFragment(post.html)

            SManga.create().apply {
                val titleElement = doc.selectFirst("h3.uk-h5 a")
                val imgElement = doc.selectFirst("img")

                title = titleElement?.text()?.trim() ?: "Bilinmeyen"
                setUrlWithoutDomain(titleElement?.attr("href") ?: "")

                // Kapak görselinden boyut ekini temizle
                thumbnail_url = processCoverUrl(imgElement?.attr("src"))
            }
        }

        return MangasPage(mangas, false)
    }

    // ==============================
    // 2. SON GÜNCELLEMELER
    // ==============================
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/son-guncellenenler/page/$page/", headers)
    }

    override fun latestUpdatesSelector() = Selectors.CARD
    override fun latestUpdatesNextPageSelector() = Selectors.NEXT_PAGE

    override fun latestUpdatesFromElement(element: Element) = SManga.create().apply {
        val titleElement = element.selectFirst("h3.uk-h5 a")
        val imgElement = element.selectFirst("div.uk-overflow-hidden img")

        title = titleElement?.text()?.trim() ?: "Bilinmeyen İsim"
        setUrlWithoutDomain(titleElement?.attr("href") ?: "")

        val rawUrl = imgElement?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }

        // Kapak görselinden boyut ekini temizle
        thumbnail_url = processCoverUrl(rawUrl)
    }

    // ==============================
    // 3. ARAMA (SEARCH - JSON API)
    // ==============================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/wp-json/initlise/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", query)
            .build()

        return GET(url, headers)
    }

    override fun searchMangaSelector() = throw UnsupportedOperationException("JSON kullanılıyor")
    override fun searchMangaFromElement(element: Element) = throw UnsupportedOperationException("JSON kullanılıyor")
    override fun searchMangaNextPageSelector() = null

    override fun searchMangaParse(response: Response): MangasPage {
        val jsonString = response.body.string()
        val results = json.decodeFromString<List<SearchDto>>(jsonString)

        val mangas = results.map { dto ->
            SManga.create().apply {
                title = Jsoup.parse(dto.title).text()
                url = dto.url.replace(baseUrl, "")

                // Kapak görselinden boyut ekini temizle
                thumbnail_url = processCoverUrl(dto.thumb)
            }
        }

        return MangasPage(mangas, false)
    }

    // ==============================
    // 4. MANGA DETAYLARI
    // ==============================
    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        val infoSection = document.selectFirst("div.manga-info-details")

        title = document.selectFirst("#manga-title")?.text()?.trim() ?: "Bilinmeyen"
        author = infoSection?.select("a[href*='/author/']")?.text()?.trim()
        artist = author
        description = document.select("#manga-description p").joinToString("\n\n") { it.text() }
        genre = document.select("#genre-tags a").joinToString(", ") { it.text() }

        val statusText = document.select("#manga-status").text()
        status = parseStatus(statusText)

        // Detay sayfasındaki kapak görselini al
        val imgElement = document.selectFirst("div.story-cover-wrap img")
        val rawUrl = imgElement?.let { img ->
            img.attr("data-src").ifEmpty {
                img.attr("data-lazy-src").ifEmpty {
                    img.attr("src")
                }
            }
        }

        // Kapak görselinden boyut ekini temizle (HQ Kapak)
        thumbnail_url = processCoverUrl(rawUrl)
    }

    // ==============================
    // 5. BÖLÜM LİSTESİ
    // ==============================
    override fun chapterListSelector() = "#chapter-list .chapter-item"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        val linkElement = element.selectFirst("a.uk-link-toggle")!!
        setUrlWithoutDomain(linkElement.attr("href"))

        val rawName = element.select("h3").text().trim()

        var cleanName = rawName
        if (rawName.contains("-") || rawName.contains("–")) {
            cleanName = rawName.substringAfterLast("-").substringAfterLast("–").trim()
        }

        name = if (cleanName.all { it.isDigit() }) "Bölüm $cleanName" else cleanName

        if (element.selectFirst("span[uk-icon*='lock']") != null) {
            name = "🔒 $name"
        }

        val dateText = element.select("time").text().trim()
        val dateTextFallback = element.select(".uk-article-meta").text()

        date_upload = parseRelativeDate(dateText.ifEmpty { dateTextFallback })
    }

    // ==============================
    // 6. SAYFA LİSTESİ
    // ==============================
    override fun pageListParse(document: Document): List<Page> {
        if (document.selectFirst("h3.uk-card-title:contains(Kilitli Bölüm)") != null) {
            throw Exception("Bu bölüm kilitli. Okumak için WebView üzerinden giriş yapmalısınız.")
        }

        val pages = mutableListOf<Page>()
        val seenUrls = mutableSetOf<String>() // Aynı resim URL'lerinin tekrar eklenmesini önlemek için

        document.select("#chapter-content img").forEach { img ->
            val url = img.attr("data-original-src").ifEmpty {
                img.attr("data-src").ifEmpty {
                    img.attr("data-lazy-src").ifEmpty {
                        img.attr("src")
                    }
                }
            }

            // Sadece HTTP linklerini al VE daha önce listeye eklenmemişse ekle
            if (url.startsWith("http") && seenUrls.add(url)) {
                pages.add(Page(pages.size, "", url))
            }
        }
        return pages
    }

    override fun imageUrlParse(document: Document) = ""

    // ==============================
    // YARDIMCI FONKSİYONLAR
    // ==============================

    /**
     * URL'deki boyut bilgisini (örn: -450x600, -300x300 vb.) silerek orijinal resmi döndürür.
     * Girdi: .../Resim-450x600.webp
     * Çıktı: .../Resim.webp
     */
    private fun processCoverUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        return url.replace(imgDimensionRegex, "")
    }

    private fun parseStatus(status: String) = when {
        status.contains("Devam", ignoreCase = true) -> SManga.ONGOING
        status.contains("Güncel", ignoreCase = true) -> SManga.ONGOING
        status.contains("Tamam", ignoreCase = true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    private fun parseRelativeDate(date: String): Long {
        val trimmedDate = date.lowercase(Locale("tr"))

        if (trimmedDate.contains("önce")) {
            val number = Regex("""(\d+)""").find(trimmedDate)?.value?.toIntOrNull() ?: return 0L
            val cal = Calendar.getInstance()

            return when {
                "saniye" in trimmedDate -> cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
                "dakika" in trimmedDate -> cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
                "saat" in trimmedDate -> cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
                "gün" in trimmedDate -> cal.apply { add(Calendar.DAY_OF_YEAR, -number) }.timeInMillis
                "hafta" in trimmedDate -> cal.apply { add(Calendar.WEEK_OF_YEAR, -number) }.timeInMillis
                "ay" in trimmedDate -> cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
                "yıl" in trimmedDate -> cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
                else -> 0L
            }
        }

        return try {
            val dateFormat = SimpleDateFormat("dd MMMM yyyy", Locale("tr"))
            dateFormat.parse(date)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    companion object {
        object Selectors {
            const val CARD = "div.uk-panel"
            const val NEXT_PAGE = "ul.uk-pagination li:last-child:not(.uk-disabled) a"
        }
    }
}

// ==============================
// DTO MODELLERİ
// ==============================

@Serializable
data class SearchDto(
    val id: Int,
    val title: String,
    val url: String,
    val thumb: String,
)

@Serializable
data class TopRankingResponse(
    val success: Boolean,
    val posts: List<TopRankingPost>,
)

@Serializable
data class TopRankingPost(
    val id: Int,
    val html: String,
)
