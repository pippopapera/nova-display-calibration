# Architecture

## Scope

This is a firmware-specific profile installer. It applies two fixed SDR calibrations, not a monitor ICC profile and not a generic saturation adjustment. Package identity remains `local.nova.diagnostic` for continuity with local builds; the public application contains no diagnostic components.

The normal build compiles three Java classes plus Android's generated resource class:

| Component | Responsibility |
|---|---|
| `CalibrationActivity` | Localized UI, fixed profile operations, private staging, OEM Binder transport |
| `ProfilePayload` | Bounded reconstruction from verified on-device factory bytes and calibration deltas |
| `AeroTheme` | Static vector drawing for the Frutiger Aero interface |
| `nova-system-profile.sh` | Firmware and ownership guards, serialized install/restore, saved state |

There are no app services, broadcast receivers, native libraries, analytics SDKs, or network clients. Opening the activity queries status once; selecting a button runs one operation on a single worker. A running worker is not a persistent calibration service.

## From measurement to light

The reference target is sRGB primaries, D65, and either Gamma 2.2 or the sRGB transfer function. IGC and GC tables implement the tone correction, and a measured 3×3 PCC matrix corrects gamut and white point. The global PA adjustment is neutral. The retained `surfaceflinger_matrix` field in the profile manifest is a historical name for the coefficients; the native implementation encodes them in PCC, leaving SurfaceFlinger at identity.

The original configuration is read from:

```text
/vendor/etc/display/qdcm_calib_data_il97680a_amoled_panel_without_DSC.json
```

The generated override and its state record are:

```text
/data/vendor/display/qdcm_calib_data_il97680a_amoled_panel_without_DSC.json
/data/vendor/display/nova-calibration-state-v1
```

The original `/vendor` file is never replaced. The compositor chooses the override at boot and loads its data into the display pipeline. Removing the owned override and restarting restores the factory configuration.

## Calibration-only distribution

The original laboratory APK bundled full reconstructed QDCM JSON files. The public app distributes only binary `.npatch` deltas, avoiding redistribution of the manufacturer's configuration.

The `NOVAPCH1` format is deliberately narrow. It addresses eight known fields in this exact, checksummed firmware file: IGC, GC, PCC and PA in NATIVE and sRGB. Each field is decoded from its byte-swapped hexadecimal representation. Bounded operations either copy existing bytes or insert original calibration values. The field is then re-encoded. The verified factory formatting is normalized to the frozen measured output's formatting, including its CRLF line endings.

The generator checks that literal additions contain only numeric values, JSON punctuation, and boolean/null tokens. Unchanged OEM strings and data are copied at runtime. The source tree also includes the measured IGC/GC binaries and coefficients for inspection; the APK only bundles the deltas, manifest, and installer.

Both generated outputs must match these frozen SHA-256 digests:

| Input/output | SHA-256 |
|---|---|
| Factory input | `26a56de100c2a27ecac2fe7ed4d30ba5bcb935865c7470775dcfda744d0173af` |
| Gamma 2.2 output | `c6e20079ef58220960e9511d5641bc02f1bcfb0f90c5bb0a842e5b2ed00c9afd` |
| sRGB output | `8be527253edcc712006e73a26cbadd4f3aa1ade3bd8d2d00ae38587b3f393b25` |

These match the previously measured profiles byte for byte. A failed checksum prevents installation. The Java reconstruction code is tested directly on the host, including malformed/truncated patches and actual private firmware fixtures.

## Install lifecycle

1. Verify the fixed profile choice and reconstruct its expected bytes from the checked factory file.
2. Stage the installer and payload under the app's private files directory, using directory mode 0700 and file mode 0600.
3. Use the firmware's existing privileged service to invoke the fixed installer operation.
4. Acquire the installation lock before reading saved state. Check firmware, factory digest, payload digest, saturation, and ownership of an existing override.
5. Preserve brightness settings, replace the owned files, set the reference brightness, and request a full restart.
6. After boot, report the persisted configuration. The display system maintains it without the app.

Repeated selection of the configured profile preserves brightness and does not create a new restart requirement. Restore is limited to a recognized profile owned by this app. It remains available after a firmware mismatch so the old owned override can be removed.

## Limits of the safeguards

Each file replacement is atomic, but the override and state are separate files. Handled errors trigger rollback; arbitrary power loss or process termination between writes is not a proven crash-atomic transaction. An interrupted operation can leave a stale lock or inconsistent state requiring recovery. Do not remove unknown files to bypass these checks.

Firmware compatibility is intentionally narrow. Supporting a new firmware requires examining its actual configuration and validating the display output again; changing only the build string or checksum bypasses that work.
