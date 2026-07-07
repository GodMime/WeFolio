#!/usr/bin/env bash
set -euo pipefail

# 腾讯云数据万象真实接口连通性测试脚本。
# 只读取 .env 中的 COS 配置，避免 DB_URL 等包含特殊字符时被 shell 解析。

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
cd "${SCRIPT_DIR}"

ENV_FILE="${ENV_FILE:-.env}"
DEFAULT_JAVA_HOME="/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home"
COS_TEST_JAVA_HOME="${COS_TEST_JAVA_HOME:-${DEFAULT_JAVA_HOME}}"
TEST_CLASS="CosTencentCiAuditIntegrationTest"
TEST_SWITCH="wefolio.tencent-ci.integration-test"
REQUIRED_COS_ENV=(
  "COS_SECRET_ID"
  "COS_SECRET_KEY"
  "COS_REGION"
  "COS_BUCKET_NAME"
)

load_cos_env() {
  if [[ ! -f "${ENV_FILE}" ]]; then
    echo "未找到 ${ENV_FILE}，请先在当前目录准备 COS 配置。"
    exit 1
  fi

  local line
  while IFS= read -r line || [[ -n "${line}" ]]; do
    line="${line%$'\r'}"
    case "${line}" in
      COS_SECRET_ID=*|COS_SECRET_KEY=*|COS_REGION=*|COS_BUCKET_NAME=*)
        export "${line}"
        ;;
    esac
  done < "${ENV_FILE}"
}

validate_required_env() {
  local name
  for name in "${REQUIRED_COS_ENV[@]}"; do
    if [[ -z "${!name:-}" ]]; then
      echo "缺少环境变量：${name}。请检查 ${ENV_FILE}。"
      exit 1
    fi
  done
}

prepare_java_home() {
  export JAVA_HOME="${COS_TEST_JAVA_HOME}"

  if [[ ! -x "${JAVA_HOME}/bin/java" ]]; then
    echo "JAVA_HOME 不可用：${JAVA_HOME}"
    echo "请安装 JDK 21，或手动设置 COS_TEST_JAVA_HOME 后重试。"
    exit 1
  fi

  local java_version
  java_version="$("${JAVA_HOME}/bin/java" -version 2>&1 | head -n 1)"
  if [[ "${java_version}" != *\"21* ]]; then
    echo "当前测试必须使用 JDK 21，实际为：${java_version}"
    echo "请设置 COS_TEST_JAVA_HOME 指向 JDK 21 后重试。"
    exit 1
  fi
}

validate_maven() {
  if ! command -v mvn >/dev/null 2>&1; then
    echo "未找到 mvn 命令，请先安装 Maven。"
    exit 1
  fi
}

load_cos_env
validate_required_env
prepare_java_home
validate_maven

echo "开始执行腾讯云 COS/数据万象真实接口连通性测试..."
echo "测试类：${TEST_CLASS}"
echo "JDK：$("${JAVA_HOME}/bin/java" -version 2>&1 | head -n 1)"

mvn \
  -Dtest="${TEST_CLASS}" \
  -D"${TEST_SWITCH}"=true \
  test \
  "$@"
