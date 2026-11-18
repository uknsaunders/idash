package me.hufman.androidautoidrive.carapp.androidauto

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import io.bimmergestalt.idriveconnectkit.CDS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.hufman.androidautoidrive.CarInformation
import me.hufman.androidautoidrive.carapp.maps.MapAppMode
import me.hufman.androidautoidrive.carapp.maps.MapInteractionController
import me.hufman.androidautoidrive.cds.flow
import me.hufman.androidautoidrive.maps.CarLocationProvider
import me.hufman.androidautoidrive.maps.LatLong
import me.hufman.androidautoidrive.utils.GsonNullable.tryAsDouble
import me.hufman.androidautoidrive.utils.GsonNullable.tryAsJsonPrimitive

/**
 * The car side of the Android Auto app: Android Auto's picture on a hidden display that goes to the
 * car as pictures, and the iDrive knob passed to Android Auto.
 *
 * The knob only turns and presses, so presses are counted: one selects, two go back,
 * three go to Android Auto's home screen.
 */
class AndroidAutoController(val context: Context,
                            val virtualDisplay: VirtualDisplay,
                            val carLocationProvider: CarLocationProvider,
                            val mapAppMode: MapAppMode): MapInteractionController {
	companion object {
		const val TAG = "i3AndroidAuto"
		/** Presses closer together than this count as one double or triple press */
		const val PRESS_WINDOW_MS = 400L
		const val SENSOR_INTERVAL_MS = 1000L
		/** after leaving the car screen for this long, the hidden display is taken down */
		const val SHUTDOWN_DELAY_MS = 120000L
	}

	private val handler = Handler(context.mainLooper)
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private val carInfo = CarInformation()
	val session = AndroidAutoSession(context.applicationContext, mapAppMode.maxWidth, mapAppMode.maxHeight)
	private var presentation: AndroidAutoPresentation? = null
	@Volatile private var speedKmh: Double? = null
	@Volatile private var lastLocation: LatLong? = null
	private var presses = 0
	@Volatile private var showing = false

	init {
		session.onStatus = { text -> handler.post { presentation?.showStatus(text) } }
		session.onPicture = { handler.post { presentation?.showStatus(null) } }
		carLocationProvider.callback = { location: Location -> lastLocation = LatLong(location.latitude, location.longitude) }
		scope.launch {
			try {
				carInfo.cdsData.flow[CDS.DRIVING.SPEEDACTUAL].collect { json ->
					speedKmh = json.tryAsJsonPrimitive("speedActual")?.tryAsDouble
				}
			} catch (e: Exception) {
				Log.w(TAG, "Stopped watching the speed", e)
			}
		}
	}

	/** No speed reading counts as moving, so Android Auto stays careful */
	private fun isMoving(): Boolean = (speedKmh ?: return true) >= 3.0

	/** Night by where the sun is, at the car's position (or Jersey until it has one) */
	private fun isNight(): Boolean {
		val p = lastLocation ?: LatLong(49.19, -2.07)
		return !Sun.isUp(p.latitude, p.longitude, System.currentTimeMillis())
	}

	private val sensorRunnable = object: Runnable {
		override fun run() {
			if (!showing) return
			session.setMoving(isMoving())
			session.setNight(isNight())
			handler.postDelayed(this, SENSOR_INTERVAL_MS)
		}
	}

	private val shutdownRunnable = Runnable {
		Log.i(TAG, "Taking down the hidden display after ${SHUTDOWN_DELAY_MS}ms in the background")
		presentation?.dismiss()
		presentation = null
	}

	override fun showMap() {
		handler.post {
			showing = true
			handler.removeCallbacks(shutdownRunnable)
			val p = presentation ?: AndroidAutoPresentation(context, virtualDisplay.display, session).also { presentation = it }
			if (!p.isShowing) p.show()
			carLocationProvider.start()
			session.setMoving(isMoving())
			session.setNight(isNight())
			session.start()
			handler.removeCallbacks(sensorRunnable)
			handler.post(sensorRunnable)
		}
	}

	override fun pauseMap() {
		handler.post {
			showing = false
			handler.removeCallbacks(sensorRunnable)
			carLocationProvider.stop()
			// Android Auto keeps running, so coming back is quick
			handler.postDelayed(shutdownRunnable, SHUTDOWN_DELAY_MS)
		}
	}

	private fun knob(steps: Int) {
		mapAppMode.startInteraction()
		session.rotate(steps)
	}

	/** The iDrive knob, turned one way */
	override fun zoomIn(steps: Int) = knob(-steps)
	/** The iDrive knob, turned the other way */
	override fun zoomOut(steps: Int) = knob(steps)

	/** The knob press stays here, so the car doesn't open its own menu over Android Auto */
	override fun handlesClick(): Boolean = true

	private val pressRunnable = Runnable {
		val count = presses
		presses = 0
		when {
			count >= 3 -> session.home()
			count == 2 -> session.back()
			count == 1 -> session.select()
		}
	}

	override fun click() {
		mapAppMode.startInteraction()
		handler.post {
			presses++
			handler.removeCallbacks(pressRunnable)
			handler.postDelayed(pressRunnable, PRESS_WINDOW_MS)
		}
	}

	override fun navigateTo(dest: LatLong) {}
	override fun recalcNavigation() {}
	override fun stopNavigation() {}

	fun onDestroy(afterwards: () -> Unit = {}) {
		showing = false
		try { session.stop() } catch (e: Exception) { Log.w(TAG, "Couldn't stop Android Auto", e) }
		try { scope.cancel() } catch (e: Exception) {}
		handler.removeCallbacksAndMessages(null)
		handler.post {
			try { presentation?.dismiss() } catch (e: Exception) { Log.w(TAG, "Couldn't close the hidden display", e) }
			presentation = null
			try { afterwards() } catch (e: Exception) { Log.w(TAG, "Problem after closing", e) }
		}
	}
}

