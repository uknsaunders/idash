package me.hufman.androidautoidrive.carapp.dash

import android.util.Log
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

/**
 * i3D: where the car-chosen music comes from. All free and open, with no keys:
 *  - internet radio from Radio Browser, a community directory of stations (radio-browser.info)
 *  - podcasts from Apple's public podcast charts and search, then each show's own RSS feed
 *
 * Every list comes back as the same shape for the car page:
 *   {kind: station|podcast|episode, id, title, sub, art, url, feed, show}
 */
object DashAudioSources {
	private const val TAG = "i3DAudioSources"
	private const val AGENT = "i3D/1.0 (BMW i3 dashboard; github.com/uknsaunders/idash)"
	// Radio Browser asks apps to look its servers up (all.api.radio-browser.info lists them, reverse DNS names
	// them), in a random order, trying the next one if a request fails. These are known names as a fallback.
	private val RADIO_FALLBACK = listOf("de2.api.radio-browser.info", "fi1.api.radio-browser.info", "nl1.api.radio-browser.info")
	@Volatile private var radioServers: List<String>? = null
	private fun radioServerList(): List<String> {
		radioServers?.let { return it }
		val found = try {
			java.net.InetAddress.getAllByName("all.api.radio-browser.info")
				.mapNotNull { a -> a.canonicalHostName.takeIf { it.endsWith(".api.radio-browser.info") } }
				.distinct().shuffled()
		} catch (e: Exception) { emptyList() }
		val list = (found + RADIO_FALLBACK).distinct().map { "https://$it" }
		if (found.isNotEmpty()) radioServers = list
		return list
	}
	private const val MAX = 40

	class Answer(val items: JSONArray, val error: String? = null)

	/** One list for the car, by what was asked for */
	fun list(kind: String, arg: String): Answer = try {
		when (kind) {
			"near" -> near(arg)
			"uk" -> Answer(stations("countrycode=GB&order=clickcount&reverse=true&hidebroken=true&limit=60"))
			"genre" -> genre(arg)
			"radiosearch" -> Answer(stations("name=${enc(arg)}&order=clickcount&reverse=true&hidebroken=true&limit=$MAX"))
			"charts" -> Answer(charts())
			"podsearch" -> Answer(podcastSearch(arg))
			"episodes" -> Answer(episodes(JSONObject(arg)))
			else -> Answer(JSONArray(), "Unknown list $kind")
		}
	} catch (e: Exception) {
		Log.w(TAG, "Couldn't load $kind", e)
		Answer(JSONArray(), "Couldn't load that list: ${e.javaClass.simpleName} ${e.message ?: ""}".trim().take(110))
	}

	/* ---------- radio ---------- */

	private fun near(arg: String): Answer {
		val at = try { JSONObject(arg) } catch (e: Exception) { JSONObject() }
		val lat = at.optDouble("lat", Double.NaN)
		val lon = at.optDouble("lon", Double.NaN)
		val list = JSONArray()
		if (!lat.isNaN() && !lon.isNaN()) {
			val found = stations("geo_lat=$lat&geo_long=$lon&geo_distance=60000&order=clickcount&reverse=true&hidebroken=true&limit=$MAX")
			for (i in 0 until found.length()) list.put(found.get(i))
		}
		// few stations record where they are, so top up with the country's favourites
		if (list.length() < 8) {
			val seen = (0 until list.length()).map { list.getJSONObject(it).optString("id") }.toHashSet()
			val more = stations("countrycode=GB&order=clickcount&reverse=true&hidebroken=true&limit=$MAX")
			for (i in 0 until more.length()) {
				val s = more.getJSONObject(i)
				if (seen.add(s.optString("id"))) list.put(s)
			}
		}
		return Answer(list)
	}

	private fun genre(tag: String): Answer {
		val uk = stations("tag=${enc(tag.lowercase())}&countrycode=GB&order=clickcount&reverse=true&hidebroken=true&limit=$MAX")
		return Answer(if (uk.length() >= 6) uk else stations("tag=${enc(tag.lowercase())}&order=clickcount&reverse=true&hidebroken=true&limit=$MAX"))
	}

	private fun stations(query: String): JSONArray {
		val body = radioGet("/json/stations/search?$query")
		val raw = JSONArray(body)
		val out = JSONArray()
		val names = HashSet<String>()
		for (i in 0 until raw.length()) {
			val s = raw.getJSONObject(i)
			val url = s.optString("url_resolved").ifBlank { s.optString("url") }
			val name = s.optString("name").trim()
			if (url.isBlank() || name.isBlank() || !names.add(name.lowercase())) continue
			val tags = s.optString("tags").split(',').map { it.trim() }.filter { it.isNotBlank() }.take(2)
			out.put(JSONObject()
				.put("kind", "station")
				.put("id", s.optString("stationuuid"))
				.put("title", name)
				.put("sub", if (tags.isNotEmpty()) tags.joinToString(" · ") { it.replaceFirstChar { c -> c.uppercase() } } else s.optString("country"))
				.put("art", s.optString("favicon").takeIf { it.startsWith("https://") } ?: "")
				.put("url", url))
		}
		return out
	}

	/** Tells Radio Browser a station was played, which keeps its popularity order useful */
	fun countClick(stationId: String) {
		if (stationId.isBlank()) return
		try { radioGet("/json/url/${enc(stationId)}") } catch (e: Exception) { }
	}

