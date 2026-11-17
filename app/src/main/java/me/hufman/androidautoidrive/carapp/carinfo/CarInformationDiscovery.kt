package me.hufman.androidautoidrive

import de.bmw.idrive.BMWRemotingServer
import de.bmw.idrive.BaseBMWRemotingClient
import io.bimmergestalt.idriveconnectkit.IDriveConnection
import io.bimmergestalt.idriveconnectkit.android.CarAppResources
import io.bimmergestalt.idriveconnectkit.android.IDriveConnectionStatus
import io.bimmergestalt.idriveconnectkit.android.security.SecurityAccess
import me.hufman.androidautoidrive.cds.CDSConnection
import me.hufman.androidautoidrive.cds.CDSConnectionEtch
import me.hufman.androidautoidrive.cds.CDSEventHandler
import me.hufman.androidautoidrive.cds.onPropertyChangedEvent

class CarInformationDiscovery(iDriveConnectionStatus: IDriveConnectionStatus, securityAccess: SecurityAccess, carAppAssets: CarAppResources, val listener: CarInformationDiscoveryListener) {
	val carappListener: CarAppListener
	val carConnection: BMWRemotingServer
	var capabilities: Map<String, String?>? = null
	/** i3D: how far it got, for the connection log */
	var step = "connecting"
		private set

	init {
		carappListener = CarAppListener(listener)
		carConnection = IDriveConnection.getEtchConnection(iDriveConnectionStatus.host
				?: "127.0.0.1", iDriveConnectionStatus.port ?: 8003, carappListener)
		// i3D: each step goes in the connection log, so a car that stops answering shows where
		try {
			val appCert = carAppAssets.getAppCertificate(iDriveConnectionStatus.brand
					?: "")?.readBytes() as ByteArray
			step = "sending the certificate"
			val sas_challenge = carConnection.sas_certificate(appCert)
			step = "signing the car's challenge with MyBMW"
			val sas_login = securityAccess.signChallenge(challenge = sas_challenge)
			step = "logging in"
			carConnection.sas_login(sas_login)
			step = "logged in"
		} catch (e: Exception) {
			try { IDriveConnection.disconnectEtchConnection(carConnection) } catch (e2: Exception) {}
			throw DiscoveryStepException(step, e)
		}
	}

	fun onCreate() {
		step = "asking for the car's details"
		getCapabilities()
		step = "read the car's details"
		listener.onCdsConnection(CDSConnectionEtch(carConnection))
	}

	private fun getCapabilities() {
		val capabilities = carConnection.rhmi_getCapabilities("", 255)

		// report the capabilities to any debug view
		val stringCapabilities = capabilities
				.mapKeys { it.key as String }
				.mapValues { it.value?.toString() }
		this.capabilities = stringCapabilities
		try {
			listener.onCapabilities(stringCapabilities)
		} catch (e: Exception) {
		}

		// report the capabilities to analytics
		val reportedKeys = setOf(
				"vehicle.type", "vehicle.country", "vehicle.productiondate",
				"hmi.type", "hmi.version", "hmi.display-width", "hmi.display-height", "hmi.role",
				"navi", "map", "tts", "inbox", "speech2text", "voice", "a4axl", "pia", "speedlock",
				"alignment-right", "touch_command"
		)
		val reportedCapabilities = capabilities.filterKeys { it is String && it in reportedKeys }
				.mapKeys { it.key as String }
				.mapValues { it.value?.toString() }
		Analytics.reportCarCapabilities(reportedCapabilities)
	}

	class CarAppListener(val cdsEventHandler: CDSEventHandler): BaseBMWRemotingClient() {
		override fun cds_onPropertyChangedEvent(handle: Int?, ident: String?, propertyName: String?, propertyValue: String?) {
			cdsEventHandler.onPropertyChangedEvent(ident, propertyValue)
		}
	}

	fun onDestroy() {
		try {
			IDriveConnection.disconnectEtchConnection(carConnection)
		} catch (e: Exception) {}
		listener.onCdsConnection(null)
	}
}

/** i3D: which step the car stopped answering at */
class DiscoveryStepException(val step: String, cause: Exception): Exception("while $step: ${cause.javaClass.simpleName} ${cause.message ?: ""}".trim(), cause)

interface CarInformationDiscoveryListener: CDSEventHandler {
	fun onCapabilities(capabilities: Map<String, String?>)
	fun onCdsConnection(connection: CDSConnection?)
}