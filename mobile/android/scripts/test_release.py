"""Signing setup must preserve app identity and never leave plaintext credentials."""
import importlib.util
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("murmur_release", Path(__file__).with_name("release.py"))
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class SigningTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.home = Path(self.temporary.name)
        self.directory = self.home / ".local/share/murmur-android"
        self.key = self.directory / "release.p12"
        self.environment = {"JAVA_HOME": "/jdk21", "PATH": "/bin"}

    def existing_key(self):
        self.directory.mkdir(parents=True)
        self.key.write_bytes(b"original identity")

    def test_unknown_existing_key_is_never_replaced(self):
        self.existing_key()
        with patch.object(release, "keychain_password", return_value=None), patch.object(release, "quiet") as command:
            with self.assertRaises(release.ReleaseError):
                release.signing_environment(self.environment, "darwin", self.home)
            command.assert_not_called()
        self.assertEqual(b"original identity", self.key.read_bytes())
        self.assertFalse((self.directory / ".signing.lock").exists())

    def test_missing_key_with_existing_credential_requires_restore(self):
        with patch.object(release, "keychain_password", return_value="existing password"), patch.object(release, "quiet") as command:
            with self.assertRaises(release.ReleaseError):
                release.signing_environment(self.environment, "darwin", self.home)
            command.assert_not_called()
        self.assertFalse(self.key.exists())

    def test_key_and_keychain_are_reused_without_generation(self):
        self.existing_key()
        with patch.object(release, "keychain_password", return_value="password"), patch.object(release, "validate_keystore") as validate, patch.object(release, "quiet") as command:
            env, state = release.signing_environment(self.environment, "darwin", self.home)
            self.assertEqual("reused", state)
            self.assertEqual("password", env["MURMUR_STORE_PASSWORD"])
            self.assertEqual("murmur", env["MURMUR_KEY_ALIAS"])
            command.assert_not_called()
            validate.assert_called_once()
        self.assertEqual(b"original identity", self.key.read_bytes())
        self.assertEqual(0o600, stat.S_IMODE(self.key.stat().st_mode))

    def test_new_key_uses_env_password_and_only_keychain_stores_it(self):
        secrets = []
        def command(args, env=None):
            if "-genkeypair" in args:
                self.assertIn("-storepass:env", args)
                self.assertNotIn(env["MURMUR_STORE_PASSWORD"], args)
                self.assertGreaterEqual(len(env["MURMUR_STORE_PASSWORD"]), 48)
                secrets.append(env["MURMUR_STORE_PASSWORD"])
                Path(args[args.index("-keystore") + 1]).write_bytes(b"generated key")
            elif "add-generic-password" in args:
                self.assertEqual(secrets[0], args[args.index("-w") + 1])
                self.assertNotIn("-U", args)
            else:
                self.fail("Unexpected credential operation")
            return subprocess.CompletedProcess(args, 0, stdout="", stderr="")
        with patch.object(release, "keychain_password", return_value=None), patch.object(release, "quiet", side_effect=command), patch.object(release, "validate_keystore"):
            env, state = release.signing_environment(self.environment, "darwin", self.home)
        self.assertEqual("created", state)
        self.assertEqual("generated key", self.key.read_text())
        self.assertEqual(secrets[0], env["MURMUR_KEY_PASSWORD"])
        self.assertEqual(["release.p12"], [entry.name for entry in self.directory.iterdir()])
        self.assertEqual(0o600, stat.S_IMODE(self.key.stat().st_mode))
        self.assertEqual(0o700, stat.S_IMODE(self.directory.stat().st_mode))

    def test_failed_keychain_store_removes_only_its_temporary_key(self):
        def command(args, env=None):
            if "-genkeypair" in args:
                Path(args[args.index("-keystore") + 1]).write_bytes(b"temporary key")
                return subprocess.CompletedProcess(args, 0, stdout="", stderr="")
            self.assertIn("add-generic-password", args)
            return subprocess.CompletedProcess(args, 1, stdout="", stderr="")
        with patch.object(release, "keychain_password", return_value=None), patch.object(release, "quiet", side_effect=command):
            with self.assertRaises(release.ReleaseError):
                release.signing_environment(self.environment, "darwin", self.home)
        self.assertEqual([], list(self.directory.iterdir()))

    def test_explicit_keystore_is_validated_without_any_keychain_operation(self):
        self.existing_key()
        env = dict(self.environment, MURMUR_KEYSTORE=str(self.key), MURMUR_STORE_PASSWORD="provided")
        with patch.object(release, "keychain_password") as keychain, patch.object(release, "validate_keystore"):
            result, state = release.signing_environment(env, "linux", self.home)
            keychain.assert_not_called()
        self.assertEqual("provided", state)
        self.assertEqual("provided", result["MURMUR_KEY_PASSWORD"])

    def test_other_systems_require_explicit_keys(self):
        with self.assertRaises(release.ReleaseError):
            release.signing_environment(self.environment, "linux", self.home)


if __name__ == "__main__":
    unittest.main()
