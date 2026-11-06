package com.kairos.tv

import org.json.JSONArray
import org.json.JSONObject

/** info extra di film e serie dal pannello (copertina orizzontale, generi, cast, età), tenute in memoria */
object Meta {
    class Info(val backdrop: String, val genre: String, val cast: String, val rating: String, val age: String, val dur: String, val plot: String, val year: String)

    private val cache = object : LinkedHashMap<String, Info>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Info>?) = size > 300
    }

    private fun key(c: Db.Card) = c.table + ":" + c.id

    fun cached(c: Db.Card): Info? = synchronized(cache) { cache[key(c)] }

    fun get(c: Db.Card, cb: (Info?) -> Unit) {
        cached(c)?.let { cb(it); return }
        net {
            val info = try { fetch(c) } catch (_: Exception) { null }
            if (info != null) synchronized(cache) { cache[key(c)] = info }
            ui { cb(info) }
        }
    }

    private fun fetch(c: Db.Card): Info? {
        val r = Db.one(c.table, c.id) ?: return null
        val p = K.pl(r.s("pl")) ?: return null
        if (!p.isXtream || r.s("sid").isEmpty()) return null
        val url = (if (c.table == "vod") p.api("get_vod_info", "&vod_id=" + r.s("sid")) else p.api("get_series_info", "&series_id=" + r.s("sid"))) ?: return null
        val o = JSONObject(Http.text(url, connectMs = 8_000, readMs = 15_000).text).optJSONObject("info") ?: return null
        val cast = o.optString("cast").ifEmpty { o.optString("actors") }
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(4).joinToString(", ")
        val genre = o.optString("genre").split(',', '/').map { it.trim() }.filter { it.isNotEmpty() }.take(3).joinToString(", ")
        var age = o.optString("age").ifEmpty { o.optString("mpaa_rating") }.ifEmpty { o.optString("certification") }.trim()
        if (age.toIntOrNull() != null) age += "+"
        return Info(
            first(o.opt("backdrop_path")),
            genre, cast,
            o.optString("rating"),
            age.take(6),
            dur(o),
            o.optString("plot").ifEmpty { o.optString("description") },
            o.optString("releasedate").ifEmpty { o.optString("releaseDate") }.take(4),
        )
    }

    private fun first(v: Any?): String = when (v) {
        is JSONArray -> if (v.length() > 0) v.optString(0) else ""
        is String -> v
        else -> ""
    }

    private fun dur(o: JSONObject): String {
        val secs = o.optString("duration_secs").toLongOrNull() ?: 0
        if (secs > 0) return Ui.minutes(secs * 1000)
        val d = o.optString("duration")
        return if (d.length >= 5 && d[2] == ':') { val h = d.substring(0, 2).toIntOrNull() ?: 0; val m = d.substring(3, 5).toIntOrNull() ?: 0; Ui.minutes((h * 60L + m) * 60_000) } else ""
    }
}
