# Changelog

## 0.4.0 — 2026-09-13

- Package calibration-only deltas and reconstruct the verified measured profiles from the console's existing factory configuration. No manufacturer configuration is bundled.
- Preserve the native system-managed Gamma 2.2 and sRGB profiles measured in the laboratory.
- Keep the six-language interface, zero declared permissions, and absence of background services.
- Add public English documentation, camera comparison, raw measurement exports, decoder tests, and compiled-APK checks.
- Require an explicit private signing key for release builds; local test signing must be requested with `-TestBuild`.
- Establish the dedicated public signing identity and verify the signed APK on the supported device.

## 0.3 — local laboratory build

- Added automatic English, Italian, German, Spanish, French, and Portuguese resources.
- Separated the normal app from diagnostic activities, probes, and the previous wake service.
- Validated native persistence, original restore, locale behavior, private staging, and installer guards.

## Earlier laboratory work

Measured the panel, fitted SDR gamut/white-point/tone corrections, compared Gamma 2.2 with sRGB, and validated brightness steps and native compositor persistence. These builds were not public releases.
