package me.hufman.androidautoidrive.carapp.carinfo

import android.util.Log
import io.bimmergestalt.idriveconnectkit.android.CarAppAssetResources
import me.hufman.androidautoidrive.CarInformationDiscovery
import me.hufman.androidautoidrive.DiscoveryStepException
import me.hufman.androidautoidrive.CarInformationUpdater
import me.hufman.androidautoidrive.MainService
import me.hufman.androidautoidrive.MutableAppSettingsReceiver
import me.hufman.androidautoidrive.carapp.CarAppService
import me.hufman.androidautoidrive.cds.CDSConnection
import me.hufman.androidautoidrive.cds.CDSConnectionAsync

class CarInformationDiscoveryService: CarAppService() {
	val appSettings by lazy { MutableAppSettingsReceiver(applicationContext) }
	var carappCapabilities: CarInformationDiscovery? = null

	override fun shouldStartApp(): Boolean {
		return true
	}

	override fun onCarStart() {
		Log.i(MainService.TAG, "Starting to discover car capabilities")
		val handler = handler!!

		// receiver to receive capabilities and cds properties
		// wraps the CDSConnection with a Handler async wrapper
		val carInformationUpdater = object: CarInformationUpdater(appSettings) {
			override fun onCdsConnection(connection: CDSConnection?) {
				super.onCdsConnection(connection?.let { CDSConnectionAsync(handler, connection) })
			}
		}

		// i3D: sign in as MyBMW's cdsbaseapp first (it can read the most car data), and if the car stops
		// answering, as smartthings - the same certificate the connection check has just used successfully.
		// Whichever works is tried first next time.
		val available = listOf("cdsbaseapp", "smartthings").filter {
			CarAppAssetResources(applicationContext, it).getAppCertificateRaw("") != null
		}
		val order = listOfNotNull(workingCert?.takeIf { it in available }) + available.filter { it != workingCert }
		var lastError: Exception? = null
		for (certName in order) {
			var discovery: CarInformationDiscovery? = null
			try {
				me.hufman.androidautoidrive.ConnectionLog.event("Reading the car's details (signed in as $certName)")
				discovery = CarInformationDiscovery(iDriveConnectionStatus, securityAccess,
						CarAppAssetResources(applicationContext, certName), carInformationUpdater)
				discovery.onCreate()
				carappCapabilities = discovery
				workingCert = certName
				me.hufman.androidautoidrive.ConnectionLog.event("Read the car's details (signed in as $certName)")
				return
			} catch (e: Exception) {
				val step = (e as? DiscoveryStepException)?.step ?: discovery?.step ?: "connecting"
				me.hufman.androidautoidrive.ConnectionLog.problem("Signed in as $certName, the car stopped answering while $step (${e.javaClass.simpleName})")
				try { discovery?.onDestroy() } catch (e2: Exception) {}
				lastError = e
			}
		}
		throw lastError ?: IllegalStateException("No certificate to read the car's details with")
	}

	companion object {
		/** i3D: the certificate that last worked, tried first */
		@Volatile var workingCert: String? = null
	}

	override fun onCarStop() {
		carappCapabilities?.onDestroy()
		carappCapabilities = null
	}
}