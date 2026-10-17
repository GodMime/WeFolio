"""按可信源清单批量生成静态 WOFF；最终落盘后再次验证字形、字重与完整许可。"""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import unicodedata
from fontTools.ttLib import TTFont
from font_license import annotate_subset_license, read_source_license, validate_subset_license


PROTOCOL_VERSION = 1
COMPLETE = 'COMPLETE'
FAILED = 'FAILED'
OUTPUT_TOO_LARGE = 'OUTPUT_TOO_LARGE'
FINAL_VALIDATION_FAILED = 'FINAL_VALIDATION_FAILED'


class OutputTooLarge(ValueError):
    """单产物超限与字形、许可失败分别诊断。"""


def allowed(cp, language):
    """语言规则固定为 r1：共用数字标点与组合符，中文排除拉丁字母。"""
    name = unicodedata.name(chr(cp), "")
    category = unicodedata.category(chr(cp))
    common = category[0] in "PNMZ" or cp in (0x200C, 0x200D)
    latin = "LATIN" in name
    han = "CJK" in name or "IDEOGRAPH" in name
    return common or (latin if language == "en" else han)


def generate(spec):
    """一次裁剪和转换，不重试；异常由调用方降级。"""
    source = Path(spec['source'])
    expected = spec['sourceSha256']
    if hashlib.sha256(source.read_bytes()).hexdigest() != expected:
        raise ValueError('源字体摘要不匹配')
    legal = read_source_license(source, spec['licensePaths'])
    with TTFont(source, lazy=True) as original:
        cmap = original.getBestCmap()
        cps = sorted(cp for cp in spec['codepoints'] if cp in cmap and allowed(cp, spec['language']))
    target = Path(spec['output'])
    unicode_file = target.with_suffix('.unicodes')
    unicode_file.write_text(','.join(f'{cp:X}' for cp in cps), encoding='utf-8')
    raw = target.with_suffix('.sfnt')
    command = [spec['harfbuzz'], str(source), '--unicodes-file='+str(unicode_file),
               '--layout-features=*', '--output-file='+str(raw)]
    if spec['variable']:
        command.append('--variations=wght='+str(spec['weight']))
    subprocess.run(command, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    family = 'WFSubset' + spec['subsetHash'][:24]
    with TTFont(raw, recalcTimestamp=False) as font:
        if 'fvar' in font:
            raise ValueError('产物不得保留可变轴')
        names = {1:family,2:'Regular',3:family,4:family,6:family,16:family,17:'Regular'}
        for record in font['name'].names:
            if record.nameID in names:
                record.string = names[record.nameID].encode(record.getEncoding())
        if 'CFF ' in font:
            cff = font['CFF '].cff
            cff.fontNames = [family]
            cff.topDictIndex[0].FullName = family
            cff.topDictIndex[0].FamilyName = family
        font['OS/2'].usWeightClass = spec['weight']
        annotate_subset_license(font, legal)
        font.flavor = 'woff'
        font.save(target)
    validate_subset_license(target, legal)
    with TTFont(target, checkChecksums=2) as font:
        actual = set(font.getBestCmap())
        if actual != set(cps) or font['OS/2'].usWeightClass != spec['weight'] or 'fvar' in font:
            raise ValueError('最终 WOFF 字形或字重校验失败')
    if target.stat().st_size > spec['maxBytes']:
        raise OutputTooLarge('字体产物过大')
    return {'output':str(target), 'sha256':hashlib.sha256(target.read_bytes()).hexdigest()}


def try_generate(spec):
    """单组失败不丢掉其它已校验的成功产物。"""
    try:
        return generate(spec)
    except Exception as error:
        return {'output': spec['output'], 'error': type(error).__name__,
                'reasonCode': OUTPUT_TOO_LARGE if isinstance(error, OutputTooLarge) else FINAL_VALIDATION_FAILED}


def write_completion(spec, result):
    """私有请求目录逐组原子发布；只有最终文件完整可读才是完成记录。"""
    target = Path(spec['completionPath'])
    record = dict(protocolVersion=PROTOCOL_VERSION, requestId=spec['requestId'], groupId=spec['groupId'])
    if 'error' in result:
        record.update(status=FAILED, reasonCode=result['reasonCode'])
    else:
        record.update(status=COMPLETE, sha256=result['sha256'], bytes=Path(spec['output']).stat().st_size)
    temporary = target.with_suffix('.tmp')
    try:
        with temporary.open('w', encoding='utf-8') as stream:
            json.dump(record, stream)
            stream.flush()
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


def generate_batch(specs):
    """按任务实际完成顺序落盘，各组只生成一次，汇总仅用于诊断。"""
    results = [None] * len(specs)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        pending = {pool.submit(try_generate, spec): index for index, spec in enumerate(specs)}
        for future in concurrent.futures.as_completed(pending):
            index = pending[future]
            result = future.result()
            write_completion(specs[index], result)
            results[index] = result
    return results


def main():
    """请求文件由 Java 写入；不解释 shell，也不接受客户端资源路径。"""
    specs = json.loads(Path(sys.argv[1]).read_text())
    result = generate_batch(specs)
    Path(sys.argv[2]).write_text(json.dumps(result))


if __name__ == '__main__':
    main()