	private fun radioGet(path: String): String {
		var last: Exception? = null
		for (server in radioServerList()) {
			try { return get(server + path) } catch (e: Exception) { last = e; Log.w(TAG, "$server didn't answer: $e") }
		}
		radioServers = null
		throw last ?: Exception("No radio directory answered")
	}

	/* ---------- podcasts ---------- */

	private fun charts(): JSONArray {
		val body = JSONObject(get("https://rss.applemarketingtools.com/api/v2/gb/podcasts/top/30/podcasts.json"))
		val results = body.optJSONObject("feed")?.optJSONArray("results") ?: JSONArray()
		val out = JSONArray()
		for (i in 0 until results.length()) {
			val p = results.getJSONObject(i)
			out.put(JSONObject()
				.put("kind", "podcast")
				.put("id", p.optString("id"))
				.put("title", p.optString("name"))
				.put("sub", p.optString("artistName"))
				.put("art", bigArt(p.optString("artworkUrl100")))
				.put("feed", ""))     // found when the show is opened
		}
		return out
	}

	private fun podcastSearch(term: String): JSONArray {
		val body = JSONObject(get("https://itunes.apple.com/search?media=podcast&entity=podcast&country=GB&limit=30&term=${enc(term)}"))
		val results = body.optJSONArray("results") ?: JSONArray()
		val out = JSONArray()
		for (i in 0 until results.length()) {
			val p = results.getJSONObject(i)
			val feed = p.optString("feedUrl")
			if (feed.isBlank()) continue
			out.put(JSONObject()
				.put("kind", "podcast")
				.put("id", p.optString("collectionId"))
				.put("title", p.optString("collectionName"))
				.put("sub", p.optString("artistName"))
				.put("art", p.optString("artworkUrl600").ifBlank { bigArt(p.optString("artworkUrl100")) })
				.put("feed", feed))
		}
		return out
	}

	/** The latest episodes of one show, newest first */
	private fun episodes(show: JSONObject): JSONArray {
		var feed = show.optString("feed")
		if (feed.isBlank()) {
			val lookup = JSONObject(get("https://itunes.apple.com/lookup?entity=podcast&id=${enc(show.optString("id"))}"))
			feed = lookup.optJSONArray("results")?.optJSONObject(0)?.optString("feedUrl") ?: ""
		}
		if (feed.isBlank()) throw Exception("This show has no public feed")
		val out = JSONArray()
		val parser = Xml.newPullParser()
		parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
		parser.setInput(get(feed).reader())
		var showArt = show.optString("art")
		var inItem = false
		var title = ""; var url = ""; var date = ""; var duration = ""; var art = ""
		var event = parser.eventType
		while (event != XmlPullParser.END_DOCUMENT && out.length() < MAX) {
			val name = parser.name
			if (event == XmlPullParser.START_TAG) {
				when {
					name == "item" -> { inItem = true; title = ""; url = ""; date = ""; duration = ""; art = "" }
					name == "itunes:image" && !inItem -> parser.getAttributeValue(null, "href")?.let { if (showArt.isBlank()) showArt = it }
					inItem && name == "title" -> title = parser.nextText().trim()
					inItem && name == "enclosure" -> url = parser.getAttributeValue(null, "url") ?: ""
					inItem && name == "pubDate" -> date = parser.nextText().trim()
					inItem && name == "itunes:duration" -> duration = parser.nextText().trim()
					inItem && name == "itunes:image" -> art = parser.getAttributeValue(null, "href") ?: ""
				}
			} else if (event == XmlPullParser.END_TAG && name == "item") {
				inItem = false
				if (url.isNotBlank()) {
					out.put(JSONObject()
						.put("kind", "episode")
						.put("id", url)
						.put("title", title.ifBlank { "Episode" })
						.put("sub", listOf(shortDate(date), length(duration)).filter { it.isNotBlank() }.joinToString(" · "))
						.put("art", art.takeIf { it.startsWith("http") } ?: showArt)
						.put("url", url)
						.put("show", show.optString("title"))
						.put("feed", feed))
				}
			}
			event = parser.next()
		}
		return out
	}

	private fun bigArt(url: String) = url.replace("100x100", "400x400")

	private fun shortDate(rss: String): String = try {
		val d = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH).parse(rss)
		if (d != null) SimpleDateFormat("d MMM", Locale.UK).format(d) else ""
	} catch (e: Exception) { "" }

	/** itunes:duration comes as seconds, mm:ss or hh:mm:ss */
	private fun length(raw: String): String {
		if (raw.isBlank()) return ""
		val parts = raw.split(':').mapNotNull { it.trim().toIntOrNull() }
		val seconds = when (parts.size) {
			1 -> parts[0]
			2 -> parts[0] * 60 + parts[1]
			3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
			else -> return ""
		}
		val minutes = (seconds + 30) / 60
		return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
	}

	/* ---------- the web ---------- */

	private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

	private fun get(address: String): String {
		var url = URL(address)
		// follow redirects by hand, including http -> https, which HttpURLConnection won't do itself
		repeat(5) {
			val connection = url.openConnection() as HttpURLConnection
			connection.connectTimeout = 8000
			connection.readTimeout = 10000
			connection.instanceFollowRedirects = false
			connection.setRequestProperty("User-Agent", AGENT)
			val code = connection.responseCode
			if (code in 300..399) {
				val next = connection.getHeaderField("Location") ?: throw Exception("Redirect without a place")
				connection.disconnect()
				url = URL(url, next)
				return@repeat
			}
			if (code !in 200..299) { connection.disconnect(); throw Exception("HTTP $code from ${url.host}") }
			return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
		}
		throw Exception("Too many redirects")
	}
}
