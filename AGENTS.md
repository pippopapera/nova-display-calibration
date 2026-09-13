# Project guidance

- Keep public documentation in English. Preserve the app's supported translations.
- This repository contains only the distributable app, original calibration deltas/data, and curated evidence. Never add factory/reconstructed QDCM JSON files, firmware binaries, private device logs, signing keys, personal profile summaries, or credentials.
- The installer is privileged and firmware-specific. Preserve hash, ownership, saturation, and compatibility checks. Do not support new hardware by weakening these checks.
- Keep calibration independent of app services, wake receivers, polling, overlays, and permanent notifications.
- The measured profile hashes are frozen. A change in optical output requires new measurements and updated evidence, not just a successful build.
- Run `test.ps1` and `tools/audit-apk.py` for packaging/decoder changes. Privileged installation changes additionally require real-device apply/reboot/restore tests.
- Distinguish host tests, actual-device verification, optical measurements, and untested claims. Do not describe camera photographs as quantitative evidence.
- A public release needs a private release signing key and verified APK/certificate digests. Never publish a `-TestBuild` APK as the official release.
