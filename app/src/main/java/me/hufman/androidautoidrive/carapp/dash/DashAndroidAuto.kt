package me.hufman.androidautoidrive.carapp.dash

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import android.view.Surface
import me.hufman.androidautoidrive.ConnectionLog
import me.hufman.androidautoidrive.androidauto.Credentials
import me.hufman.androidautoidrive.androidauto.HeadUnit
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Android Auto on the iDrive screen.
 *
 * i3D plays the part of an Android Auto head unit, on the phone itself: it connects to Android Auto's
 * head unit server (Android Auto's developer setting, on port 5277), decodes the picture onto a surface
 * on i3D's hidden display (which already goes to the car as pictures), plays the sound on the phone
 * (so it reaches the car over Bluetooth like any other app), and passes the iDrive knob to Android Auto
 * as a rotary controller.
 */
class DashAndroidAuto(private val context: Context, private val screenWidth: Int, private val screenHeight: Int) {
	companion object {
		const val TAG = "i3DAndroidAuto"
		const val PORT = 5277
		const val CERT_FILE = "android-auto-headunit.pem"
		/** Android Auto lays itself out for this screen density */
		const val DPI = 160
		const val RETRY_MS = 4000L

		fun certificateFile(context: Context) = File(context.filesDir, CERT_FILE)
		fun hasCertificate(context: Context) = certificateFile(context).let { it.exists() && it.length() > 100 }

		/** Checks a certificate before it is saved. Returns null if it is usable, or what is wrong */
		fun checkCertificate(pem: String): String? = try {
			Credentials.sslContext(pem)
			null
		} catch (e: Exception) {
			e.message ?: e.toString()
		}
	}

	val video: HeadUnit.VideoSetup = HeadUnit.VideoSetup.forScreen(screenWidth, screenHeight, DPI)

	/** Short messages for the car screen: what it's doing or what went wrong */
	var onStatus: (String) -> Unit = {}
	/** Called when a new picture decoder shows its first frame */
	var onPicture: () -> Unit = {}

	@Volatile private var night = false
	@Volatile private var moving = false

	@Volatile private var wanted = false
	@Volatile private var headUnit: HeadUnit? = null
	@Volatile private var socket: Socket? = null
	private var thread: Thread? = null
	@Volatile var lastStatus: String = "Not started"
		private set

	val isConnected: Boolean get() = headUnit != null

	/** Starts connecting, and keeps trying until stop() */
	@Synchronized
	fun start() {
		if (wanted) return
		wanted = true
		thread = Thread({ connectLoop() }, "i3D Android Auto").also { it.start() }
	}

	@Synchronized
	fun stop() {
		wanted = false
		headUnit?.stop()
		try { socket?.close() } catch (e: Exception) {}
		thread?.interrupt()
		thread = null
		stopMicrophone()
		synchronized(decoderLock) { releaseDecoder() }
		audio.values.forEach { it.release() }
		audio.clear()
	}

	private fun status(text: String) {
		lastStatus = text
		Log.i(TAG, text)
		ConnectionLog.event("Android Auto: $text")
		onStatus(text)
	}

	private fun connectLoop() {
		while (wanted) {
			try {
				val file = certificateFile(context)
				if (!hasCertificate(context)) {
					status("No head unit certificate yet. Add one in the i3D phone app")
					return
				}
				val ssl = Credentials.sslContext(file.readText())
				status("Connecting to Android Auto on the phone")
				val s = Socket()
				s.tcpNoDelay = true
				s.connect(InetSocketAddress("127.0.0.1", PORT), 3000)
				socket = s
				val hu = HeadUnit(s.getInputStream(), s.getOutputStream(), ssl, video, listener)
				hu.setNight(night)      // only noted: sent once Android Auto asks for the sensors
				hu.setMoving(moving)
				headUnit = hu
				hu.run()
				status("Android Auto closed the link")
			} catch (e: java.net.ConnectException) {
				status("Android Auto isn't listening. Open Android Auto on the phone and choose Start head unit server")
			} catch (e: java.net.SocketTimeoutException) {
				status("Android Auto didn't answer")
			} catch (e: Exception) {
				if (wanted) status("Link to Android Auto dropped: ${e.message ?: e.javaClass.simpleName}")
			} finally {
				headUnit = null
				try { socket?.close() } catch (e: Exception) {}
				socket = null
				stopMicrophone()
				synchronized(decoderLock) { releaseDecoder(); codecConfig = null }
			}
			if (!wanted) return
			try { Thread.sleep(RETRY_MS) } catch (e: InterruptedException) { return }
		}
	}

	/* ---------- from the car ---------- */

	private val sender = Executors.newSingleThreadExecutor()
	private fun send(what: String, action: (HeadUnit) -> Unit) {
		val hu = headUnit ?: return
		sender.execute {
			try { action(hu) } catch (e: Exception) { Log.w(TAG, "Couldn't send $what to Android Auto", e) }
		}
	}

	fun rotate(steps: Int) = send("a knob turn") { it.rotate(steps) }
	fun select() = send("a press") { it.key(HeadUnit.KEY_DPAD_CENTER) }
	fun back() = send("back") { it.key(HeadUnit.KEY_BACK) }
	fun setNight(night: Boolean) {
		this.night = night
		send("night mode") { it.setNight(night) }
	}
	fun setMoving(moving: Boolean) {
		if (this.moving == moving && headUnit == null) return
		this.moving = moving
		send("the driving status") { it.setMoving(moving) }
	}

	/* ---------- the picture ---------- */

	private val decoderLock = Any()
	private var surface: Surface? = null
	private var decoder: MediaCodec? = null
	private var shownFirstFrame = false
	/** The stream's set-up data (SPS and PPS), kept so a new decoder can start mid-stream */
	private var codecConfig: ByteArray? = null

	/** Where the picture is drawn. Null while there's nowhere to show it */
	fun setSurface(surface: Surface?) {
		synchronized(decoderLock) {
			if (this.surface === surface) return
			releaseDecoder()
			this.surface = surface
		}
		// a fresh decoder needs a fresh key frame: ask Android Auto to start the picture again
		if (surface != null) send("a picture request") { it.refocusVideo() }
	}

	private fun releaseDecoder() {
		try { decoder?.stop() } catch (e: Exception) {}
		try { decoder?.release() } catch (e: Exception) {}
		decoder = null
	}

	private fun ensureDecoder(): MediaCodec? {
		decoder?.let { return it }
		val surface = surface ?: return null
		return try {
			val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, video.width, video.height)
			val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
			codec.configure(format, surface, null, 0)
			codec.start()
			decoder = codec
			shownFirstFrame = false
			codecConfig?.let { queue(codec, it, 0, it.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG) }
			codec
		} catch (e: Exception) {
			Log.w(TAG, "Couldn't start the video decoder", e)
			status("The phone couldn't decode Android Auto's picture: ${e.message}")
			null
		}
	}

	private fun queue(codec: MediaCodec, data: ByteArray, offset: Int, length: Int, timeUs: Long, flags: Int) {
		val index = codec.dequeueInputBuffer(20_000)
		if (index < 0) return       // the decoder is behind: drop this piece, the next key frame recovers
		val buffer = codec.getInputBuffer(index) ?: return
		buffer.clear()
		buffer.put(data, offset, length)
		codec.queueInputBuffer(index, 0, length, timeUs, flags)
	}

	private val info = MediaCodec.BufferInfo()
	private fun drain(codec: MediaCodec) {
		while (true) {
			val index = codec.dequeueOutputBuffer(info, 0)
			if (index >= 0) {
				codec.releaseOutputBuffer(index, true)
				if (!shownFirstFrame) {
					shownFirstFrame = true
					onPicture()
				}
			} else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
		}
	}

	/* ---------- the sound ---------- */

	private class Player(rate: Int, channels: Int, usage: Int) {
		private val track: AudioTrack
		private val writer = Executors.newSingleThreadExecutor()
		init {
			val channelMask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
			val min = AudioTrack.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
			track = AudioTrack.Builder()
				.setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(
					if (usage == AudioAttributes.USAGE_MEDIA) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH).build())
				.setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(channelMask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
				.setBufferSizeInBytes(maxOf(min * 4, 16384))
				.setTransferMode(AudioTrack.MODE_STREAM)
				.build()
		}
		fun write(data: ByteArray, offset: Int, length: Int) {
			val copy = data.copyOfRange(offset, offset + length)
			writer.execute {
				try {
					if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
					track.write(copy, 0, copy.size)
				} catch (e: Exception) { Log.w(TAG, "Couldn't play Android Auto's sound", e) }
			}
		}
		fun pause() {
			writer.execute { try { track.pause(); track.flush() } catch (e: Exception) {} }
		}
		fun release() {
			writer.execute { try { track.stop() } catch (e: Exception) {}; track.release() }
			writer.shutdown()
		}
	}

	private val audio = java.util.concurrent.ConcurrentHashMap<Int, Player>()
	private fun player(channel: Int): Player = audio.getOrPut(channel) {
		when (channel) {
			HeadUnit.CH_MEDIA_AUDIO -> Player(48000, 2, AudioAttributes.USAGE_MEDIA)
			HeadUnit.CH_SPEECH_AUDIO -> Player(16000, 1, AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
			else -> Player(16000, 1, AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
		}
	}

	/* ---------- the microphone, for the Assistant and calls ---------- */

	@Volatile private var micThread: Thread? = null

	@SuppressLint("MissingPermission")
	private fun startMicrophone() {
		if (micThread != null) return
		if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
			status("Android Auto wants the microphone. Allow i3D the microphone in the phone app")
			return
		}
		val t = Thread({
			val rate = 16000
			val size = maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 4096)
			val recorder = try {
				AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
			} catch (e: Exception) { Log.w(TAG, "No microphone", e); null }
			if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
				recorder?.release()
				return@Thread
			}
			val buffer = ByteArray(2048)
			try {
				recorder.startRecording()
				while (micThread === Thread.currentThread()) {
					val n = recorder.read(buffer, 0, buffer.size)
					if (n > 0) headUnit?.sendMicrophone(buffer, n)
				}
			} catch (e: Exception) {
				Log.w(TAG, "Microphone stopped", e)
			} finally {
				try { recorder.stop() } catch (e: Exception) {}
				recorder.release()
			}
		}, "i3D Android Auto microphone")
		micThread = t
		t.start()
	}

	private fun stopMicrophone() {
		micThread = null
	}

	/* ---------- what Android Auto sends ---------- */

	private val listener = object: HeadUnit.Listener {
		override fun onVideo(data: ByteArray, offset: Int, length: Int, timestampUs: Long, config: Boolean) {
			synchronized(decoderLock) {
				if (config) codecConfig = data.copyOfRange(offset, offset + length)
				val codec = ensureDecoder() ?: return
				try {
					queue(codec, data, offset, length, timestampUs, if (config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
					drain(codec)
				} catch (e: Exception) {
					Log.w(TAG, "Video decoder problem, starting it again", e)
					releaseDecoder()
				}
			}
		}

		override fun onVideoStop() {}

		override fun onAudio(channel: Int, data: ByteArray, offset: Int, length: Int) {
			player(channel).write(data, offset, length)
		}

		override fun onAudioStop(channel: Int) {
			audio[channel]?.pause()
		}

		override fun onMicrophone(open: Boolean) {
			if (open) startMicrophone() else stopMicrophone()
		}

		override fun onStatus(status: String) {
			status(status)
		}
	}
}
