# Building and validating

## Requirements

The tested build runs on Windows with PowerShell, JDK 24, Android SDK platform 36, and build-tools 35.0.0. Python is needed for the optional APK/data checks. No Gradle, JNA, NDK, colorimeter, or factory firmware download is required to build the normal APK. Android API 33 is the tested runtime; the manifest's minimum API is not a claim of compatibility with other devices.

Set `JAVA_HOME` and `ANDROID_SDK_ROOT`, or provide `-Jdk` and `-Sdk` explicitly.

```powershell
# Local evaluation only; creates/uses an ignored local test keystore.
.\build.ps1 -TestBuild

# Test the actual production decoder using synthetic fixtures.
.\test.ps1

# Inspect the compiled manifest, classes, translations, and bundled assets.
python tools/audit-apk.py --sdk $env:ANDROID_SDK_ROOT
```

Output: `build/release/nova-calibration.apk`. A local test build is non-debuggable and has the same component/permission restrictions as the normal release. Its signing identity is for local testing and must not be mistaken for the project's public release identity.

## Release signing

Use a private signing key stored outside the repository. The build refuses a normal release unless the key path, alias, and password environment variables are supplied. Keep the same key for subsequent public APK updates and back it up privately.

```powershell
# Set NOVA_STORE_PASSWORD and NOVA_KEY_PASSWORD in your local environment.
# Do not put secrets into a script, commit, or public shell transcript.
.\build.ps1 -KeyStore 'C:\private\nova-release.jks' -KeyAlias 'nova-release'
python tools/audit-apk.py --sdk $env:ANDROID_SDK_ROOT
Get-FileHash build/release/nova-calibration.apk -Algorithm SHA256
```

Release assets should include the signed APK and its SHA-256 file. Record the public signing certificate SHA-256 in the release notes. Signing changes the APK identity, not the calibrated profile bytes. Android does not accept an update signed with an unrelated key. A user moving from a laboratory signature must first restore the original profile and reboot, then uninstall that APK before installing the public build.

## Optional exact reconstruction check

The original manufacturer configuration is intentionally absent from the repository and APK. If you own the exact supported firmware and have obtained its original configuration locally, the host test can verify both outputs against the frozen measured hashes:

```powershell
.\test.ps1 -FactoryJson 'C:\private\panel-factory.json'
```

Do not commit that file. The `private/` and known firmware-output patterns are ignored as an additional guard.

`tools/generate-profile-patches.py` regenerates both deltas from that private factory input and the published measured LUTs/matrix. Full reconstructed firmware data exist only in memory. The generator validates the exact output hashes, permitted SDR changes, and literal content. Running it is not necessary for normal app builds.

```text
python tools/generate-profile-patches.py --factory C:\private\panel-factory.json
```

## What was validated

- Production decoder: valid byte-exact reconstruction, real private profile fixtures, malformed inputs, truncated data, bounds, unexpected fields/opcodes, and wrong checksums.
- Compiled APK: zero permissions; one launcher; no services, receivers, providers, network clients or native libraries; only the expected calibration assets; complete localization keys/placeholders.
- Actual device: profile changes through the normal app UI, full reboot, original-profile restore, brightness preservation on repeated selection, and persistence with the app force-stopped through sleep/wake. See the current [device report](verification/device-validation.json).
- Earlier optical campaign: 921 recorded readings. Version 0.4 reconstructs the same profile bytes; packaging changes do not constitute new optical readings.

[APK inspection](verification/apk-audit.json) · [Decoder test log](verification/profile-reconstruction-tests.txt) · [Measurement report](MEASUREMENTS.md)

The source tree deliberately excludes historical device probes and research binaries. Hardware readback in the laboratory used separately staged diagnostic tools, removed after the checks; those tools are not present in the normal APK.
