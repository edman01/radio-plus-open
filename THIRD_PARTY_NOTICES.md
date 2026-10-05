# Third-party notices

The [MIT license](LICENSE) applies to Radio+'s original code and documentation.
It does not grant rights to manufacturer software, third-party artwork,
visual designs or trademarks. Radio+ is an independent project, not an official
Junsun or Škoda product.

## Radio services

Package names, service names and AIDL interfaces identify compatible radio
services. Radio+ uses the software already installed on the head unit.
Manufacturer APKs, tuner drivers, native libraries, firmware and system signing
keys are not included. Manufacturer software remains separately licensed.

## Artwork

The public app has no bundled station-logo collection or automatic logo download.
Use images you have permission to use when importing custom logos.

The documentation uses illustrations with fictional station names and geometric
logos. They do not show a verified installation or imply broadcaster endorsement.
See [the illustration guide](docs/images/README.md).

Third-party artwork, including artwork in earlier revisions, is not covered by
the project's MIT license. The code license does not grant rights to the launcher
artwork or head-unit reference image.

## Build and test dependencies

- Android Gradle Plugin and AndroidX Test Runner: Apache License 2.0.
- JUnit 4: Eclipse Public License 1.0.
- Gradle is an external build tool, not an included runtime component.
- The Android SDK is separately supplied under its own terms.

Test dependencies are not included in the release APK.
