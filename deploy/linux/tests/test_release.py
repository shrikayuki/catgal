import contextlib
import copy
import io
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import deploy_release as worker
import publish
import release_common as common


def fixture(root):
    files = {"pom.xml": "pom", "gal-server/src/main/java/App.java": "java",
             "gal-common/pom.xml": "common", "ai-service/app/main.py": "python",
             "ai-service/pyproject.toml": "deps", "frontend/src/App.vue": "vue",
             "deploy/linux/compose.yaml": "compose", "deploy/linux/manage.sh": "manage",
             "deploy/linux/secrets/java.env": "JWT_SECRET=fake-test-secret\n",
             "deploy/linux/secrets/ai.env": "AI_TEST=fake-test-secret\n",
             "deploy/linux/.env": "APP_VERSION=" + root.name + "\nHTTP_PORT=8088\n"}
    for name, value in files.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)


class InputTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "release-1"
        fixture(self.root)

    def test_fingerprints_isolate_java_ai_and_web(self):
        for name, service in [("gal-server/src/main/java/App.java", "java"),
                              ("ai-service/app/main.py", "ai"), ("frontend/src/App.vue", "web")]:
            before = common.build_fingerprints(self.root)
            path = self.root / name
            path.write_text(path.read_text() + " change")
            after = common.build_fingerprints(self.root)
            self.assertEqual([service], [key for key in common.SERVICES if before[key] != after[key]])

    def test_compose_and_worker_changes_do_not_rebuild_applications(self):
        before = common.build_fingerprints(self.root)
        deployment = common.deployment_fingerprint(self.root)
        (self.root / "deploy/linux/compose.yaml").write_text("new compose")
        (self.root / "deploy/linux/manage.sh").write_text("new worker")
        self.assertEqual(before, common.build_fingerprints(self.root))
        self.assertNotEqual(deployment, common.deployment_fingerprint(self.root))

    def test_runtime_hash_ignores_build_paths_but_detects_environment_changes(self):
        first = {"image": "old", "build": {"context": "old"}, "environment": {"KEY": "one"}}
        second = {**first, "image": "new", "build": {"context": "new"}}
        self.assertEqual(common.runtime_fingerprint(first), common.runtime_fingerprint(second))
        second["environment"] = {"KEY": "two"}
        self.assertNotEqual(common.runtime_fingerprint(first), common.runtime_fingerprint(second))

    def test_source_archive_excludes_private_and_development_files(self):
        for name in ["docs/report.md", "prompts/task.md", "%ProgramData%/config.json",
                     "frontend/.env.local", "frontend/.npmrc"]:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("private-fixture")
        names = common.source_files(self.root)
        with patch.object(publish, "ROOT", self.root):
            payload = publish.archive_release(self.root.name, names, common.build_fingerprints(self.root),
                                              self.root / "target/missing")
        with zipfile.ZipFile(io.BytesIO(payload)) as archive:
            self.assertIn(common.MANIFEST, archive.namelist())
            for name in archive.namelist():
                self.assertNotIn("secrets", name)
                self.assertNotIn("%ProgramData%", name)
                self.assertFalse(name.endswith(".md"))
                self.assertNotIn(b"private-fixture", archive.read(name))

    def test_manifest_rejects_modified_artifacts_and_source(self):
        artifact = self.root / "deploy/linux/artifacts/java/app.jar"
        artifact.parent.mkdir(parents=True)
        artifact.write_bytes(b"tested-jar")
        value = {"format": 1, "version": self.root.name, "builds": common.build_fingerprints(self.root),
                 "artifacts": {artifact.relative_to(self.root).as_posix(): common.file_digest(artifact)}}
        common.write_json(self.root / common.MANIFEST, value)
        common.verify_manifest(self.root)
        artifact.write_bytes(b"changed-jar")
        with self.assertRaises(ValueError):
            common.verify_manifest(self.root)
        artifact.write_bytes(b"tested-jar")
        (self.root / "frontend/src/App.vue").write_text("changed source")
        with self.assertRaises(ValueError):
            common.verify_manifest(self.root)

    def test_release_path_validation(self):
        for value in ("../escape", "bad/name", "bad tag", "x;shutdown", ""):
            with self.assertRaises(ValueError):
                common.safe_release(self.root, value)

    def test_manifest_v2_rejects_configuration_changes(self):
        value = {"format": 2, "version": self.root.name, "builds": common.build_fingerprints(self.root),
                 "compose_source": common.file_digest(self.root / "deploy/linux/compose.yaml"),
                 "deployment_source": common.deployment_fingerprint(self.root), "artifacts": {}}
        common.write_json(self.root / common.MANIFEST, value)
        common.verify_manifest(self.root)
        (self.root / "deploy/linux/compose.yaml").write_text("changed after validation")
        with self.assertRaises(ValueError):
            common.verify_manifest(self.root)


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.app = Path(self.temporary.name)
        self.old = self.app / "old-1"
        self.new = self.app / "new-1"
        fixture(self.old)
        fixture(self.new)
        self.config = {name: {"environment": {"NAME": name}} for name in common.SERVICES}
        hashes = common.build_fingerprints(self.old)
        self.records = {name: {"container": "old-" + name, "image": "sha256:" + name,
                               "origin": str(self.old), "health": "healthy"} for name in common.SERVICES}
        self.current = copy.deepcopy(self.records)
        self.previous = {"format": 1, "release": str(self.old), "services": {
            name: {**self.records[name], "build": hashes[name], "runtime": common.runtime_fingerprint(self.config[name])}
            for name in common.SERVICES}}
        common.write_json(self.app / ".deploy/state.json", self.previous)
        self.calls = []
        self.fail_build = False
        self.fail_update = False
        self.drift = False
        self.manifest = None
        for target, value in [("APP_ROOT", self.app), ("exclusive_lock", lambda _: contextlib.nullcontext()),
                              ("load_baseline", lambda log: (self.previous, copy.deepcopy(self.records))),
                              ("running_services", lambda log=None: copy.deepcopy(self.current)),
                              ("compose_services", lambda root, log=None: copy.deepcopy(self.config)),
                              ("compose", self.fake_compose), ("run", self.fake_run), ("smoke", self.fake_smoke)]:
            started = patch.object(worker, target, value)
            started.start()
            self.addCleanup(started.stop)
        self.smoke_failures = 0

    def manifest_for_current_source(self):
        artifacts = {}
        for name in ["java/app.jar", "java/health/HealthCheck.class", "web/index.html"]:
            path = self.new / "deploy/linux/artifacts" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"tested artifact")
            artifacts[path.relative_to(self.new).as_posix()] = common.file_digest(path)
        self.manifest = {"format": 1, "version": self.new.name,
                         "builds": common.build_fingerprints(self.new), "artifacts": artifacts}
        common.write_json(self.new / common.MANIFEST, self.manifest)

    def fake_run(self, arguments, **kwargs):
        self.calls.append(arguments)
        return ""

    def fake_compose(self, root, *args, **kwargs):
        self.calls.append(["compose", *args])
        if args[0] == "build":
            if self.fail_build:
                raise RuntimeError("isolated build failure")
            if self.drift:
                self.current["ai"]["container"] = "external-ai"
        if args[0] == "up":
            if self.fail_update and not kwargs.get("override"):
                self.fail_update = False
                raise RuntimeError("isolated unhealthy update")
            for name in args[args.index("240") + 1:]:
                if kwargs.get("override"):
                    self.current[name] = copy.deepcopy(self.records[name])
                else:
                    self.current[name].update(container="new-" + name, origin=str(self.new))
                    if self.previous["services"][name]["build"] != self.manifest["builds"][name]:
                        self.current[name]["image"] = "sha256:new-" + name
        return ""

    def fake_smoke(self, root, log):
        if self.smoke_failures:
            self.smoke_failures -= 1
            raise RuntimeError("isolated smoke failure")

    def deploy(self):
        self.manifest_for_current_source()
        with contextlib.redirect_stdout(io.StringIO()):
            worker.deploy(self.new.name)

    def test_java_only_reuses_ai_web_and_reloads_nginx(self):
        (self.new / "gal-server/src/main/java/App.java").write_text("new java")
        self.deploy()
        self.assertIn(["compose", "build", "java"], self.calls)
        self.assertNotIn(["compose", "build", "web"], self.calls)
        self.assertEqual("old-web", self.current["web"]["container"])
        self.assertEqual("old-ai", self.current["ai"]["container"])
        self.assertIn(["docker", "exec", "old-web", "nginx", "-s", "reload"], self.calls)
        up = next(call for call in self.calls if call[:2] == ["compose", "up"])
        self.assertEqual(["java"], up[up.index("240") + 1:])
        self.assertIn("--no-deps", up)
        self.assertIn("never", up)

    def test_web_only_restarts_web(self):
        (self.new / "frontend/src/App.vue").write_text("new web")
        self.deploy()
        self.assertEqual("old-java", self.current["java"]["container"])
        self.assertEqual("old-ai", self.current["ai"]["container"])
        self.assertIn(["compose", "build", "web"], self.calls)

    def test_runtime_change_reuses_image_without_build(self):
        self.config["java"]["mem_limit"] = "1200m"
        self.deploy()
        self.assertFalse(any(call[:2] == ["compose", "build"] for call in self.calls))
        self.assertEqual("sha256:java", self.current["java"]["image"])
        self.assertEqual("new-java", self.current["java"]["container"])

    def test_identical_inputs_do_not_recreate_services(self):
        self.deploy()
        self.assertFalse(any(call[:2] == ["compose", "up"] for call in self.calls))
        result = common.read_json(self.app / ".deploy/status/new-1.json")
        self.assertEqual("success", result["status"])
        self.assertEqual([], result["updates"])

    def test_failed_build_preserves_running_containers(self):
        (self.new / "gal-server/src/main/java/App.java").write_text("new java")
        self.fail_build = True
        with self.assertRaises(RuntimeError):
            self.deploy()
        self.assertEqual(self.records, self.current)
        self.assertFalse(any(call[:2] == ["compose", "up"] for call in self.calls))

    def test_unhealthy_update_restores_affected_service(self):
        (self.new / "gal-server/src/main/java/App.java").write_text("new java")
        self.fail_update = True
        with self.assertRaises(RuntimeError):
            self.deploy()
        self.assertEqual(self.records, self.current)
        result = common.read_json(self.app / ".deploy/status/new-1.json")
        self.assertEqual("rolled-back", result["phase"])
        self.assertEqual(["java"], result["updates"])
        self.assertEqual(self.previous, common.read_json(self.app / ".deploy/state.json"))

    def test_failed_smoke_rolls_back_after_startup(self):
        (self.new / "frontend/src/App.vue").write_text("new web")
        self.smoke_failures = 1
        with self.assertRaises(RuntimeError):
            self.deploy()
        self.assertEqual(self.records, self.current)

    def test_concurrent_manual_deployment_is_detected_before_replacement(self):
        (self.new / "gal-server/src/main/java/App.java").write_text("new java")
        self.drift = True
        with self.assertRaises(RuntimeError):
            self.deploy()
        self.assertFalse(any(call[:2] == ["compose", "up"] for call in self.calls))

    def test_credentials_stay_server_side_and_versions_keep_image_tags(self):
        self.deploy()
        self.assertEqual((self.old / "deploy/linux/secrets/java.env").read_text(),
                         (self.new / "deploy/linux/secrets/java.env").read_text())
        for name in common.SERVICES:
            self.assertIn(["docker", "image", "tag", "sha256:" + name, "catgal/" + name + ":new-1"], self.calls)
        self.assertNotIn("fake-test-secret", (self.app / ".deploy/state.json").read_text())

    def test_source_verification_does_not_replace_application_or_move_baseline(self):
        (self.new / "gal-server/src/main/java/App.java").write_text("candidate java")
        common.write_json(self.new / common.MANIFEST, {"format": 1, "version": self.new.name,
                          "builds": common.build_fingerprints(self.new), "artifacts": {}})
        with patch.object(worker, "build_source") as build, contextlib.redirect_stdout(io.StringIO()):
            worker.deploy(self.new.name, source_build=True, verify_only=True)
        self.assertEqual((self.new, "java"), build.call_args.args[:2])
        self.assertFalse(any(call[:2] == ["compose", "up"] for call in self.calls))
        self.assertEqual(self.previous, common.read_json(self.app / ".deploy/state.json"))
        self.assertEqual("verification-complete", common.read_json(self.app / ".deploy/status/new-1.json")["phase"])

    def test_docker_build_receives_source_context_without_runtime_secrets(self):
        seen = []

        def check_build(arguments, **kwargs):
            context = Path(arguments[-1])
            self.assertTrue((context / "gal-server/src/main/java/App.java").is_file())
            self.assertFalse((context / "deploy/linux/.env").exists())
            self.assertFalse((context / "deploy/linux/secrets").exists())
            seen.append(context)

        with patch.object(worker, "run", check_build):
            worker.build_source(self.new, "java", "some-build-hash", None)
        self.assertEqual(1, len(seen))
        self.assertFalse(seen[0].exists())


if __name__ == "__main__":
    unittest.main()
