"""使用真实九组 WOFF 和正式最终校验器验证许可负向样本。"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

FONT_ROOT = Path(__file__).resolve().parents[2] / "main" / "resources" / "fonts"
sys.path.insert(0, str(FONT_ROOT))
from fontTools.ttLib import TTFont
from font_license import read_source_license, validate_subset_license
from subset import generate, try_generate


class FinalWoffLicenseTest(unittest.TestCase):
    """只变异已落盘的最终 WOFF，不给生产脚本加入破坏开关。"""

    @classmethod
    def setUpClass(cls):
        """按包内真实清单生成六款字体的九个物理字重。"""
        cls.workspace = tempfile.TemporaryDirectory(prefix="wefolio-license-test-")
        cls.root = Path(cls.workspace.name)
        harfbuzz = os.environ.get("FONT_TEST_HARFBUZZ") or shutil.which("hb-subset")
        if not harfbuzz:
            raise RuntimeError("必须提供 FONT_TEST_HARFBUZZ 或 PATH 中的 hb-subset")
        manifest = json.loads((FONT_ROOT / "manifest.json").read_text())
        cls.outputs = []
        for font in manifest["fonts"]:
            for weight, face in ((400, font["normal"]), (700, font.get("bold"))):
                if face is None or "relativePath" not in face:
                    continue
                relative = face["relativePath"]
                source = next(item for item in font["sourceFiles"] if item["relativePath"] == relative)
                output = cls.root / f"{font['fontId']}-{weight}.woff"
                spec = {"source": str(FONT_ROOT / relative), "sourceSha256": source["sha256"],
                        "language": font["language"], "codepoints": list(map(ord, "Timeless中永123")),
                        "weight": weight, "variable": "instantiateAxes" in face,
                        "harfbuzz": harfbuzz, "subsetHash": hashlib.sha256(output.name.encode()).hexdigest(),
                        "output": str(output), "maxBytes": 8 * 1024 * 1024,
                        "licensePaths": [str(FONT_ROOT / path) for path in font["licensePaths"]]}
                generate(spec)
                legal = read_source_license(spec["source"], spec["licensePaths"])
                cls.outputs.append((font["fontId"], weight, output, legal, spec))
        if len(cls.outputs) != 9:
            raise AssertionError(f"清单应生成 9 个物理字重，实际 {len(cls.outputs)}")

    @classmethod
    def tearDownClass(cls):
        """仅清理当前测试的临时本地产物。"""
        cls.workspace.cleanup()

    def test_all_physical_faces_keep_full_license_after_reopening(self):
        """重新打开真实落盘产物，确认完整许可、静态字重和实际字形。"""
        for font_id, weight, output, legal, _ in self.outputs:
            with self.subTest(font=font_id, weight=weight):
                validate_subset_license(output, legal)
                with TTFont(output, checkChecksums=2) as font:
                    self.assertEqual(font.flavor, "woff")
                    self.assertEqual(font["OS/2"].usWeightClass, weight)
                    self.assertNotIn("fvar", font)
                    self.assertTrue(font.getBestCmap())
                    self.assertTrue(any(name.nameID == 14 for name in font["name"].names))

    def test_missing_truncated_or_replaced_legal_records_are_rejected(self):
        """逐物理字重、逐 nameID 0/13/14 覆盖删除、截断和错配。"""
        for font_id, weight, output, legal, _ in self.outputs:
            for name_id in (0, 13, 14):
                for mutation in ("missing", "truncated", "replaced"):
                    with self.subTest(font=font_id, weight=weight, name_id=name_id, mutation=mutation):
                        changed = self.root / "mutated.woff"
                        with TTFont(output, recalcTimestamp=False) as font:
                            if mutation == "missing":
                                font["name"].names = [n for n in font["name"].names if n.nameID != name_id]
                            else:
                                for record in font["name"].names:
                                    if record.nameID == name_id:
                                        text = record.toUnicode()
                                        text = text[:len(text)//2] if mutation == "truncated" else "INVALID TEST LEGAL TEXT"
                                        record.string = text.encode(record.getEncoding())
                            font.save(changed)
                        with self.assertRaises(ValueError):
                            validate_subset_license(changed, legal)

    def test_unreadable_and_non_woff_outputs_are_rejected(self):
        """正文完全损坏及伪装成输出文件的非 WOFF 均不能准入。"""
        _, _, output, legal, _ = self.outputs[0]
        changed = self.root / "unreadable.woff"
        changed.write_bytes(b"not a font")
        with self.assertRaises(Exception):
            validate_subset_license(changed, legal)
        with TTFont(output) as font:
            font.flavor = None
            font.save(changed)
        with self.assertRaises(ValueError):
            validate_subset_license(changed, legal)

    def test_one_group_failure_does_not_discard_other_validated_output(self):
        """生产单组边界保留成功结果，失败仅返回结构化错误且不重试。"""
        _, _, _, _, valid = self.outputs[0]
        invalid = dict(valid, sourceSha256="0" * 64, output=str(self.root / "invalid.woff"))
        failure = try_generate(invalid)
        self.assertIn("error", failure)
        self.assertFalse(Path(invalid["output"]).exists())
        success = try_generate(dict(valid, output=str(self.root / "still-valid.woff")))
        self.assertNotIn("error", success)
        self.assertEqual(len(success["sha256"]), 64)


if __name__ == "__main__":
    unittest.main()
