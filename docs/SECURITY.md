# Privileged access, system changes, and recovery

## What the app can do

The compiled application declares no Android permissions and contains one exported launcher activity. It does not accept profile commands from launch intent extras. There are no exported services or receivers, camera APIs, telemetry clients, shared-storage staging, or ongoing background jobs.

The firmware nevertheless exposes the privileged `PServerBinder` service. The installer uses that existing service for fixed profile/status/restore operations and the user-requested restart. It verifies root identity before relying on it. This is privileged system access, even though Android does not show a runtime permission dialog for it.

The UI accepts fixed profile IDs; shell actions and staging filenames are allowlisted. The stage is app-private, symlinks and foreign overrides are rejected, and the full reconstructed profile is checksummed before use. Obscured button touches are filtered. These safeguards reduce the app's exposed surface; they are not a certification of the underlying firmware.

## What was changed on the tested console

- A profile override and an ownership/state record under `/data/vendor/display/`.
- Reference brightness on initial installation or profile change, with prior brightness saved for restore.
- The Nova Calibration APK and its private data.

The project did not patch `/vendor`, the kernel, the bootloader, or SELinux policy, and did not install Magisk or another root manager. The test console already had an unlocked bootloader. Original factory configuration checksums were retained.

The tested firmware's OEM `pservice` sets SELinux permissive during its own startup. This was found in the existing binary and boot configuration; it was not introduced by the calibration. Repairing that manufacturer behavior is outside this app's scope. The app does not require users to change SELinux settings. This statement applies to the tested firmware, not to every Retroid device or future update.

## Recovery and removal

For normal removal, open the app, select **Restore original**, then **Restart device**. Do this **before uninstalling the APK or updating firmware**. Uninstalling an app does not remove a file it previously installed in the system display data directory.

If the app reports an existing foreign profile, incompatible firmware, unavailable status, or an incomplete operation, use the long-press status log to identify the failure. Preserve the log and the exact firmware version. Do not overwrite another tool's profile, bypass a checksum, or delete an unknown lock/state file blindly.

The installer backs up its previous owned state for rollback on handled errors. It has not been proven recoverable after power loss at every individual write boundary. A stale lock or interrupted transaction may need case-specific assistance.

## Reporting

For ordinary bugs, use the repository issue tracker and provide firmware, app version, selected profile, reproduction steps, and the relevant error text. Remove personal information from logs before sharing them. For a security issue, use GitHub's private vulnerability reporting if the repository has it enabled; otherwise request a private contact channel without posting exploit details publicly.

No independent penetration test or quantitative FPS/battery benchmark is claimed. Reviewable source, bounded host tests, compiled-APK inspection, actual-device installation/reboot/restore checks, and optical measurements provide the current evidence.