/**
 * Android Auto's picture, decoded straight onto a TextureView on the hidden display. Android Auto draws
 * its screen in the middle of a larger video, so the view is zoomed to just that middle part.
 * A line of text covers it until the picture arrives, or when something goes wrong.
 */
class AndroidAutoPresentation(context: Context, display: Display, private val session: AndroidAutoSession): Presentation(context, display) {
	private var view: TextureView? = null
	private var status: TextView? = null
	private var surface: Surface? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val root = FrameLayout(context)
		root.setBackgroundColor(Color.BLACK)
		val fill = FrameLayout.LayoutParams.MATCH_PARENT
		val texture = TextureView(context)
		texture.surfaceTextureListener = object: TextureView.SurfaceTextureListener {
			override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
				st.setDefaultBufferSize(session.video.width, session.video.height)
				fit(texture, width, height)
				val s = Surface(st)
				surface = s
				session.setSurface(s)
			}
			override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = fit(texture, width, height)
			override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
				session.setSurface(null)
				surface?.release()
				surface = null
				return true
			}
			override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
		}
		root.addView(texture, FrameLayout.LayoutParams(fill, fill))
		val label = TextView(context).apply {
			setBackgroundColor(Color.BLACK)
			setTextColor(Color.WHITE)
			textSize = 26f
			gravity = Gravity.CENTER
			setPadding(60, 0, 60, 0)
		}
		root.addView(label, FrameLayout.LayoutParams(fill, fill))
		view = texture
		status = label
		setContentView(root)
		showStatus(if (session.isConnected) null else session.lastStatus.takeIf { it != "Not started" } ?: "Starting Android Auto…")
	}

	private fun fit(texture: TextureView, width: Int, height: Int) {
		val v = session.video
		val sx = v.width.toFloat() / (v.width - v.marginWidth)
		val sy = v.height.toFloat() / (v.height - v.marginHeight)
		texture.setTransform(Matrix().apply { setScale(sx, sy, width / 2f, height / 2f) })
	}

	/** Messages only come while starting or when something goes wrong, so they always show. Null hides it */
	fun showStatus(text: String?) {
		val label = status ?: return
		if (text == null) {
			label.visibility = View.GONE
		} else {
			label.text = text + "\n\nPress the knob three times for Android Auto's home screen"
			label.visibility = View.VISIBLE
		}
	}

	override fun onStop() {
		session.setSurface(null)
		super.onStop()
	}
}

/** Whether the sun is up, the same sum the i3D dash uses for its day and night map */
object Sun {
	fun isUp(lat: Double, lon: Double, ms: Long): Boolean {
		val r = Math.PI / 180
		val d = ms / 864e5 - 10957.5
		val g = (357.529 + 0.98560028 * d) * r
		val l = (280.459 + 0.98564736 * d + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g)) * r
		val e = (23.439 - 0.00000036 * d) * r
		val ra = Math.atan2(Math.cos(e) * Math.sin(l), Math.cos(l))
		val dec = Math.asin(Math.sin(e) * Math.sin(l))
		val ha = ((18.697374558 + 24.06570982441908 * d) * 15 + lon) * r - ra
		return Math.asin(Math.sin(lat * r) * Math.sin(dec) + Math.cos(lat * r) * Math.cos(dec) * Math.cos(ha)) / r > -4
	}
}
