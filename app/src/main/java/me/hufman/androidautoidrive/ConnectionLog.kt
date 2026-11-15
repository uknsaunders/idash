package me.hufman.androidautoidrive

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * i3D: a short, plain record of how the car connection went, kept on the phone.
 *
 * The phone's home screen shows the last problem from here while it retries, and offers to
 * share the whole log, so a connection that fails in the car can be diagnosed afterwards.
 * Repeats of the same line (a retry failing the same way every few seconds) are written once.
 */
object ConnectionLog {
	private const val TAG = "ConnectionLog"
	private const val FILE = "connection.log"
	private const val MAX_LINES = 300

	private val lines = java.util.ArrayDeque<String>()
	private var file: File? = null
	private const val REPEAT_WINDOW = 10 * 60 * 1000L     // the same line again within this is a retry, not news
	private val lastWritten = HashMap<String, Long>()
	private val timeFormat = SimpleDateFormat("dd MMM HH:mm:ss", Locale.UK)
	private val listeners = CopyOnWriteArrayList<() -> Unit>()

	/** The most recent problem, and when it happened */
	@Volatile var lastProblem: String? = null
		private set
	@Volatile var lastProblemTime: Long = 0
		private set
	/** When i3D last reached the car screen, and when the car link last dropped */
	@Volatile var appShownTime: Long = 0
		private set
	@Volatile var droppedTime: Long = 0
		private set

	fun init(context: Context) {
		synchronized(this) {
			if (file != null) return
			val f = File(context.filesDir, FILE)
			file = f
			try {
				if (f.exists()) f.readLines().takeLast(MAX_LINES).forEach { lines.addLast(it) }
			} catch (e: Exception) {
				Log.w(TAG, "Couldn't read the connection log", e)
			}
		}
	}

	fun addListener(listener: () -> Unit) { listeners.add(listener) }
	fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

	/** Something that went right, or a step along the way */
	fun event(message: String) = add(message, false)

	/** Something that went wrong, shown on the phone until the car connects again */
	fun problem(message: String) = add(message, true)

	/** i3D's own screen was created in the car */
	fun appShown() {
		appShownTime = System.currentTimeMillis()
		lastProblem = null
		synchronized(this) { lastWritten.clear() }     // a fresh connection: log its steps again next time
		add("i3D is on the car screen", false)
	}

	/** The car link went away */
	fun dropped(message: String) {
		droppedTime = System.currentTimeMillis()
		add(message, true)
	}

	private fun add(message: String, problem: Boolean) {
		val changed = synchronized(this) {
			if (problem) {
				lastProblem = message
				lastProblemTime = System.currentTimeMillis()
			}
			val now = System.currentTimeMillis()
			val previous = lastWritten[message]
			if (previous != null && now - previous < REPEAT_WINDOW) return@synchronized false     // the same retry again
			lastWritten[message] = now
			if (lastWritten.size > 200) lastWritten.clear()
			val line = timeFormat.format(Date()) + (if (problem) "  ! " else "    ") + message
			lines.addLast(line)
			while (lines.size > MAX_LINES) lines.removeFirst()
			try {
				file?.writeText(lines.joinToString("\n", postfix = "\n"))
			} catch (e: Exception) {
				Log.w(TAG, "Couldn't save the connection log", e)
			}
			true
		}
		Log.i(TAG, message)
		if (changed) listeners.forEach { try { it() } catch (e: Exception) { } }
	}

	fun isEmpty(): Boolean = synchronized(this) { lines.isEmpty() }

	fun text(): String = synchronized(this) { lines.joinToString("\n") }

	fun clear() {
		synchronized(this) {
			lines.clear()
			lastWritten.clear()
			lastProblem = null
			try { file?.delete() } catch (e: Exception) { }
		}
	}
}
