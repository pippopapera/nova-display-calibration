"""Run the production installer with synthetic files and mocked Android commands.

Uses real shell/cp/mv/hash operations. Does not replace Android/device testing.
No manufacturer configuration, private logs, or device identifiers are required.
"""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SHELL = (r"C:\Program Files\Git\bin\bash.exe" if os.name == "nt"
         else shutil.which("sh"))
IDENTITY = "[[1.000,0.000,0.000,0.000][0.000,1.000,0.000,0.000][0.000,0.000,1.000,0.000][0.000,0.000,0.000,1.000]]"
MODIFIED = IDENTITY.replace("1.000", "0.800", 1)


def posix(path):
    value = str(path.resolve()).replace("\\", "/")
    return "/" + value[0].lower() + value[2:] if os.name == "nt" else value


def digest(data):
    return hashlib.sha256(data).hexdigest()


def display(matrix=IDENTITY, internal=True):
    return ("Display 1 (physical, \"\")\n   Composition Display State:\n"
            "   layerFilter={layerStack=0 toInternalDisplay="
            + str(internal).lower() + " }\n   colorTransformMatrix=" + matrix + " \n")


class InstallerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="nova-installer-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for path in ("display", "settings", "bin"):
            (self.root / path).mkdir()
        self.put("factory", "synthetic factory")
        self.put("build", "RPN_V1.0.0.436_20260722_083524_user")
        self.put("boot", "boot-A")
        self.put("saturation", "1.0")
        self.put("surfaceflinger", display())
        self.put("identity", display())
        self.put("settings/screen_brightness", "165")
        self.put("settings/screen_brightness_mode", "1")
        self.put("nova-profile-system.json", "synthetic gamma22")
        script = (ROOT / "sourceprofiles/nova-system-profile.sh").read_text(encoding="utf8")
        substitutions = {
            "/data/vendor/display/qdcm_calib_data_il97680a_amoled_panel_without_DSC.json": "display/profile",
            "/data/vendor/display/nova-calibration-state-v1": "display/state",
            "/data/vendor/display/nova-calibration-lock": "lock",
            "/vendor/etc/display/qdcm_calib_data_il97680a_amoled_panel_without_DSC.json": "factory",
            "/proc/sys/kernel/random/boot_id": "boot",
        }
        for old, relative in substitutions.items():
            self.assertIn(old, script)
            script = script.replace(old, posix(self.root / relative))
        for key, data in (("FACTORY_HASH", b"synthetic factory"),
                          ("GAMMA", b"synthetic gamma22"), ("SRGB", b"synthetic srgb")):
            import re
            script, count = re.subn(r"(?m)^" + key + r"=[0-9a-f]{64}$",
                                    key + "=" + digest(data), script)
            self.assertEqual(count, 1)
        self.put("installer.sh", script)
        mocks = {
            "id": "echo 0",
            "getprop": 'case "$1" in ro.build.display.id) cat "$FIXTURE/build";; persist.sys.sf.color_saturation) [ ! -f "$FIXTURE/fail-getprop" ] || exit 1; cat "$FIXTURE/saturation";; *) exit 1;; esac',
            "dumpsys": '[ "$1" = -t ] && [ "$2" = 5 ] && [ "$3" = SurfaceFlinger ] || exit 1\n[ ! -f "$FIXTURE/fail-dumpsys" ] || exit 1\ncat "$FIXTURE/surfaceflinger"',
            "setprop": '[ "$1" = persist.sys.sf.color_saturation ] && [ "$2" = 1.0 ] || exit 1\n[ ! -f "$FIXTURE/fail-setprop" ] || exit 1\nprintf "%s" "$2" > "$FIXTURE/saturation"',
            "service": '[ "$*" = "call SurfaceFlinger 1022 f 1.0" ] || exit 1\n[ ! -f "$FIXTURE/fail-service" ] || exit 1\n[ -f "$FIXTURE/keep-filter" ] || /bin/cp "$FIXTURE/identity" "$FIXTURE/surfaceflinger"\necho "Result: Parcel(NULL)"',
            "sleep": "exit 0",
            "settings": 'case "$1" in get) cat "$FIXTURE/settings/$3";; put) printf "%s" "$4" > "$FIXTURE/settings/$3";; *) exit 1;; esac',
            "chown": "exit 0", "chmod": "exit 0", "restorecon": "exit 0", "sync": "exit 0",
            "mv": 'if [ -f "$FIXTURE/fail-move" ] && [ "$2" = "$FIXTURE/display/state" ]; then exit 1; fi\n/bin/mv "$@"',
        }
        for name, body in mocks.items():
            self.put("bin/" + name, "#!/bin/sh\n" + body + "\n")
            (self.root / "bin" / name).chmod(0o755)

    def put(self, name, value):
        (self.root / name).write_text(value, encoding="utf8", newline="\n")

    def run_installer(self, *args, error=None, rollback=False):
        before = self.snapshot()
        env = dict(os.environ, FIXTURE=posix(self.root), MSYS_NO_PATHCONV="1")
        result = subprocess.run([SHELL, "-c", 'export PATH="$FIXTURE/bin:/usr/bin:/bin"; exec /bin/sh "$@"',
                                 "fixture", posix(self.root / "installer.sh"), *args],
                                env=env, capture_output=True, text=True, encoding="utf8", timeout=20)
        if error or rollback:
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("NOVA_NATIVE_WRITE_ROLLED_BACK=1" if rollback else "NOVA_NATIVE_ERROR=" + error, result.stdout)
            self.assertEqual(before, self.snapshot(), "A rejected operation changed profile/state/brightness")
        else:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse((self.root / "lock").exists())
        return result.stdout

    def snapshot(self):
        return {name: (self.root / name).read_bytes() if (self.root / name).exists() else None
                for name in ("display/profile", "display/state", "settings/screen_brightness",
                             "settings/screen_brightness_mode")}

    def test_default_and_equivalent_neutral_values(self):
        for value in ("", "1", "1.0", "1.00", "1.000", "1.0000000", "+1", "01.000", "1e0", "10e-1"):
            with self.subTest(value=value):
                self.put("saturation", value)
                if value != "1.0":
                    out = self.run_installer("apply", "gamma22", error="saturation_setup_required")
                    self.assertIn("NOVA_SATURATION_PERSISTENT=" + (value or "<default:1.0>"), out)
                    self.assertEqual((self.root / "saturation").read_text(), value)
                    self.assertIn("NOVA_NATIVE_SATURATION_RESET=1", self.run_installer("reset-saturation"))
                out = self.run_installer("apply", "gamma22")
                self.assertIn("NOVA_NATIVE_CONFIG_SAVED=1", out)
                self.assertIn("NOVA_COLOR_TRANSFORM_STATUS=identity", out)
                self.assertEqual((self.root / "saturation").read_text(), "1.0")

    def test_non_neutral_startup_values_rejected_even_with_identity(self):
        for value in ("0.8", "0", "1.1", "1.00001", "1e3"):
            with self.subTest(value=value):
                self.put("saturation", value)
                self.run_installer("apply", "gamma22", error="saturation_setup_required")
                self.run_installer("reset-saturation")
                self.run_installer("apply", "gamma22")

    def test_invalid_property_and_read_failure(self):
        for value in ("NaN", "inf", "1garbage", "1\nNOVA_NATIVE_CONFIG_SAVED=1", "$(touch bad)"):
            with self.subTest(value=value):
                self.put("saturation", value)
                out = self.run_installer("apply", "gamma22", error="color_state_unavailable")
                self.assertNotIn("NOVA_NATIVE_CONFIG_SAVED=1", out)
        self.put("fail-getprop", "")
        self.put("saturation", "")
        self.run_installer("apply", "gamma22", error="color_state_unavailable")

    def test_live_adjustment_rejected_with_neutral_or_default_startup(self):
        self.put("surfaceflinger", display(MODIFIED))
        for value in ("", "1.0"):
            with self.subTest(value=value):
                self.put("saturation", value)
                self.run_installer("apply", "gamma22", error="color_transform_active" if value else "saturation_setup_required")

    def test_reset_is_explicit_and_preserves_profile_state_and_brightness(self):
        self.run_installer("apply", "gamma22")
        before = self.snapshot()
        self.put("saturation", "1.1")
        self.put("surfaceflinger", display(MODIFIED))
        self.run_installer("apply", "gamma22", error="saturation_setup_required")
        self.assertEqual((self.root / "saturation").read_text(), "1.1")
        self.run_installer("reset-saturation")
        self.assertEqual(self.snapshot(), before)
        self.assertEqual((self.root / "saturation").read_text(), "1.0")
        self.assertEqual((self.root / "surfaceflinger").read_text(), display())

    def test_reset_errors_and_remaining_filters_never_apply_a_profile(self):
        self.put("saturation", "1.1")
        self.put("fail-setprop", "")
        self.run_installer("reset-saturation", error="saturation_reset_failed")
        self.assertEqual((self.root / "saturation").read_text(), "1.1")
        (self.root / "fail-setprop").unlink()
        self.put("fail-service", "")
        self.run_installer("reset-saturation", error="saturation_reset_failed")
        (self.root / "fail-service").unlink()
        self.put("keep-filter", "")
        self.put("surfaceflinger", display(MODIFIED))
        self.run_installer("reset-saturation", error="color_transform_active")
        self.assertIsNone(self.snapshot()["display/profile"])
        self.put("build", "future-firmware")
        self.put("saturation", "1.1")
        self.run_installer("reset-saturation", error="firmware_mismatch")
        self.assertEqual((self.root / "saturation").read_text(), "1.1")

    def test_external_display_and_layer_do_not_override_internal_check(self):
        self.put("surfaceflinger", "layer colorTransformMatrix=" + MODIFIED + "\n" +
                 display() + display(MODIFIED, internal=False))
        self.run_installer("apply", "gamma22")
        self.put("surfaceflinger", display(MODIFIED) + display(internal=False))
        self.run_installer("apply", "gamma22", error="color_transform_active")

    def test_unreadable_missing_duplicate_and_malformed_transform_fail_closed(self):
        for text in ("", display(internal=False), display() + display(),
                     display("[[1,0,0]]"), display(IDENTITY + "junk"),
                     display(IDENTITY.replace("1.000", "NaN", 1))):
            with self.subTest(text=text):
                self.put("surfaceflinger", text)
                self.run_installer("apply", "gamma22", error="color_state_unavailable")
        self.put("surfaceflinger", display())
        self.put("fail-dumpsys", "")
        self.run_installer("apply", "gamma22", error="color_state_unavailable")

    def test_status_and_restore_remain_available_when_color_check_fails(self):
        self.run_installer("apply", "gamma22")
        self.put("saturation", "0.8")
        self.put("surfaceflinger", display(MODIFIED))
        out = self.run_installer("status")
        self.assertIn("NOVA_NATIVE_STATUS_OK=1", out)
        self.assertIn("NOVA_SATURATION_PERSISTENT=0.8", out)
        self.assertIn("NOVA_COLOR_TRANSFORM_STATUS=modified", out)
        self.put("build", "future-firmware")
        self.run_installer("restore")
        self.assertIsNone(self.snapshot()["display/profile"])
        self.assertEqual(self.snapshot()["settings/screen_brightness"], b"165")
        self.assertEqual(self.snapshot()["settings/screen_brightness_mode"], b"1")

    def test_transaction_and_guards(self):
        self.run_installer("apply", "gamma22")
        self.put("boot", "boot-B")
        self.put("settings/screen_brightness", "103")
        before = self.snapshot()
        out = self.run_installer("apply", "gamma22")
        self.assertIn("NOVA_NATIVE_UNCHANGED=1", out)
        self.assertEqual(self.snapshot(), before)
        self.put("nova-profile-system.json", "corrupt")
        self.run_installer("apply", "srgb", error="payload_hash")
        self.put("nova-profile-system.json", "synthetic srgb")
        self.put("fail-move", "")
        self.run_installer("apply", "srgb", rollback=True)
        self.assertEqual(before, self.snapshot())
        self.assertFalse((self.root / "lock").exists())
        (self.root / "fail-move").unlink()
        self.run_installer("apply", "srgb")
        self.assertEqual(self.snapshot()["display/profile"], b"synthetic srgb")
        self.put("build", "future-firmware")
        self.run_installer("apply", "srgb", error="firmware_mismatch")
        self.put("build", "RPN_V1.0.0.436_20260722_083524_user")
        self.put("factory", "unexpected factory")
        self.run_installer("apply", "srgb", error="firmware_mismatch")
        self.put("factory", "synthetic factory")
        self.put("display/profile", "foreign override")
        self.run_installer("restore", error="foreign_override")


if __name__ == "__main__":
    unittest.main(verbosity=2)
