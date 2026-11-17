package me.hufman.androidautoidrive.carapp.dash

import android.util.Log
import me.hufman.androidautoidrive.ConnectionLog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * i3D: OpenStreetMap lookups (chargers, public toilets, speed limits) done on the phone side.
 * From the car screen these could hang with no answer, so here each server gets a time limit,
 * and if the main Overpass server is busy or broken, two community mirrors are tried.
 */
object DashOverpass {
	private const val TAG = "i3DOverpass"
	private const val AGENT = "i3D/1.0 (BMW i3 dashboard; github.com/uknsaunders/idash)"
	private val SERVERS = listOf(
		"https://overpass-api.de/api/interpreter",
		"https://overpass.private.coffee/api/interpreter",
		"https://overpass.kumi.systems/api/interpreter")

	/** The answer as JSON text, or null and what went wrong with each server */
	fun query(query: String, what: String): Pair<String?, String?> {
		val problems = ArrayList<String>()
		for (server in SERVERS) {
			val host = URL(server).host
			try {
				val connection = URL(server).openConnection() as HttpURLConnection
				connection.connectTimeout = 8000
				connection.readTimeout = 25000
				connection.requestMethod = "POST"
				connection.doOutput = true
				connection.setRequestProperty("User-Agent", AGENT)
				connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
				connection.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
				val code = connection.responseCode
				val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
					?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
				connection.disconnect()
				if (code !in 200..299) { problems.add("$host HTTP $code"); continue }
				val json = JSONObject(body)
				val count = json.optJSONArray("elements")?.length() ?: 0
				if (what != "speed limits") ConnectionLog.event("OpenStreetMap: $count $what from $host")
				return Pair(json.toString(), null)
			} catch (e: Exception) {
				Log.w(TAG, "$host didn't answer", e)
				problems.add("$host: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
			}
		}
		val message = problems.joinToString("; ")
		ConnectionLog.problem("OpenStreetMap lookup for $what failed: $message".take(300))
		return Pair(null, message)
	}
}
