package eu.kanade.tachiyomi.extension.tr.alucardscans

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class AlucardScans : HttpSource() {
    override val name = "Alucard Scans"
    override val baseUrl = "https://alucardscans.com"
    override val lang = "tr"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(2)
        .build()

    // ==============================
    // 1. POPÜLER (API - Görüntülenmeye Göre)
    // ==============================

    override fun popularMangaRequest(page: Int): Request {
        // Senin verdiğin parametrelerle "En Çok Okunanlar" (Tüm Zamanlar)
        val url = "$baseUrl/api/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "24")
            .addQueryParameter("sort", "views")
            .addQueryParameter("order", "desc")
            .addQueryParameter("timeRange", "all")
            .addQueryParameter("calculateTotalViews", "true")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response) = parseMangaList(response)

    // ==============================
    // 2. SON GÜNCELLEMELER (HTML - Anasayfa Widget)
    // ==============================

    override fun latestUpdatesRequest(page: Int): Request {
        // API sıralaması yanlış olduğu için doğrudan anasayfayı çekiyoruz.
        // Anasayfadaki "Son Güncellemeler" widget'ı en doğru sırayı verir.
        return GET(baseUrl, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body.string())
        val mangas = mutableListOf<SManga>()

        // "Son Güncellemeler" başlığını taşıyan bölümü buluyoruz
        val section = document.select("section[aria-labelledby='latest-updates-heading']")

        // İçindeki kartları (group) geziyoruz
        section.select("div.group").forEach { element ->
            val manga = SManga.create()

            // Linki ve Resmi bul
            val linkElement = element.selectFirst("a[href*='/manga/']")
            val imgElement = element.selectFirst("img")
            val titleElement = element.selectFirst("h3")

            if (linkElement != null && imgElement != null) {
                manga.url = linkElement.attr("href")
                manga.title = titleElement?.text() ?: ""
                manga.thumbnail_url = imgElement.absUrl("src")

                mangas.add(manga)
            }
        }

        // Anasayfa widget'ında sayfalama (sonsuz kaydırma) yoktur, o yüzden false dönüyoruz.
        return MangasPage(mangas, false)
    }

    // ==============================
    // 3. ARAMA (API - Search)
    // ==============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.startsWith(URL_SEARCH_PREFIX)) {
            val slug = query.substringAfter(URL_SEARCH_PREFIX)
            return searchRequest(page, slug)
        }
        return searchRequest(page, query)
    }

    private fun searchRequest(page: Int, query: String): Request {
        val url = "$baseUrl/api/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "24")
            .addQueryParameter("search", query)
            .addQueryParameter("sort", "views") // Aramada popüler olanlar üstte
            .addQueryParameter("order", "desc")
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = parseMangaList(response)

    // ==============================
    // 4. DETAYLAR (HTML - JSON-LD Parsing)
    // ==============================

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        // API listelemesinde açıklama (description) gelmediği için
        // Detayları doğrudan manga sayfasının kaynağından (HTML) çekiyoruz.
        return client.newCall(GET(baseUrl + manga.url, headers))
            .asObservableSuccess()
            .map { response ->
                val document = Jsoup.parse(response.body.string())

                manga.apply {
                    // Sayfa içindeki "structured-data" (JSON-LD) verisi en temiz veridir
                    val scriptContent = document.selectFirst("meta[name=structured-data]")?.attr("content")

                    if (scriptContent != null) {
                        try {
                            val jsonString = Parser.unescapeEntities(scriptContent, false)
                            val jsonObject = json.parseToJsonElement(jsonString).jsonObject

                            title = jsonObject["name"]?.jsonPrimitive?.content ?: title
                            description = jsonObject["description"]?.jsonPrimitive?.content
                            genre = jsonObject["genre"]?.jsonPrimitive?.content

                            val publisher = jsonObject["publisher"]?.jsonObject
                            author = publisher?.get("name")?.jsonPrimitive?.content ?: "Alucard Scans"
                            artist = author

                            status = when (jsonObject["status"]?.jsonPrimitive?.content) {
                                "Ongoing", "Current" -> SManga.ONGOING
                                "Completed" -> SManga.COMPLETED
                                else -> SManga.UNKNOWN
                            }
                        } catch (e: Exception) {
                            // JSON parse edilemezse fallback
                        }
                    }

                    // Eğer üstteki çalışmazsa manuel fallback
                    if (description.isNullOrEmpty()) {
                        description = document.select("meta[name=description]").attr("content")
                    }

                    initialized = true
                }
            }
    }

    override fun mangaDetailsParse(response: Response) = throw UnsupportedOperationException("HTML kullanılıyor")

    // ==============================
    // 5. BÖLÜMLER (API - Zincirleme İstek)
    // ==============================

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        val slug = manga.url.trim('/').substringAfterLast('/')

        // 1. ADIM: Slug ile Seriyi Arat ve ID'yi Bul
        return getSeriesDtoBySlug(slug).flatMap { seriesDto ->
            val seriesId = seriesDto.id
                ?: throw Exception("Seri ID'si API'den alınamadı: ${seriesDto.title}")

            // 2. ADIM: Bulunan ID ile Bölümleri Çek
            val chaptersUrl = "$baseUrl/api/series/$seriesId/chapters"
            client.newCall(GET(chaptersUrl, headers)).asObservableSuccess()
        }.map { response ->
            val chaptersDto = json.decodeFromString<List<ChapterDto>>(response.body.string())

            chaptersDto.map { dto ->
                SChapter.create().apply {
                    val finalSlug = dto.slug.ifEmpty { "$slug-bolum-${dto.number}" }
                    url = "/$finalSlug"

                    name = if (dto.title.isNullOrEmpty()) {
                        "Bölüm ${dto.number}"
                    } else {
                        "Bölüm ${dto.number}: ${dto.title}"
                    }

                    chapter_number = dto.number?.toFloatOrNull() ?: -1f
                    date_upload = parseIsoDate(dto.createdAt)
                }
            }
        }
    }

    override fun chapterListParse(response: Response) = throw UnsupportedOperationException("API kullanılıyor")

    // ==============================
    // 6. SAYFA RESİMLERİ
    // ==============================

    override fun pageListRequest(chapter: SChapter): Request {
        return GET("$baseUrl${chapter.url}", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()

        // Next.js JSON verisi içindeki resim URL'lerini yakalar
        val regex = """\\?"url\\?":\\?"(/uploads/[^"]+)""".toRegex()

        val pages = regex.findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .mapIndexed { index, relativeUrl ->
                val fullUrl = "$baseUrl${relativeUrl.replace("\\", "")}"
                Page(index, "", fullUrl)
            }
            .toList()

        if (pages.isNotEmpty()) return pages

        // Yedek Yöntem: HTML img etiketleri
        val doc = Jsoup.parse(html)
        return doc.select("img[src*='/uploads/']").mapIndexed { index, img ->
            Page(index, "", img.attr("abs:src"))
        }.distinctBy { it.imageUrl }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    // ==============================
    // YARDIMCI METODLAR
    // ==============================

    private fun parseMangaList(response: Response): MangasPage {
        val result = json.decodeFromString<ApiResponse>(response.body.string())
        val mangas = result.series.map { it.toSManga(baseUrl) }
        val limit = result.pagination.limit
        val hasNextPage = result.pagination.page * limit < result.pagination.total
        return MangasPage(mangas, hasNextPage)
    }

    // Bölümleri çekmek için ID bulmaya yarayan fonksiyon
    private fun getSeriesDtoBySlug(slug: String): Observable<SeriesDto> {
        val searchUrl = "$baseUrl/api/series".toHttpUrl().newBuilder()
            .addQueryParameter("search", slug)
            .addQueryParameter("limit", "100")
            .build()

        return client.newCall(GET(searchUrl, headers))
            .asObservableSuccess()
            .map { response ->
                val apiResponse = json.decodeFromString<ApiResponse>(response.body.string())
                apiResponse.series.find { it.slug == slug }
                    ?: throw Exception("Seri API'de bulunamadı (Slug: $slug)")
            }
    }

    private fun parseIsoDate(dateStr: String?): Long {
        if (dateStr == null) return 0L
        return try {
            isoDateFormat.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    companion object {
        const val URL_SEARCH_PREFIX = "slug:"
        private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}

// ==============================
// DTO MODELLERİ
// ==============================

@Serializable
data class ApiResponse(
    val series: List<SeriesDto>,
    val pagination: PaginationDto,
)

@Serializable
data class PaginationDto(
    val total: Int,
    val page: Int,
    val limit: Int,
)

@Serializable
data class SeriesDto(
    @SerialName("_id") val id: String? = null,
    val title: String,
    val slug: String,
    val coverImage: String,
) {
    fun toSManga(baseUrl: String) = SManga.create().apply {
        title = this@SeriesDto.title
        url = "/manga/$slug"
        thumbnail_url = if (coverImage.startsWith("http")) coverImage else "$baseUrl$coverImage"
    }
}

@Serializable
data class ChapterDto(
    @SerialName("_id") val id: String? = null,
    val title: String? = null,
    val number: String? = null,
    val slug: String,
    val createdAt: String? = null,
)
