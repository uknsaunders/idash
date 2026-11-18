package me.hufman.androidautoidrive.carapp.androidauto

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The phone side of the Android Auto app: adding the head unit certificate, the Android Auto developer
 * setting that lets this app connect, and a check that Android Auto is listening.
 */
class AndroidAutoSetupActivity: Activity() {
	companion object {
		const val PICK_CERTIFICATE = 71
		const val GEARHEAD = "com.google.android.projection.gearhead"
	}

	private lateinit var certStatus: TextView
	private lateinit var linkStatus: TextView
	private lateinit var removeButton: Button

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val list = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(20), dp(24), dp(20), dp(32))
		}
		list.addView(text("Android Auto on the car screen", 24f, bold = true))
		list.addView(text("This app shows your phone's Android Auto on the iDrive screen, through the MyBMW app's link, the same way i3D shows its dash. " +
				"In the car it is the Android Auto entry under ConnectedDrive. Turn the knob to move around Android Auto, press once to choose, " +
				"twice to go back, and three times for Android Auto's home screen. " +
				"Sound plays through the phone, so it reaches the car over Bluetooth like other apps.", 15f))

		list.addView(section("1. Head unit certificate"))
		list.addView(text("Android Auto only talks to screens that present a head unit certificate. This app doesn't come with one. " +
				"Choose a file with the certificate and its private key in PEM form (the text blocks that start " +
				"-----BEGIN CERTIFICATE----- and -----BEGIN RSA PRIVATE KEY-----). It stays on this phone.", 15f))
		certStatus = text("", 16f, bold = true)
		list.addView(certStatus)
		list.addView(button("Choose the certificate file") {
			val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
			try { startActivityForResult(pick, PICK_CERTIFICATE) } catch (e: Exception) { toast("This phone has no file picker") }
		})
		removeButton = button("Remove the certificate") {
			AndroidAutoSession.certificateFile(this).delete()
			refresh()
		}
		list.addView(removeButton)

		list.addView(section("2. Let this app connect to Android Auto"))
		list.addView(text("Do this once on the phone. It may need doing again after the phone or Android Auto updates.\n\n" +
				"a. Open Android Auto's settings (button below, or Settings › Connected devices › Android Auto).\n" +
				"b. Scroll to the bottom and tap Version about ten times, until it offers developer settings. Allow them.\n" +
				"c. Tap the three dots at the top right and choose Start head unit server.\n\n" +
				"Android Auto then waits for this app on the phone. Nothing is opened to other devices.", 15f))
		list.addView(button("Open Android Auto's settings") { openAndroidAuto() })

		list.addView(section("3. Check"))
		linkStatus = text("", 15f)
		list.addView(button("Check that Android Auto is listening") { checkLink() })
		list.addView(linkStatus)

		list.addView(section("In the car"))
		list.addView(text("On the car, open ConnectedDrive and choose Android Auto. If it can't connect, the screen says why. " +
				"Android Auto keeps running while you use the car's own menus, so coming back to it is quick.", 15f))

		val scroll = ScrollView(this)
		scroll.addView(list)
		setContentView(scroll)
		refresh()
	}

	private fun refresh() {
		val has = AndroidAutoSession.hasCertificate(this)
		certStatus.text = if (has) "✓  Certificate added" else "○  No certificate yet"
		removeButton.visibility = if (has) View.VISIBLE else View.GONE
	}

	@Deprecated("Deprecated in Java")
	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		super.onActivityResult(requestCode, resultCode, data)
		if (requestCode != PICK_CERTIFICATE || resultCode != RESULT_OK) return
		val uri: Uri = data?.data ?: return
		thread {
			val result = try {
				val pem = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
				val problem = AndroidAutoSession.checkCertificate(pem)
				if (problem == null) {
					AndroidAutoSession.certificateFile(this).writeText(pem)
					"Certificate added"
				} else {
					"That file can't be used: $problem"
				}
			} catch (e: Exception) {
				"Couldn't read the file: ${e.message}"
			}
			runOnUiThread { toast(result); refresh() }
		}
	}

	private fun openAndroidAuto() {
		val attempts = listOf(
			Intent().setClassName(GEARHEAD, "com.google.android.apps.auto.carservice.settings.SettingsActivity"),
			Intent().setClassName(GEARHEAD, "com.google.android.projection.gearhead.companion.settings.SettingsActivity"),
			packageManager.getLaunchIntentForPackage(GEARHEAD),
			Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", GEARHEAD, null))
		)
		for (intent in attempts) {
			if (intent == null) continue
			try {
				startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
				return
			} catch (e: Exception) {
				// try the next way in
			}
		}
		toast("Android Auto isn't installed on this phone")
	}

	private fun checkLink() {
		linkStatus.text = "Checking…"
		thread {
			val result = try {
				Socket().use { it.connect(InetSocketAddress("127.0.0.1", AndroidAutoSession.PORT), 2000) }
				"✓  Android Auto is listening. " + (if (AndroidAutoSession.hasCertificate(this)) "Ready for the car." else "Add the certificate too.")
			} catch (e: Exception) {
				"○  Android Auto isn't listening. Do step 2c (Start head unit server), then check again."
			}
			runOnUiThread { linkStatus.text = result }
		}
	}

	private fun toast(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()

	private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

	private fun text(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
		text = value
		setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
		if (bold) setTypeface(typeface, Typeface.BOLD)
		layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
	}

	private fun section(value: String) = text(value, 18f, bold = true).apply {
		(layoutParams as LinearLayout.LayoutParams).topMargin = dp(28)
	}

	private fun button(label: String, action: () -> Unit) = Button(this).apply {
		text = label
		isAllCaps = false
		setOnClickListener { action() }
		layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
	}
}
