# Maintainer publication notes

The repository content is prepared locally. No GitHub repository, push, or public release was created by the preparation workflow.

Suggested repository name: **nova-display-calibration**

Suggested description:

> Measured SDR display calibration for Retroid Pocket Nova, with native profile persistence and no background service.

Create an empty repository under `pippopapera`. Leave GitHub's README, `.gitignore`, and license initialization options off because the local repository supplies them. MIT is the prepared license for project code and original calibration data; review it before publishing. Third-party artwork in photos is excluded from that grant.

## Source publication

1. Review the README, license, compatibility limits, and comparison images.
2. Add the newly created repository URL as the local `origin` remote.
3. Push the local `main` branch using the owner's authenticated Git setup.

No account credentials, private key, firmware copy, device serial, GPS metadata, personal-summary file, or test APK belongs in the commit.

## Signed APK release

1. Create or choose a dedicated private signing key outside the repository and back it up securely.
2. Build using the external signing parameters in [BUILDING.md](../docs/BUILDING.md), then rerun the compiled-APK audit.
3. Verify the APK/certificate digests and install the signed artifact for a final smoke test. The local lab installation uses a different key: restore the original profile and reboot before replacing it.
4. Update the signing status in the verification/release records. Attach the signed APK and `SHA256SUMS.txt` to release **v0.4.0**, using [the prepared release notes](v0.4.0.md).
5. Make the release public only after those artifact checks. The current `-TestBuild` APK is a candidate, not the public distribution.

The signature affects APK updates and publisher identity; it does not alter calibration data. Keep the measured profile hashes unchanged unless a new measurement campaign justifies a change.
