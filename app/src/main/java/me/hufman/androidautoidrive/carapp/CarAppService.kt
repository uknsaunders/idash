package me.hufman.androidautoidrive.carapp

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.util.Log
import io.bimmergestalt.idriveconnectkit.android.IDriveConnectionReceiver
import io.bimmergestalt.idriveconnectkit.android.IDriveConnectionStatus
import io.bimmergestalt.idriveconnectkit.android.security.SecurityAccess
import me.hufman.androidautoidrive.CarInformation
import me.hufman.androidautoidrive.CarThread
import java.lang.RuntimeException

abstract class CarAppService: Service() {
	companion object {
		const val RESTART_ATTEMPTS = 6
		const val RESTART_FIRST_DELAY = 3000L       // then 6s, 12s, 24s, 48s, 96s
		const val RESTART_RESET_AFTER = 5 * 60 * 1000L
	}
	private val TAG by lazy(this.javaClass::getSimpleName)
	/** i3D: the name used in the phone's connection log */
	open val logName: String
		get() = TAG

	// if we are told to start up again during the shutdown process
	var running = false
		private set

	// where the car app runs, on its own thread
	var thread: CarThread? = null
	val handler: Handler?
		get() = thread?.handler

	// will need changes if the service gets split to separate processes
	val carInformation = CarInformation()
	val iDriveConnectionStatus: IDriveConnectionStatus = IDriveConnectionReceiver()
	val securityAccess by lazy { SecurityAccess.getInstance(applicationContext) }

	override fun onCreate() {
		super.onCreate()
		if (!securityAccess.isConnected()) {
			securityAccess.connect()
		}
	}

	/**
	 * When a car is connected, it will bind the Car App Service
	 */
	override fun onBind(intent: Intent?): IBinder? {
		intent ?: return null
		setConnection(intent)
		startThread()
		return null
	}

	/**
	 * If the thread crashes for any reason,
	 * opening the main app will trigger a Start on this Car App Service
	 * as a chance to reconnect
	 */
	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)
		intent ?: return START_NOT_STICKY
		setConnection(intent)
		startThread()
		return START_STICKY
	}

	/**
	 * The car has disconnected, so forget the previous details and shut down
	 *
	 * Until we are split out to a separate process, rely on the MainService to handle this
	 */
	override fun onUnbind(intent: Intent?): Boolean {
//		IDriveConnectionStatus.reset()
		restartHandler.removeCallbacksAndMessages(null)
		restartAttempts = 0
		stopThread()
		return super.onUnbind(intent)
	}

	override fun onDestroy() {
		restartHandler.removeCallbacksAndMessages(null)
		stopThread()
		super.onDestroy()
	}

	/**
	 * Parses the connection intent and sets the connection details
	 *
	 * Until we are split out to a separate process, rely on the MainService to handle this
	 */
	fun setConnection(intent: Intent) {
		IDriveConnectionReceiver().onReceive(applicationContext, intent)
	}

	/**
	 * Starts the thread for the car app, if it isn't running
	 */
	fun startThread() {
		if (shouldStartApp()) {
			running = true

			if (iDriveConnectionStatus.isConnected &&
					securityAccess.isConnected() &&
					thread?.isAlive != true) {

				val started = System.currentTimeMillis()
				thread = CarThread(logName) {
					onCarStart()
				}.apply {
					onUnexpectedEnd = { scheduleRestart(started) }
				}
				thread?.start()
			}
		} else {
			stopThread()
		}
	}

	/**
	 * Stops the thread for the car app, if it is still running
	 *
	 * If the car forcefully disconnects, the thread will have crashed already
	 * But if the service is being toggled through the UI, we must still gracefully stop
	 */
	fun stopThread() {
		running = false
		onCarStop()

		// shut down the thread after finishing any startup
		try {
			thread?.post {
				// try to stop again after any startup runnables have run
				onCarStop()
				thread?.quit()
				thread = null

				// if we were told to start up again during shutting down, start again
				if (running) {
					startThread()
				}
			}
		} catch (e: RuntimeException) {
			// thread is already dead
		}
	}

	/**
	 * i3D: if the app's thread stops by itself while the car is still connected (a blip in the
	 * car link, or the car still holding the app from a moment ago), start it again after a pause,
	 * waiting a little longer each time, instead of leaving it missing until the next drive.
	 */
	private val restartHandler = Handler(android.os.Looper.getMainLooper())
	private var restartAttempts = 0
	private fun scheduleRestart(startedAt: Long) {
		// a thread that ran happily for a while is a fresh problem, not part of a loop
		if (System.currentTimeMillis() - startedAt > RESTART_RESET_AFTER) restartAttempts = 0
		if (restartAttempts >= RESTART_ATTEMPTS) {
			me.hufman.androidautoidrive.ConnectionLog.problem("$logName kept stopping, so it won't be restarted until the car reconnects")
			return
		}
		val delay = RESTART_FIRST_DELAY shl restartAttempts
		restartAttempts += 1
		Log.i(TAG, "Car app thread ended by itself, restarting in ${delay}ms (attempt $restartAttempts)")
		restartHandler.postDelayed({
			if (running && iDriveConnectionStatus.isConnected) {
				me.hufman.androidautoidrive.ConnectionLog.event("Restarting $logName (try $restartAttempts of $RESTART_ATTEMPTS)")
				try { onCarStop() } catch (e: Exception) { Log.w(TAG, "Problem cleaning up before a restart", e) }
				thread = null
				startThread()
			}
		}, delay)
	}

	abstract fun shouldStartApp(): Boolean

	abstract fun onCarStart()

	abstract fun onCarStop()
}