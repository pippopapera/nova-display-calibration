# How to contribute

Independent measurements and reproducible bug reports are welcome.

For application bugs, include the exact firmware build, app version, selected profile, brightness, reproduction steps, and relevant status-log text. State whether the issue remains after restoring the original profile and restarting. Avoid posting device identifiers, location data, credentials, or full firmware files.

For measurements, report the instrument and spectral correction, patch geometry, brightness, active profile, system saturation, refresh rate, and raw RGB/XYZ readings. A camera photograph is useful context but cannot replace an instrument measurement. Keep captured examples unretouched and identify any changes to metadata or framing.

Do not expand compatibility by changing a firmware guard alone. A new device/firmware needs examination of its display configuration, reversible installation checks, and optical verification. Preserve factory HDR/P3 settings unless their calibration is explicitly part of the work.

Run the production decoder tests and compiled-APK audit for code affecting installation or packaging. Changes involving privileged writes also need actual-device apply/reboot/restore verification. Explain what was tested and what remains untested.

Repository documentation and development discussions should be in English. Android translations remain in their respective resource directories. AI-assisted contributions are welcome when the contributor reviews the result and provides appropriate verification; generated output alone is not evidence of correctness.
