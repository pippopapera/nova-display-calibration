# Maintainer publication notes

Repository: [pippopapera/nova-display-calibration](https://github.com/pippopapera/nova-display-calibration).

This directory contains the APK release procedure and prepared release notes. The source repository and a downloadable, signed APK are separate publication steps. The first signed APK release is still being prepared.

The project code and original calibration data use MIT. Third-party artwork in the comparison photographs is excluded from that grant.

## Source publication

1. Review source changes, public documentation, compatibility limits, and validation evidence.
2. Commit the reviewed files, keeping private artifacts excluded.
3. Push `main` to the repository above using the owner's authenticated Git setup.

No account credentials, private key, firmware copy, device serial, GPS metadata, personal-summary file, or test APK belongs in the commit.

## Signed APK release

1. Create or choose a dedicated private signing key outside the repository and back it up securely.
2. Build using the external signing parameters in [BUILDING.md](../docs/BUILDING.md), then rerun the compiled-APK audit.
3. Verify the APK/certificate digests and install the signed artifact for a final smoke test. The local lab installation uses a different key: restore the original profile and reboot before replacing it.
4. Update the signing status in the verification/release records. Attach the signed APK and `SHA256SUMS.txt` to release **v0.4.0**, using [the prepared release notes](v0.4.0.md).
5. Make the release public only after those artifact checks. The current `-TestBuild` APK is a candidate, not the public distribution.

The signature affects APK updates and publisher identity; it does not alter calibration data. Keep the measured profile hashes unchanged unless a new measurement campaign justifies a change.
