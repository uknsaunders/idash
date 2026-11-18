# i3 Android Auto (experimental)

This branch (`android-auto`) holds only the Android Auto app. i3D, the dash, lives on `main`; the two share the original AAIdrive code for connecting to the car through MyBMW, and this branch was started from `main`.


**i3 Android Auto** shows your phone's Android Auto on the iDrive screen. It works the way i3D does: it goes through the MyBMW app's link and sends Android Auto's screen to the car as pictures, so nothing in the car is changed. It installs next to i3D, has its own green icon, and has its own **Android Auto** entry under ConnectedDrive.

Download `i3-android-auto.apk` from the [`android-auto-latest` release](https://github.com/uknsaunders/idash/releases/tag/android-auto-latest).

How it works: the app acts as the Android Auto "head unit" on the phone itself. Android Auto draws its screen for the app, the app decodes it onto a hidden display, and each frame goes to the car like i3D's dash.

- **The knob:** turn to move around Android Auto, press once to choose, twice to go back, three times for Android Auto's home screen.
- **Sound** plays on the phone, so it reaches the car over Bluetooth like other apps. The Assistant and calls use the phone's microphone.
- **Driving and night:** the app tells Android Auto when the car is moving, and uses night colours after sunset.
- **The picture** updates as fast as the MyBMW link carries pictures, which is slower than a built-in Android Auto screen. Fine for maps and lists.

Setting up, on the phone (the app's home screen walks through these):

1. **A head unit certificate.** Android Auto only talks to screens that present one, and the app doesn't include one. You supply a PEM file with the certificate and its RSA private key. Open-source Android Auto head unit projects publish the one they use. Using it is between you and Google's terms.
2. **Android Auto's developer setting.** In Android Auto's settings, tap Version about ten times to turn on developer settings, then choose **Start head unit server** from the three-dot menu.
3. **The microphone**, if you want the Assistant.

Not yet tried in the car. Both apps connect through MyBMW at the same time; if that turns out to be unreliable, close one. Android Auto may change or remove the head unit server setting in an update.

Build it with `./gradlew assembleNomapNonalyticsFullDebug`. `BuildConfig.ANDROID_AUTO_ONLY` (always true on this branch) keeps the other car apps from the original AAIdrive code switched off. The Android Auto code is in `app/src/main/java/me/hufman/androidautoidrive/androidauto` (the protocol) and `carapp/androidauto` (the car and phone sides).

## Building

GitHub Actions builds the app on every push to this branch (`.github/workflows/android-auto.yml`), runs the Android Auto checks, and publishes `i3-android-auto.apk` to the `android-auto-latest` release. It is signed with the same personal debug key as i3D, `i3dash-debug.keystore`, so updates install over each other.

## Changes

See the [changelog](CHANGELOG.md).

## Credits and licence

Built on [AAIdrive](https://github.com/BimmerGestalt/AAIdrive) and the BimmerGestalt libraries. MIT licensed, see [LICENSE](LICENSE).

