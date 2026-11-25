import io
import stat
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "jenkins"))
import release_queue as queue


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.destination = Path(directory.name) / "release"

    def archive(self, extras):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            for name in queue.REQUIRED:
                archive.writestr(name, "required")
            for name in extras:
                archive.writestr(name, "submission")
        return zipfile.ZipFile(data)

    def test_uploaded_compose_and_health_check_are_never_extracted(self):
        with self.archive([*queue.PINNED, "frontend/src/main.ts"]) as archive:
            queue.extract_sources(archive, self.destination)
        self.assertTrue((self.destination / "frontend/src/main.ts").is_file())
        self.assertTrue(all(not (self.destination / path).exists() for path in queue.PINNED))

    def test_secret_or_traversal_rejects_entire_archive_before_writing(self):
        for name in ("deploy/linux/secrets/java.env", "frontend/.env", "gal-common/../../escape"):
            with self.subTest(name=name), self.archive([name]) as archive:
                with self.assertRaises(ValueError):
                    queue.extract_sources(archive, self.destination)
                self.assertFalse(self.destination.exists())

    def test_symlink_rejects_entire_archive_before_writing(self):
        entry = zipfile.ZipInfo("frontend/public/example")
        entry.create_system = 3
        entry.external_attr = (stat.S_IFLNK | 0o777) << 16
        with self.archive([entry]) as archive:
            with self.assertRaises(ValueError):
                queue.extract_sources(archive, self.destination)
        self.assertFalse(self.destination.exists())
