# Maintainer publication notes

Repository: [pippopapera/nova-display-calibration](https://github.com/pippopapera/nova-display-calibration).

This directory contains the APK release procedure and release notes. The current public signed APK is [v1.0.0](https://github.com/pippopapera/nova-display-calibration/releases/tag/v1.0.0). Its certificate fingerprint is recorded in [the release notes](v1.0.0.md) and [signature verification output](../docs/verification/release-signing-certificate.txt).

The project code and original calibration data use MIT. Third-party artwork in the comparison photographs is excluded from that grant.

## Source publication

1. Review source changes, public documentation, compatibility limits, and validation evidence.
2. Commit the reviewed files, keeping private artifacts excluded.
3. Push `main` to the repository above using the owner's authenticated Git setup.

No account credentials, private key, firmware copy, device serial, GPS metadata, personal-summary file, or test APK belongs in the commit.

## Signed APK release

1. Use the established private release signing key outside the repository and keep a secure backup. Do not generate a new identity for a routine update.
2. Build using the external signing parameters in [BUILDING.md](../docs/BUILDING.md), then rerun the compiled-APK audit.
3. Verify the APK/certificate digests and install the signed artifact for a final smoke test. When migrating from a different laboratory key, restore the original profile and reboot before uninstalling the old APK.
4. Update verification records and release notes for the new version. Attach the signed APK and `SHA256SUMS.txt` to its tagged release. See [v1.0.0](v1.0.0.md) for the initial format.
5. Make the release public only after those artifact checks. Never upload a `-TestBuild` APK as an official release.

The signature affects APK updates and publisher identity; it does not alter calibration data. Keep the measured profile hashes unchanged unless a new measurement campaign justifies a change.
