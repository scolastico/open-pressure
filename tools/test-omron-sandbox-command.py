#!/usr/bin/env python3
"""Check the reporting command's exit status without running Gradle or Omron."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class SandboxCommandTest(unittest.TestCase):
    def run_command(self, gradle_status):
        with tempfile.TemporaryDirectory(prefix="omron-command-test-") as directory:
            root = Path(directory)
            (root / "tools").mkdir()
            shutil.copyfile(Path(__file__).with_name("omron-sandbox.sh"), root / "tools/omron-sandbox.sh")
            fixtures = root / "protocol/src/testFixtures/resources/org/openpressure/protocol/fixtures"
            fixtures.mkdir(parents=True)
            (fixtures / "index").write_text("test/sample\n")
            (root / "protocol/src/main").mkdir(parents=True)
            (root / "protocol/src/main/Synthetic.java").write_text("class Synthetic {}\n")
            (root / "gradlew").write_text("#!/bin/bash\nmkdir -p omron-sandbox/build/omron-sandbox/results/test app/build/fixture-replay/test\necho 'omron: synthetic result' > omron-sandbox/build/omron-sandbox/results/test/sample.txt\necho 'ours (app): synthetic result' > app/build/fixture-replay/test/sample.txt\nexit " + str(gradle_status) + "\n")
            (root / "gradlew").chmod(0o755)
            binaries = root / "bin"
            binaries.mkdir()
            for name, body in {"javac": "exit 0", "java": "echo 'ours (protocol): synthetic result'"}.items():
                path = binaries / name
                path.write_text("#!/bin/bash\n" + body + "\n")
                path.chmod(0o755)
            env = dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ["PATH"])
            return subprocess.run(["bash", "tools/omron-sandbox.sh", "test/sample"], cwd=root, env=env, capture_output=True, text=True)

    def test_failed_gradle_run_still_reports_both_clients_and_returns_failure(self):
        result = self.run_command(9)
        self.assertIn("omron: synthetic result", result.stdout)
        self.assertIn("ours (app): synthetic result", result.stdout)
        self.assertIn("ours (protocol): synthetic result", result.stdout)
        self.assertEqual(9, result.returncode, result.stderr)

    def test_successful_gradle_run_returns_success(self):
        result = self.run_command(0)
        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
