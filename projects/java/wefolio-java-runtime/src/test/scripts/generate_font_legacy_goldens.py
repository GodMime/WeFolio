#!/usr/bin/env python3
"""从固定旧提交的真实 DTO 生成请求摘要黄金夹具，不加载数据库或网络配置。

先取得本地 Maven 测试依赖 classpath，再传 --classpath（包含 target/classes）。
输入使用小程序旧序列化夹具；输出保留原 DTO 源 SHA、排序原文和 SHA-256。
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

BASE = "971336c90e1421453047df57dd6db330047f26df"
MODULE = Path(__file__).resolve().parents[3]
ROOT = MODULE.parents[2]
DTO_ROOT = "projects/java/wefolio-java-runtime/src/main/java/com/jxc/wefolio/"
DTO_FILES = ["dto/PortfolioConfigDto.java", "dto/MinePortfolioDraftSaveRequest.java",
             "dto/teamportfolio/TeamPortfolioConfigDto.java", "dto/teamportfolio/TeamPortfolioDraftSaveRequest.java"]
JAVA_SOURCE = 'import com.alibaba.fastjson2.*;\nimport java.nio.file.*;\nimport java.util.*;\nimport java.nio.charset.StandardCharsets;\nimport java.security.MessageDigest;\npublic class Golden {\n static Object sort(Object value) { if(value instanceof Map<?,?> map) { Map<String,Object> out=new TreeMap<>();map.forEach((k,v)->out.put(String.valueOf(k),sort(v)));return out;}if(value instanceof List<?> list)return list.stream().map(Golden::sort).toList();return value; }\n static String hash(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}\n public static void main(String[]args)throws Exception{\n JSONObject out=JSON.parseObject(Files.readString(Path.of(args[2])));\n JSONObject inputs=JSON.parseObject(Files.readString(Path.of(args[0]))).getJSONObject("requests");\n JSONObject goldens=new JSONObject();\n for(String scope:List.of("portfolios","team-portfolios")){\n  JSONObject variants=new JSONObject();\n  for(String variant:List.of("untouched","edited")){\n   JSONObject input=inputs.getJSONObject(scope).getJSONObject(variant);\n   String canonical;\n   if(scope.equals("portfolios")){var dto=input.to(legacy.MinePortfolioDraftSaveRequest.class);canonical=JSON.toJSONString(sort(JSON.parse(JSON.toJSONString(dto))));}\n   else {var dto=input.to(legacy.TeamPortfolioDraftSaveRequest.class);JSONObject fingerprint=new JSONObject();fingerprint.put("clientRevision",dto.getClientRevision());fingerprint.put("config",dto.getConfig());canonical=JSON.toJSONString(fingerprint,JSONWriter.Feature.MapSortField);}\n   variants.put(variant,JSONObject.of("request",input,"canonical",canonical,"hash",hash(canonical)));\n  }\n  goldens.put(scope,variants);\n }\n out.put("requests",goldens);Files.writeString(Path.of(args[1]),JSON.toJSONString(out,JSONWriter.Feature.PrettyFormat)+"\\n");\n }\n}\n'

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath", required=True, help="本地 Maven 测试依赖和 target/classes 的 classpath")
    args = parser.parse_args()
    java_home = Path(os.environ["JAVA_HOME"]) / "bin"
    with tempfile.TemporaryDirectory(prefix="wefolio-old-font-dto-") as folder:
        temp = Path(folder)
        hashes = {}
        for name in DTO_FILES:
            repo_path = DTO_ROOT + name
            source = subprocess.check_output(["git", "show", f"{BASE}:{repo_path}"], cwd=ROOT).decode()
            hashes[repo_path] = hashlib.sha256(source.encode()).hexdigest()
            source = source.replace("package com.jxc.wefolio.dto.teamportfolio;", "package legacy;").replace(
                "package com.jxc.wefolio.dto;", "package legacy;")
            if name == "dto/PortfolioConfigDto.java":
                source = source.replace("import lombok.Data;", "import lombok.Data;\nimport com.jxc.wefolio.dto.BackgroundAudioConfigDto;")
            (temp / Path(name).name).write_text(source)
        (temp / "Golden.java").write_text(JAVA_SOURCE)
        provenance = temp / "provenance.json"
        provenance.write_text(json.dumps({"sourceCommit": BASE, "dtoSha256": hashes}))
        subprocess.run([str(java_home / "javac"), "-cp", args.classpath, "-d", str(temp),
                        *map(str, temp.glob("*.java"))], check=True)
        subprocess.run([str(java_home / "java"), "-cp", str(temp) + os.pathsep + args.classpath, "Golden",
                        str(ROOT / "projects/miniapp/tests/fixtures/portfolio-font-legacy-requests.json"),
                        str(MODULE / "src/test/resources/portfolio-font-legacy-goldens.json"), str(provenance)], check=True)

if __name__ == "__main__":
    main()
