# Third-party notes

The normal APK has no bundled third-party runtime library or native library. It uses the Android framework APIs available on the device.

- **ArgyllCMS** was used externally on the PC to collect measurements with `spotread`. No ArgyllCMS executable or source is included in the APK or this repository. [ArgyllCMS spotread documentation](https://www.argyllcms.com/doc/spotread.html).
- **X-Rite i1Display Pro Plus EODIS3PL** was the measurement instrument. The measurement correction was the generic OLED Family 2018 CCSS; its identity/checksum is recorded, but its spectral file is not redistributed here.
- **OdinTools** documented use of Retroid's existing OEM Binder service. No OdinTools source or UI is bundled. [Protocol reference](https://github.com/langerhans/OdinTools/blob/main/app/src/main/java/de/langerhans/odintools/tools/ShellExecutor.kt).
- **Qualcomm/Retroid firmware** provides the QDCM configuration, display compositor, and privileged service. Full factory/reconstructed JSON files, firmware binaries, disassemblies, and kernel modules are excluded. Calibration deltas reconstruct an override from the configuration already present on the user's supported device. Manufacturer data retain their original ownership and notices on-device.
- **Nintendo game artwork** appears in owner-supplied photographs solely to illustrate the display comparison. Game artwork and related trademarks remain the property of their respective owners and are not licensed under this project's MIT license. No game ROMs or extracted game assets are included.
- **JDK and Android SDK/build-tools** are external build dependencies governed by their upstream terms. They are not distributed in the repository.

The app's Frutiger Aero graphics are drawn with code using Android canvas/drawables; no external wallpaper or icon asset is bundled.
