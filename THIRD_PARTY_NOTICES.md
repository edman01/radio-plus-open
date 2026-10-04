# Third party notices and scope of the license

The MIT license covers the project's original code and documentation to the
extent the contributors can license it. It does not grant rights to manufacturers'
software, visual designs, station artwork, trademarks or the underlying subject
matter shown in demonstration images.

## Hardware interoperability

`com.hcn.autoradio`, `FMPlugService`, AIDL interface names and vendor class names
are compatibility identifiers. The app uses the installed vendor service; no
vendor service implementation, manufacturer APK, native tuner library, firmware
or system signing key is bundled here. Two AIDL files describe the compatible
service/callback contracts. Those contracts are based on the existing interface;
their presence is not a claim of vendor permission or a legal opinion about
interoperability exceptions. The vendor's software remains separately licensed.

## Images and visual design

The public app contains no station-logo collection or automatic logo download.
The current public illustrations use fictional station names, geometric logos
and track text; these are not bundled app content or claims of broadcaster
endorsement. They are not claimed to be exclusive or trademark-cleared. Earlier
real station artwork remains in Git history and is not licensed under this
project's MIT license. See [image provenance](docs/images/README.md).
The AI-assisted head-unit composite incorporates a user-supplied frame reference
and an edited emulator image. It is illustrative, not an official product
photograph, affiliation statement or hardware test result.

The launcher artwork was supplied for this project. Its underlying rights and
the OEM-inspired interface's visual/design rights have not received independent
legal clearance. Publishing the source or charging no money does not resolve
third-party rights. Redistributors must assess their intended use separately;
do not market this as an official or authorized Junsun or Škoda product.

## Build and test dependencies

- Android Gradle Plugin and AndroidX Test Runner: Apache License 2.0.
- JUnit 4: Eclipse Public License 1.0.
- Gradle is an external build tool, not an included runtime component.
- The Android SDK is separately supplied under its own terms.

Test dependencies are not packaged as runtime dependencies in the release APK.
No third-party station-logo license is conveyed by linking a download source or
by allowing a user to select a file.
