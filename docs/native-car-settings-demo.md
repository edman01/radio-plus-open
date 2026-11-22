# Native car settings demo

This opt-in, import-only APK runs the real Radio+ app. Its Settings action opens a native Android activity, also exposed as the in-package `android.intent.action.APPLICATION_PREFERENCES` handler. The regular APK has no such activity and retains its existing settings dialog.

From the project root, using the project's Gradle version:

```sh
gradle -I tools/car-settings-demo.gradle -PexperimentalTestBuild6=true :app:assemblePlayCarSettingsDemo
```

The package is `fi.radioplus.app.play.settingsdemo`, version `0.16.1-settings-demo3`. It updates previous settings demos in place. It uses the SDK debug signing configuration. It does not replace any normal build or share its private preferences. The launcher opens the normal radio, without seeded sample stations. Open **Settings** in the bottom navigation.

## What to try

- Toggle auto-start, close settings, then reopen. The preference is the one consumed by this APK's boot receiver. Changing the language updates the real radio UI.
- On a stock emulator, sensitivity shows **Unavailable** and explains why. On a supported connected tuner, LOCAL/DX choices read and write its actual state.
- Open steering controls, diagnostics, or About. Back returns one level. Close on the root finishes settings and returns to the caller, without opening another radio instance.

## Design and verification

The design uses Google's [custom automotive media screens](https://developers.google.com/cars/design/automotive-os/apps/media/guidelines-custom-screens) and [settings guidance](https://developers.google.com/cars/design/automotive-os/apps/media/create-your-app/design-settings). The shared header deliberately omits the optional brand mark: it has only the native borderless navigation button and the screen title. Close exits settings; Up returns one screen. The translated button label is shown when space permits and is always available to accessibility services. Controls are native, at least 80 dp in each dimension, with 24 dp between adjacent targets. Primary type starts at 32 (title: 36), secondary at 24. Text respects larger system font settings, wraps, and never scales below the guideline minimum. Settings do not declare a distraction-optimized exemption.

To run just the demo's device tests on a stock emulator:

```sh
gradle -I tools/car-settings-demo.gradle -PexperimentalTestBuild6=true :app:assemblePlayCarSettingsDemo :app:assemblePlayCarSettingsDemoAndroidTest
adb install -r app/build/outputs/apk/play/carSettingsDemo/app-play-carSettingsDemo.apk
adb install -r app/build/outputs/apk/androidTest/play/carSettingsDemo/app-play-carSettingsDemo-androidTest.apk
adb shell am instrument -w -e class fi.radioplus.app.CarSettingsDemoTest fi.radioplus.app.play.settingsdemo.test/androidx.test.runner.AndroidJUnitRunner
```

This is a sideloadable aftermarket-radio demo, not a dedicated Google Play Android Automotive OS distribution. On AAOS the system enforces restrictions for parked activities. Generic Android head units do not necessarily supply that signal. Adjust settings while parked; neither a successful emulator test nor this design guide constitutes Google certification.
