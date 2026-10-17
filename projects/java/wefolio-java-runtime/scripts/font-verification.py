#!/usr/bin/env python3
"""执行并核验固定必验集；每次使用新的报告目录，不加载应用配置。"""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def load_registry(path):
    data = json.loads(Path(path).read_text())
    if not isinstance(data, dict) or data.get('schemaVersion') != 1:
        raise ValueError('必验登记 schemaVersion 无效')
    for kind, identity, methods in [('javaTests', 'class', 'requiredMethods'),
                                    ('pythonTests', 'module', 'requiredMethods'),
                                    ('shellTests', 'script', 'requiredFunctions')]:
        entries = data.get(kind)
        if not isinstance(entries, list) or not entries:
            raise ValueError(f'必验登记缺失或为空: {kind}')
        identities = set()
        for entry in entries:
            if not isinstance(entry, dict) or not isinstance(entry.get(identity), str) or not entry[identity]:
                raise ValueError(f'必验身份无效: {kind}')
            names = entry.get(methods)
            if not isinstance(names, list) or not names or any(not isinstance(n, str) or not n for n in names) or len(names) != len(set(names)):
                raise ValueError(f'必验方法无效: {kind}')
            if kind == 'pythonTests' and not isinstance(entry.get('class'), str):
                raise ValueError('Python 类缺失')
            key = entry[identity] + entry.get('class', '')
            if key in identities:
                raise ValueError(f'重复必验身份: {key}')
            identities.add(key)
    return data


def validate_reports(registry, directory, python, shell, run_id):
    directory = Path(directory)
    if (directory / 'run-id').read_text() != run_id or python.get('runId') != run_id or shell.get('runId') != run_id:
        raise ValueError('拒绝旧报告')
    for expected in registry['javaTests']:
        name = expected['class']
        report = ET.parse(directory / f'TEST-{name}.xml').getroot()
        if int(report.get('tests', '0')) <= 0 or any(int(report.get(key, '-1')) != 0 for key in ('failures', 'errors', 'skipped')):
            raise ValueError(f'Java 必验失败/跳过/空: {name}')
        cases = report.findall('testcase')
        if any(case.find(tag) is not None for case in cases for tag in ('skipped', 'failure', 'error')):
            raise ValueError(f'Java 方法未通过: {name}')
        actual = {(case.get('classname'), case.get('name')) for case in cases}
        for method in expected['requiredMethods']:
            if (name, method) not in actual:
                raise ValueError(f'Java 必验方法缺失: {name}.{method}')
    if python.get('testsRun', 0) <= 0 or any(python.get(key) != [] for key in ('skipped', 'failures', 'errors', 'expectedFailures', 'unexpectedSuccesses')):
        raise ValueError('Python 必验失败/跳过/空')
    for entry in registry['pythonTests']:
        for method in entry['requiredMethods']:
            identity = f"{entry['module']}.{entry['class']}.{method}"
            if identity not in python.get('ids', []):
                raise ValueError(f'Python 必验方法缺失: {identity}')
    if shell.get('exitCode') != 0:
        raise ValueError('Shell 必验执行失败')
    for entry in registry['shellTests']:
        for method in entry['requiredFunctions']:
            if f"{entry['script']}:{method}" not in shell.get('completed', []):
                raise ValueError(f'Shell 必验函数缺失: {method}')


class RecordingResult(unittest.TextTestResult):
    """记录真正开始执行的 ID，不能用发现到的列表冒充执行证据。"""
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.ids = []

    def startTest(self, test):
        self.ids.append(test.id())
        super().startTest(test)


def main():
    registry = load_registry(ROOT / 'scripts/font-verification-required-tests.json')
    for key in ('FONT_TEST_PYTHON', 'FONT_TEST_HARFBUZZ'):
        tool = os.environ.get(key)
        if not tool or not Path(tool).is_file() or not os.access(tool, os.X_OK):
            raise ValueError(f'缺少可执行工具: {key}')
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    version = subprocess.run([java, '-version'], capture_output=True, text=True, check=True)
    if not re.search(r'version "21(?:\.|"|[-+])', version.stderr + version.stdout):
        raise ValueError('完整验收要求 JDK 21')
    import fontTools
    harfbuzz = subprocess.check_output([os.environ['FONT_TEST_HARFBUZZ'], '--version'], text=True).splitlines()[0]
    target = ROOT / 'target/font-verification'
    target.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='run-', dir=target))
    run_id = str(uuid.uuid4())
    (directory / 'run-id').write_text(run_id)
    (directory / 'tools.json').write_text(json.dumps({'python': sys.version.split()[0], 'fontTools': fontTools.__version__, 'harfbuzz': harfbuzz, 'java': version.stderr.strip()}, indent=2))
    print(f'本次报告目录: {directory}', flush=True)
    classes = ','.join(entry['class'] for entry in registry['javaTests'])
    subprocess.run(['mvn', '-o', '-f', str(ROOT / 'pom.xml'), f'-Dtest={classes}',
                    f'-Dfont.verification.reports={directory}', 'test'], cwd=ROOT, check=True)
    sys.path.insert(0, str(ROOT / 'src/test/python'))
    suite = unittest.TestSuite()
    for entry in registry['pythonTests']:
        # 运行完整类，再校验固定方法，防止遗漏新增场景。
        suite.addTests(unittest.defaultTestLoader.loadTestsFromName(f"{entry['module']}.{entry['class']}"))
    result = unittest.TextTestRunner(verbosity=2, resultclass=RecordingResult).run(suite)
    python = {'runId': run_id, 'testsRun': result.testsRun, 'ids': result.ids,
              'skipped': [(test.id(), reason) for test, reason in result.skipped],
              'failures': [(test.id(), detail) for test, detail in result.failures],
              'errors': [(test.id(), detail) for test, detail in result.errors],
              'expectedFailures': [test.id() for test, _ in result.expectedFailures],
              'unexpectedSuccesses': [test.id() for test in result.unexpectedSuccesses]}
    (directory / 'python.json').write_text(json.dumps(python, indent=2))
    shell = {'runId': run_id, 'exitCode': 0, 'completed': []}
    for entry in registry['shellTests']:
        completed = subprocess.run(['bash', str(ROOT / entry['script'])], cwd=ROOT, capture_output=True, text=True)
        (directory / (Path(entry['script']).stem + '.log')).write_text(completed.stdout + completed.stderr)
        if completed.returncode:
            shell['exitCode'] = completed.returncode
        shell['completed'].extend(f"{entry['script']}:{name}" for name in re.findall(r'^DEPLOY_TEST_PASSED (test_\w+)$', completed.stdout, re.M))
    (directory / 'shell.json').write_text(json.dumps(shell, indent=2))
    validate_reports(registry, directory, python, shell, run_id)
    (directory / 'success.json').write_text(json.dumps({'runId': run_id, 'status': 'passed'}))
    print('字体完整必验通过（仅隔离测试，不代表生产或真机验收）')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, ET.ParseError, subprocess.CalledProcessError) as error:
        print(f'字体完整验收失败: {error}', file=sys.stderr)
        sys.exit(1)
