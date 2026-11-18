package me.hufman.androidautoidrive.carapp.androidauto

import android.hardware.display.VirtualDisplay
import android.util.Log
import io.bimmergestalt.idriveconnectkit.RHMIDimensions
import io.bimmergestalt.idriveconnectkit.android.CarAppAssetResources
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import me.hufman.androidautoidrive.AppSettings
import me.hufman.androidautoidrive.BuildConfig
import me.hufman.androidautoidrive.CarInformation
import me.hufman.androidautoidrive.ConnectionLog
import me.hufman.androidautoidrive.MutableAppSettingsReceiver
import me.hufman.androidautoidrive.carapp.CarAppService
import me.hufman.androidautoidrive.carapp.maps.MapApp
import me.hufman.androidautoidrive.carapp.maps.MapAppMode
import me.hufman.androidautoidrive.carapp.maps.MapsInteractionControllerListener
import me.hufman.androidautoidrive.carapp.maps.VirtualDisplayScreenCapture
import me.hufman.androidautoidrive.carapp.music.MusicAppMode
import me.hufman.androidautoidrive.cds.CDSDataProvider
import me.hufman.androidautoidrive.maps.CdsLocationProvider
import me.hufman.androidautoidrive.maps.MapPlaceSearch
import me.hufman.androidautoidrive.maps.MapResult

/**
 * The Android Auto app's entry in the car: one full-screen app under ConnectedDrive, built the same way
 * as i3D's dash. Android Auto's picture is drawn on a hidden display on the phone, and each frame is
 * sent to the iDrive screen as a picture through the MyBMW app's link.
 *
 * It only runs in the separate Android Auto build (BuildConfig.ANDROID_AUTO_ONLY), and uses its own
 * names in the car so it can sit alongside i3D.
 */
class AndroidAutoAppService: CarAppService() {
	companion object {
		const val TAG = "i3AndroidAuto"
		const val RHMI_IDENT = "me.hufman.androidautoidrive.androidauto"
		const val AM_IDENT = "androidautoidrive.androidauto"
	}

	var mapAppMode: MapAppMode? = null
	var mapApp: MapApp? = null
	var screenCapture: VirtualDisplayScreenCapture? = null
	var virtualDisplay: VirtualDisplay? = null
	var controller: AndroidAutoController? = null
	var interactionListener: MapsInteractionControllerListener? = null

	override fun shouldStartApp(): Boolean = BuildConfig.ANDROID_AUTO_ONLY
	override val logName: String = "Android Auto"

	/** The car's letter wheel isn't used, but the car app needs something to answer it */
	private object NoSearch: MapPlaceSearch {
		override fun searchLocationsAsync(query: String): Deferred<List<MapResult>> = CompletableDeferred(emptyList())
		override fun resultInformationAsync(resultId: String): Deferred<MapResult?> = CompletableDeferred(null)
	}

	override fun onCarStart() {
		Log.i(TAG, "Starting Android Auto on the car screen")
		ConnectionLog.event("Starting Android Auto on the car screen")
		val handler = this.handler!!

		val cdsData = CDSDataProvider()
		cdsData.setConnection(CarInformation.cdsData.asConnection(cdsData))
		val carLocationProvider = CdsLocationProvider(cdsData, false)

		val appSettings = MutableAppSettingsReceiver(this, handler)
		appSettings[AppSettings.KEYS.MAP_WIDESCREEN] = "true"       // the whole width of the i3's wide screen

		val mapAppMode = MapAppMode.build(RHMIDimensions.create(carInformation.capabilities), appSettings, cdsData,
				MusicAppMode.TRANSPORT_PORTS.fromPort(iDriveConnectionStatus.port) ?: MusicAppMode.TRANSPORT_PORTS.BT)
		this.mapAppMode = mapAppMode

		val screenCapture = VirtualDisplayScreenCapture.build(mapAppMode)
		this.screenCapture = screenCapture
		val virtualDisplay = VirtualDisplayScreenCapture.createVirtualDisplay(applicationContext, screenCapture.imageCapture, 160, "AndroidAutoDisplay")
		this.virtualDisplay = virtualDisplay

		val controller = AndroidAutoController(applicationContext, virtualDisplay, carLocationProvider, mapAppMode)
		this.controller = controller
		val interactionListener = MapsInteractionControllerListener(applicationContext, controller)
		interactionListener.onCreate()
		this.interactionListener = interactionListener

		val mapApp = MapApp(iDriveConnectionStatus, securityAccess,
				CarAppAssetResources(applicationContext, "smartthings"),
				mapAppMode, carLocationProvider,
				controller,
				NoSearch,
				screenCapture,
				" ", "Android Auto",
				resources.openRawResource(me.hufman.androidautoidrive.R.raw.android_auto_entry).use { it.readBytes() },
				RHMI_IDENT, AM_IDENT)
		this.mapApp = mapApp
		mapApp.onCreate(handler)
		ConnectionLog.appShown()
	}

	override fun onCarStop() {
		fun step(what: String, action: () -> Unit) {
			try { action() } catch (e: Exception) { Log.w(TAG, "Problem while shutting down ($what)", e) }
		}
		val display = virtualDisplay
		step("screen capture") { screenCapture?.onDestroy() }
		step("listener") { interactionListener?.onDestroy() }
		step("car app") { mapApp?.onDestroy() }
		step("car connection") { mapApp?.disconnect() }
		val c = controller
		if (c != null) {
			step("Android Auto") { c.onDestroy { step("display") { display?.release() } } }
		} else {
			step("display") { display?.release() }
		}
		screenCapture = null
		interactionListener = null
		controller = null
		virtualDisplay = null
		mapApp = null
	}
}
