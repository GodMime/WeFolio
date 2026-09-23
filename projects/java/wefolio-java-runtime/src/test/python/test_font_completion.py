"""逐组完成记录必须早于整批结束发布，未原子完成的内容不构成成功。"""
import concurrent.futures
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

FONT_ROOT = Path(__file__).resolve().parents[2] / 'main/resources/fonts'
sys.path.insert(0, str(FONT_ROOT))
import subset

class CompletionRecordProtocolTest(unittest.TestCase):
    """测试父进程协调与文件协议；生成算法由真实许可集成测试覆盖。"""
    def test_publishes_record_before_batch_finishes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            release, published = threading.Event(), threading.Event()
            specs = [dict(requestId='request', groupId=str(i), completionPath=str(root / f'{i}.json'), output=str(root / f'{i}.woff')) for i in range(2)]
            def generate(spec):
                if spec['groupId'] == '1':
                    self.assertTrue(release.wait(5))
                Path(spec['output']).write_bytes(b'woff')
                return dict(output=spec['output'], sha256='a'*64)
            def replace(source, target):
                from os import rename
                rename(source, target)
                if target == Path(specs[0]['completionPath']): published.set()
            with patch.object(subset, 'generate', side_effect=generate), patch.object(subset.os, 'replace', side_effect=replace):
                with concurrent.futures.ThreadPoolExecutor() as pool:
                    future = pool.submit(subset.generate_batch, specs)
                    try:
                        self.assertTrue(published.wait(5))
                        self.assertFalse(future.done())
                        record = json.loads(Path(specs[0]['completionPath']).read_text())
                        self.assertEqual(record['groupId'], '0')
                        self.assertEqual(record['status'], 'COMPLETE')
                        self.assertEqual(record['bytes'], 4)
                    finally:
                        release.set()
                    self.assertEqual(len(future.result()), 2)
    def test_requires_atomic_complete_record(self):
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp) / 'done.json'
            spec = dict(requestId='request', groupId='0', completionPath=str(target), output=str(Path(temp)/'a.woff'))
            with patch.object(subset, 'generate', side_effect=ValueError('bad')):
                result = subset.generate_batch([spec])
            self.assertEqual(result[0]['reasonCode'], 'FINAL_VALIDATION_FAILED')
            self.assertEqual(json.loads(target.read_text())['status'], 'FAILED')
            target.unlink()
            def inspect_before_replace(source, final):
                self.assertFalse(target.exists())
                self.assertEqual(json.loads(Path(source).read_text())['status'], 'COMPLETE')
                raise OSError('interrupted rename')
            Path(spec['output']).write_bytes(b'woff')
            with patch.object(subset.os, 'replace', side_effect=inspect_before_replace):
                with self.assertRaises(OSError): subset.write_completion(spec, dict(sha256='a'*64))
            self.assertFalse(target.exists())

if __name__ == '__main__': unittest.main()
