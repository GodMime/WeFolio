"""完整字体门禁自身的负向回归，不访问外部资源。"""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[3] / 'scripts/font-verification.py'
spec = importlib.util.spec_from_file_location('font_verification', SCRIPT)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class FontVerificationGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.registry = {'schemaVersion': 1,
                         'javaTests': [{'class': 'example.RequiredTest', 'requiredMethods': ['realTest']}],
                         'pythonTests': [{'module': 'test_required', 'class': 'RequiredTest', 'requiredMethods': ['test_real']}],
                         'shellTests': [{'script': 'test.sh', 'requiredFunctions': ['test_phase']}]}
        self.run_id = 'current-run'
        self.good_reports()

    def good_reports(self):
        (self.root / 'TEST-example.RequiredTest.xml').write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="example.RequiredTest" name="realTest"/></testsuite>')
        self.python = {'runId': self.run_id, 'testsRun': 1, 'ids': ['test_required.RequiredTest.test_real'], 'skipped': [], 'failures': [], 'errors': [], 'expectedFailures': [], 'unexpectedSuccesses': []}
        self.shell = {'runId': self.run_id, 'exitCode': 0, 'completed': ['test.sh:test_phase']}
        (self.root / 'run-id').write_text(self.run_id)

    def validate(self):
        gate.validate_reports(self.registry, self.root, self.python, self.shell, self.run_id)

    def test_missing_or_empty_registry_fails(self):
        with self.assertRaises((ValueError, OSError)):
            gate.load_registry(self.root / 'missing.json')
        for data in ({}, {'schemaVersion': 1, 'javaTests': [], 'pythonTests': [], 'shellTests': []}):
            path = self.root / 'registry.json'
            path.write_text(json.dumps(data))
            with self.assertRaises(ValueError):
                gate.load_registry(path)

    def test_missing_required_class_or_method_fails(self):
        self.validate()
        for classname, method in [('example.OtherTest', 'realTest'), ('example.RequiredTest', 'other')]:
            (self.root / 'TEST-example.RequiredTest.xml').write_text(f'<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="{classname}" name="{method}"/></testsuite>')
            with self.assertRaises(ValueError):
                self.validate()

    def test_java_or_python_skip_fails(self):
        (self.root / 'TEST-example.RequiredTest.xml').write_text('<testsuite tests="1" failures="0" errors="0" skipped="1"><testcase classname="example.RequiredTest" name="realTest"><skipped/></testcase></testsuite>')
        with self.assertRaises(ValueError):
            self.validate()
        self.good_reports()
        self.python['skipped'] = [['test_required.RequiredTest.test_real', 'missing tool']]
        with self.assertRaises(ValueError):
            self.validate()

    def test_stale_reports_and_shell_failure_fail(self):
        self.python['runId'] = 'old-run'
        with self.assertRaises(ValueError):
            self.validate()
        self.good_reports()
        (self.root / 'run-id').write_text('old-run')
        with self.assertRaises(ValueError):
            self.validate()
        self.good_reports()
        self.shell['exitCode'] = 1
        with self.assertRaises(ValueError):
            self.validate()
        self.good_reports()
        self.shell['completed'] = []
        with self.assertRaises(ValueError):
            self.validate()


if __name__ == '__main__':
    unittest.main()
