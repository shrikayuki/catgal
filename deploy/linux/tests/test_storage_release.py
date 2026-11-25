import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from storage_release import STORAGE_INPUTS, storage_manifest, verify_storage_manifest, initialize_application_storage, storage_action


class StorageReleaseTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        for name in STORAGE_INPUTS:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(name)
        self.manifest = storage_manifest(self.root, "storage-test")
        self.write_manifest()

    def write_manifest(self):
        (self.root / "release-manifest.json").write_text(json.dumps(self.manifest))

    def test_exact_source_manifest_is_accepted(self):
        self.assertEqual(self.manifest, verify_storage_manifest(self.root, "storage-test"))
        self.assertFalse(any("secrets" in name for name in STORAGE_INPUTS))

    def test_changed_source_is_rejected(self):
        (self.root / STORAGE_INPUTS[0]).write_text("changed")
        with self.assertRaises(ValueError):
            verify_storage_manifest(self.root, "storage-test")

    def test_extra_paths_and_wrong_versions_are_rejected(self):
        with self.assertRaises(ValueError):
            verify_storage_manifest(self.root, "different-version")
        self.manifest["files"]["../outside"] = "bad"
        self.write_manifest()
        with self.assertRaises(ValueError):
            verify_storage_manifest(self.root, "storage-test")

    def test_application_uses_scoped_credentials_and_preserves_other_settings(self):
        environment = self.root / "java.env"
        environment.write_text("JWT_SECRET=existing-jwt\nMYSQL_PASSWORD=existing-db\n")
        storage = self.root / "minio.env"
        storage.write_text("MINIO_ROOT_PASSWORD=root-private\nMINIO_APP_ACCESS_KEY=app-key\nMINIO_APP_SECRET_KEY=app-private\nMINIO_BUCKET=catgal-media\n")
        initialize_application_storage(environment, storage)
        settings = dict(line.split("=", 1) for line in environment.read_text().splitlines())
        self.assertEqual("existing-jwt", settings["JWT_SECRET"])
        self.assertEqual("existing-db", settings["MYSQL_PASSWORD"])
        self.assertEqual("true", settings["STORAGE_ENABLED"])
        self.assertEqual("app-private", settings["MINIO_SECRET_KEY"])
        self.assertNotIn("root-private", environment.read_text())

    def test_explicit_storage_configuration_is_preserved(self):
        environment = self.root / "java.env"
        environment.write_text("STORAGE_ENABLED=false\nMINIO_ENDPOINT=http://custom\n")
        before = environment.read_bytes()
        initialize_application_storage(environment, self.root / "absent-secret-file")
        self.assertEqual(before, environment.read_bytes())

    def test_policy_only_updates_reuse_image_inputs(self):
        previous = self.root / "previous"
        for name in ("deploy/minio/Dockerfile", "deploy/minio/compose.yaml"):
            target = previous / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((self.root / name).read_bytes())
        (self.root / "deploy/minio/initialize.sh").write_text("new prefix policy")
        self.assertEqual("initialize", storage_action(self.root, previous))

    def test_application_image_policy_is_limited_to_the_four_managed_prefixes(self):
        script = (Path(__file__).resolve().parents[3] / "deploy/minio/initialize.sh").read_text()
        document = script.split("cat >/tmp/media-policy.json <<EOF\n", 1)[1].split("\nEOF", 1)[0]
        policy = json.loads(document.replace("$MINIO_BUCKET", "catgal-media"))
        images = policy["Statement"][1]
        self.assertEqual({"s3:PutObject", "s3:GetObject", "s3:DeleteObject"}, set(images["Action"]))
        self.assertEqual({
            "arn:aws:s3:::catgal-media/v1/avatars/*",
            "arn:aws:s3:::catgal-media/v1/game-covers/*",
            "arn:aws:s3:::catgal-media/v1/ai-bot-avatars/*",
            "arn:aws:s3:::catgal-media/v1/ai-bot-portraits/*",
        }, set(images["Resource"]))
        self.assertIn('mc anonymous set none "local/$MINIO_BUCKET"', script)

    def test_image_source_changes_require_deployment(self):
        self.assertEqual("deploy", storage_action(self.root, None))
        previous = self.root / "empty"
        previous.mkdir()
        self.assertEqual("deploy", storage_action(self.root, previous))


if __name__ == "__main__":
    unittest.main()
