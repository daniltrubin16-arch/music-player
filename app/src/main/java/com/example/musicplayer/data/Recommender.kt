package com.example.musicplayer.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Подбор новой музыки по библиотеке через открытый API Deezer (ключ не нужен).
 * Из приложения уходят только имена исполнителей. Deezer отдаёт 30-секундные превью.
 */
object Recommender {
    private const val API = "https://api.deezer.com"
    private const val UNKNOWN = "Неизвестный исполнитель"

    private data class Related(val id: Long, val name: String, val weight: Int)

    /** Возвращает до 40 треков-превью; пустой список — не удалось получить данные. */
    suspend fun recommend(library: List<Track>): List<Track> = withContext(Dispatchers.IO) {
        val counts = library.filter { it.artist != UNKNOWN && it.artist.isNotBlank() }
            .groupingBy { it.artist }
            .eachCount()
        if (counts.isEmpty()) return@withContext emptyList()

        // Какие песни у вас уже есть: название (без скобок и знаков) -> исполнители с таким названием.
        val owned: Map<String, List<String>> = library
            .groupBy({ titleKey(it.title) }, { norm(it.artist) })
        // Берём самых частых исполнителей, но каждый раз разную десятку — чтобы «Обновить» давало новое.
        val seeds = counts.entries.sortedByDescending { it.value }.take(20).shuffled().take(10)

        // 1. Для каждого исполнителя находим его в Deezer и берём похожих.
        val related: List<Related> = coroutineScope {
            seeds.map { seed ->
                async {
                    val id = findArtistId(seed.key) ?: return@async emptyList<Related>()
                    val data = getJson("$API/artist/$id/related?limit=12")?.optJSONArray("data")
                        ?: return@async emptyList<Related>()
                    (0 until data.length()).mapNotNull { i ->
                        val o = data.optJSONObject(i) ?: return@mapNotNull null
                        val rid = o.optLong("id")
                        val name = o.optString("name")
                        if (rid == 0L || name.isBlank()) null else Related(rid, name, seed.value)
                    }
                }
            }.awaitAll().flatten()
        }

        // 2. Чем чаще артист «похож» на ваших любимых, тем выше он.
        // Исполнителей из вашей библиотеки не исключаем: скрываем только песни, которые у вас уже есть.
        val picked = related
            .groupBy { it.id }
            .map { (id, list) -> Triple(id, list.first().name, list.sumOf { it.weight }) }
            .shuffled()
            .sortedByDescending { it.third }
            .take(12)
        if (picked.isEmpty()) return@withContext emptyList()

        // 3. Лучшие треки выбранных артистов.
        val tracks = coroutineScope {
            picked.map { artist ->
                async {
                    // Берём с запасом: часть песен может оказаться у вас, их отбросим.
                    val data = getJson("$API/artist/${artist.first}/top?limit=12")?.optJSONArray("data")
                        ?: return@async emptyList<Track>()
                    (0 until data.length())
                        .mapNotNull { i -> data.optJSONObject(i)?.let(::toTrack) }
                        .filterNot { isOwned(it, owned) }
                        .take(4)
                }
            }.awaitAll().flatten()
        }

        tracks.distinctBy { it.id }.shuffled().take(40)
    }

    private fun findArtistId(name: String): Long? {
        val q = URLEncoder.encode(name, "UTF-8")
        val data = getJson("$API/search/artist?q=$q&limit=5")?.optJSONArray("data") ?: return null
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            // Берём только точное совпадение имени, чтобы не получить «однофамильца».
            if (norm(o.optString("name")) == norm(name)) return o.optLong("id").takeIf { it != 0L }
        }
        return null
    }

    private fun toTrack(o: JSONObject): Track? {
        val preview = o.optString("preview")
        val id = o.optLong("id")
        if (preview.isBlank() || id == 0L) return null
        return Track(
            // Отрицательный id, чтобы не пересекаться с id локальных треков.
            id = -id,
            uri = Uri.parse(preview),
            title = o.optString("title", "Без названия"),
            artist = o.optJSONObject("artist")?.optString("name").orEmpty(),
            album = o.optJSONObject("album")?.optString("title").orEmpty(),
            durationMs = 30_000,
            webUrl = o.optString("link").ifBlank { null }
        )
    }

    private fun norm(s: String) = s.trim().lowercase()

    /** Название без скобок «(Remastered)», «[feat. …]», суффикса « - …» и без знаков препинания. */
    private fun titleKey(title: String): String = title.lowercase()
        .replace(Regex("[(\\[].*?[)\\]]"), " ")
        .substringBefore(" - ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    /** Песня уже у вас, если совпало название и исполнитель (допускаем «A» против «A feat. B»). */
    private fun isOwned(track: Track, owned: Map<String, List<String>>): Boolean {
        val artists = owned[titleKey(track.title)] ?: return false
        val a = norm(track.artist)
        return artists.any { it == a || it.contains(a) || a.contains(it) }
    }

    private fun getJson(url: String): JSONObject? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.requestMethod = "GET"
        if (c.responseCode != 200) {
            null
        } else {
            c.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        }
    } catch (e: Exception) {
        null
    }
}
