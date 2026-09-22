# Saturation setup and troubleshooting

If version 1.0.1 reports **Could not verify the display color settings** and the operation details include `printf: Argument list too long`, update to **1.0.2**. This was an app bug when Android returned a large display diagnostic report, even with saturation correctly set to 100%. Install the update over the existing app, select your profile, and restart if requested. No OdinTools reinstall or manual saturation reset is needed.

Version 1.0.1 replaces the misleading “Set OdinTools saturation to 100%” rejection with a checked, optional reset inside Nova Calibration. OdinTools is not required.

1. Install the update over version 1.0.0 and select Gamma 2.2 or sRGB.
2. If prompted, choose **Reset and apply**. This sets Android's current and saved global saturation to 100%, verifies the internal display's color transform, and then saves the selected profile.
3. Restart when the app asks you to.

**Cancel** leaves saturation and the profile unchanged. Resetting saturation is a separate, explicit adjustment: **Restore original** removes the panel calibration but keeps saturation at 100%. It does not restore an earlier OdinTools preference.

If another color adjustment remains active after the reset, profile installation stops. Disable night-light or other color filters and try again. Nova Calibration does not disable accessibility settings or change OdinTools preferences. A setting or another app can still change colors later; Nova Calibration does not monitor or override them in the background.

## Why the old check could disagree with OdinTools

OdinTools can set the current SurfaceFlinger saturation without updating `persist.sys.sf.color_saturation`. Version 1.0.0 checked only that saved property, accepted a small set of textual representations of one, and attributed every other value to OdinTools. It did not report the rejected value.

There is also a startup detail: Android 13's color service compares the saved property with the exact string `1.0` when inferring natural versus boosted mode. An empty value or a numerically equivalent value such as `1.000` can therefore select a boost at restart. On the test Nova, an empty property with a neutral live transform reproduced the old rejection; rebooting with that empty property selected `1.1` and changed the live transform. Simply ignoring the property check would not be a complete fix.

Sources: [OdinTools saturation setter](https://github.com/langerhans/OdinTools/blob/main/app/src/main/java/de/langerhans/odintools/tools/SettingsRepo.kt), [Android 13 startup color-mode inference](https://github.com/aosp-mirror/platform_frameworks_base/blob/android13-release/services/core/java/com/android/server/display/color/ColorDisplayService.java), and [Android 13 saturation setter](https://github.com/aosp-mirror/platform_frameworks_base/blob/android13-release/services/core/java/com/android/server/display/color/DisplayTransformManager.java).

## What the new check does

- Reads and reports the saved saturation separately from the internal display's active compositor transform.
- Requires the canonical saved value `1.0` and a neutral live transform before saving a calibration.
- Offers a user-confirmed reset instead of asking users to reinstall OdinTools. The reset uses the existing OEM service to set the persistent saturation property and SurfaceFlinger saturation to `1.0`.
- Leaves the measured profile bytes, firmware checks, ownership checks, permissions and app lifecycle unchanged.
- Keeps **Restore original** available even if the saturation check fails.

The live check uses the internal display's matrix in a bounded SurfaceFlinger dump. That dump rounds components to three decimal places; it is a software safeguard, not an optical measurement or a guarantee against later changes by another app. External display and individual layer matrices are not substituted for the internal display's state. Missing, ambiguous or malformed output stops installation.

Starting with 1.0.2, the report is streamed into the parser instead of passed as a large command argument. Only one length-limited internal matrix is retained. A completion marker distinguishes a successful report from partial output followed by an error or timeout; failed reports remain blocked.

Press and hold the status text for operation details. The log reports `NOVA_SATURATION_PERSISTENT`, `NOVA_SATURATION_PERSISTENT_STATUS` and `NOVA_COLOR_TRANSFORM_STATUS`. It does not save the full SurfaceFlinger dump or unrelated application details.
