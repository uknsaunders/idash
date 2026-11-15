package me.hufman.androidautoidrive.carapp.dash

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.MediaSession
import org.json.JSONObject

/**
 * i3D's own player, for the sources that can be chosen in the car: internet radio and podcasts.
 *
 * It plays on the phone, so the sound reaches the car the same way as any other music app (Bluetooth).
 * It publishes a normal media session, so the dash's now-playing panel and the car's own media
 * controls see it like any other app. Taking audio focus pauses whatever else was playing.
 * Podcast positions are remembered, so an episode carries on where it was left.
 */
class DashAudio(val context: Context) {
	companion object {
		const val TAG = "i3DAudio"
		private const val PREFS = "i3dash_audio"
		private const val SAVE_EVERY = 15000L
	}

	private val main = Handler(Looper.getMainLooper())
	private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
	private var player: ExoPlayer? = null
	private var session: MediaSession? = null

	/** What is loaded: the item the car page chose, as it sent it */
	@Volatile var current: JSONObject? = null
		private set
	/** For radio, the song the station says it is playing now ("Artist - Title"), if it says */
	@Volatile var nowLine: String? = null
		private set
	@Volatile var isPlaying = false
		private set

	/** Told about problems the driver should hear about, such as a station that won't play */
	var onProblem: (String) -> Unit = {}

	private val saver = object: Runnable {
		override fun run() {
			savePosition()
			main.postDelayed(this, SAVE_EVERY)
		}
	}

	private fun ensurePlayer(): ExoPlayer {
		player?.let { return it }
		val attrs = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
		val p = ExoPlayer.Builder(context)
			.setAudioAttributes(attrs, true)        // take audio focus: pauses Spotify and the rest
			.setHandleAudioBecomingNoisy(true)
			.build()
		p.addListener(object: Player.Listener {
			override fun onIsPlayingChanged(playing: Boolean) {
				isPlaying = playing
				if (!playing) savePosition()
			}
			override fun onMetadata(metadata: Metadata) {
				for (i in 0 until metadata.length()) {
					val entry = metadata.get(i)
					if (entry is IcyInfo) {
						val title = entry.title?.trim()
						nowLine = if (title.isNullOrBlank() || title == "-") null else title
					}
				}
			}
			override fun onPlaybackStateChanged(state: Int) {
				if (state == Player.STATE_ENDED) {
					current?.optString("url")?.let { prefs.edit().remove("pos:$it").apply() }
				}
			}
			override fun onPlayerError(error: PlaybackException) {
				Log.w(TAG, "Playback problem", error)
				val name = current?.optString("title") ?: "That"
				onProblem("$name won't play right now")
			}
		})
		player = p
		session = try {
			MediaSession.Builder(context, p).setId("i3d-audio").build()
		} catch (e: Exception) {
			Log.w(TAG, "Couldn't publish a media session", e)
			null
		}
		main.post(saver)
		return p
	}

	/** Plays a station or an episode from the car page: {kind, id, title, sub, art, url, show} */
	fun play(item: JSONObject) {
		main.post {
			try {
				val url = item.optString("url")
				if (url.isBlank()) { onProblem("Nothing to play there"); return@post }
				savePosition()
				val p = ensurePlayer()
				val live = item.optString("kind") == "station"
				val meta = MediaMetadata.Builder()
					.setTitle(item.optString("title"))
					.setArtist(if (live) item.optString("sub") else item.optString("show", item.optString("sub")))
					.setStation(if (live) item.optString("title") else null)
					.apply {
						val art = item.optString("art")
						if (art.startsWith("http")) setArtworkUri(Uri.parse(art))
					}
					.build()
				val builder = MediaItem.Builder().setUri(url).setMediaId(item.optString("id", url)).setMediaMetadata(meta)
				if (url.contains(".m3u8", ignoreCase = true)) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
				current = item
				nowLine = null
				val resumeAt = if (live) 0L else prefs.getLong("pos:$url", 0L)
				p.setMediaItem(builder.build(), resumeAt)
				p.prepare()
				p.play()
			} catch (e: Exception) {
				Log.w(TAG, "Couldn't start playing", e)
				onProblem("Couldn't start that")
			}
		}
	}

	/** toggle, next, prev, fwd or back, as sent by the car's knob */
	fun command(command: String) {
		main.post {
			val p = player ?: return@post
			when (command) {
				"toggle" -> if (p.isPlaying) p.pause() else p.play()
				"play" -> p.play()
				"pause" -> p.pause()
				"fwd" -> if (p.isCurrentMediaItemSeekable) p.seekTo(p.currentPosition + 30000L)
				"back" -> if (p.isCurrentMediaItemSeekable) p.seekTo(maxOf(0L, p.currentPosition - 15000L))
				"stop" -> { savePosition(); p.stop(); p.clearMediaItems(); current = null; nowLine = null }
			}
		}
	}

	/** Called when the driver picks a phone app instead, so the two don't play over each other */
	fun pause() = command("pause")

	/** Position and length in ms, for the progress bar (0 length for live radio) */
	fun progress(): Pair<Long, Long> {
		val p = player ?: return Pair(0L, 0L)
		return try {
			val d = p.duration
			Pair(p.currentPosition, if (d == C.TIME_UNSET || d < 0) 0L else d)
		} catch (e: Exception) { Pair(0L, 0L) }
	}

	private fun savePosition() {
		val p = player ?: return
		val item = current ?: return
		if (item.optString("kind") != "episode") return
		val url = item.optString("url")
		try {
			val at = p.currentPosition
			val length = p.duration
			if (at > 10000L && (length == C.TIME_UNSET || at < length - 30000L)) {
				prefs.edit().putLong("pos:$url", at).apply()
			}
		} catch (e: Exception) {
			// player is between items
		}
	}

	/** How far into an episode the driver has listened, in ms, for the "continue" tiles */
	fun savedPosition(url: String): Long = prefs.getLong("pos:$url", 0L)

	fun release() {
		main.post {
			main.removeCallbacks(saver)
			savePosition()
			try { session?.release() } catch (e: Exception) { }
			try { player?.release() } catch (e: Exception) { }
			session = null
			player = null
			current = null
			isPlaying = false
		}
	}
}
