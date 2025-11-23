package eu.kanade.tachiyomi.extension.tr.alucardscans

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

/**
 * Bu aktivite, "https://alucardscans.com/series/seri-adi" gibi linklere tıklandığında
 * Mihon uygulamasının tetiklenmesini sağlar.
 *
 * Çalışma Mantığı:
 * 1. Android sistemi URL'yi yakalar ve bu aktiviteyi açar.
 * 2. Aktivite URL'deki "slug" (seri-adi) kısmını alır.
 * 3. Mihon'un arama sistemini "slug:seri-adi" şeklindeki özel bir komutla başlatır.
 * 4. AlucardScans.kt dosyası bu komutu görünce doğrudan o seriyi açar.
 */
class AlucardScansUrlActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Linkin yolunu parçalara ayır (örn: ["series", "sonsuzlugun-kadim-hukumdari"])
        val pathSegments = intent?.data?.pathSegments

        if (pathSegments != null && pathSegments.size >= 2) {
            // URL yapısı: alucardscans.com/series/slug veya /manga/slug
            val slug = pathSegments[1]

            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.SEARCH"
                // Arama sorgusu oluştur: slug:manga-adi
                putExtra("query", "${AlucardScans.URL_SEARCH_PREFIX}$slug")
                putExtra("filter", packageName)
            }

            try {
                startActivity(mainIntent)
            } catch (e: ActivityNotFoundException) {
                Log.e("AlucardUrlActivity", e.toString())
            }
        } else {
            Log.e("AlucardUrlActivity", "Link parse edilemedi: $intent")
        }

        finish()
        exitProcess(0)
    }
}
