"""为本地实验字体子集嵌入原版权声明和完整许可证，并校验落盘结果。"""

from copy import deepcopy
from pathlib import Path

from fontTools.ttLib import TTFont


COPYRIGHT_NAME_ID = 0
LICENSE_NAME_ID = 13
LICENSE_URL_NAME_ID = 14
LEGAL_NAME_IDS = {COPYRIGHT_NAME_ID, LICENSE_NAME_ID, LICENSE_URL_NAME_ID}
PRESERVED_NAME_IDS = {COPYRIGHT_NAME_ID, LICENSE_URL_NAME_ID}
WINDOWS_PLATFORM_ID = 3
UNICODE_ENCODING_ID = 1
ENGLISH_LANGUAGE_ID = 0x0409


def read_source_license(source, license_paths):
    """从指定源字体及其配套许可文件读取原声明；不接受缺失的许可材料。"""
    if not license_paths:
        raise ValueError("字体子集必须指定原始许可证文件")
    texts = [Path(path).read_text(encoding="utf-8") for path in license_paths]
    if any(not text.strip() for text in texts):
        raise ValueError("原始许可证文件不能为空")
    with TTFont(source, lazy=True) as font:
        records = [deepcopy(record) for record in font["name"].names
                   if record.nameID in PRESERVED_NAME_IDS]
    if not any(record.nameID == COPYRIGHT_NAME_ID and record.toUnicode().strip()
               for record in records):
        raise ValueError("源字体缺少版权声明")
    return records, "\n\n".join(texts)


def annotate_subset_license(font, source_license):
    """裁剪和改名后恢复源版权、许可链接，将完整许可正文写入 nameID 13。"""
    records, full_text = source_license
    names = font["name"]
    names.names = [record for record in names.names if record.nameID not in LEGAL_NAME_IDS]
    names.names.extend(deepcopy(records))
    names.setName(full_text, LICENSE_NAME_ID, WINDOWS_PLATFORM_ID,
                  UNICODE_ENCODING_ID, ENGLISH_LANGUAGE_ID)


def validate_subset_license(output, source_license):
    """重新打开最终 WOFF，对照源声明及完整许可正文，拒绝缺失或被改写的产物。"""
    records, full_text = source_license

    def identity(record):
        """比较记录身份和解码后的全文，不依赖字体表的序列化顺序。"""
        return (record.nameID, record.platformID, record.platEncID,
                record.langID, record.toUnicode())

    expected = {identity(record) for record in records}
    with TTFont(output, checkChecksums=2) as font:
        if font.flavor != "woff":
            raise ValueError("许可证校验对象必须是最终 WOFF 文件")
        actual = {identity(record) for record in font["name"].names
                  if record.nameID in PRESERVED_NAME_IDS}
        license_records = [record.toUnicode() for record in font["name"].names
                           if record.nameID == LICENSE_NAME_ID]
        if actual != expected:
            raise ValueError("子集版权声明或许可链接与源字体不一致")
        if not license_records or any(text != full_text for text in license_records):
            raise ValueError("子集未完整保留原始许可证正文")


def validate_request_file(request_path, result_path):
    """批量复核落盘产物，供 Java 接收边界复用同一许可算法。"""
    import json
    results = []
    for spec in json.loads(Path(request_path).read_text(encoding="utf-8")):
        try:
            validate_subset_license(spec["output"], read_source_license(spec["source"], spec["licensePaths"]))
            results.append({"valid": True})
        except Exception:
            results.append({"valid": False, "reason": "FINAL_LICENSE_INVALID"})
    Path(result_path).write_text(json.dumps(results), encoding="utf-8")


if __name__ == "__main__":
    import sys
    if len(sys.argv) != 3:
        raise SystemExit("参数应为请求文件和校验结果文件")
    validate_request_file(sys.argv[1], sys.argv[2])
