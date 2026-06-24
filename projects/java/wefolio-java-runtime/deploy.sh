#!/bin/bash
set -eo pipefail

# === 切换到脚本所在目录（确保从任意位置执行都能正确找到 pom.xml 和 target/），执行完回到原目录 ===
ORIG_DIR="$(pwd)"
trap 'cd "$ORIG_DIR"' EXIT
cd "$(dirname "$0")"

# === 配置 ===
SERVER="root@49.235.146.161"
REMOTE_DIR="/root/java"
JAR_NAME="wefolio-java-runtime.jar"
SERVICE_NAME="wefolio.service"

# === 错误处理 ===
die() {
    echo "[失败] $1" >&2
    exit 1
}

# === 1. 切换 JDK ===
echo ">>> 切换 JDK 21..."
export JAVA_HOME="/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home"
export PATH="${JAVA_HOME}/bin:${PATH}"
java -version 2>&1 || die "JDK 切换失败"

# === 2. 构建 ===
echo ">>> 构建 JAR..."
mvn clean package -DskipTests -q || die "Maven 构建失败，请检查编译错误"

# === 3. 上传（服务仍在运行，先传文件）===
echo ">>> 上传 JAR 到 ${SERVER}:${REMOTE_DIR}/ ..."
scp "target/${JAR_NAME}" "${SERVER}:${REMOTE_DIR}/${JAR_NAME}" || die "SCP 上传失败，请检查网络或 SSH 配置"

# === 4. 覆盖 JAR（服务仍在运行，先替换文件）===
echo ">>> 覆盖 JAR..."
ssh "${SERVER}" "cp ${REMOTE_DIR}/${JAR_NAME} ${REMOTE_DIR}/app.jar" || die "覆盖 JAR 失败"

# === 5. 重启服务（短暂停顿）===
echo ">>> 重启服务 ${SERVICE_NAME}..."
ssh "${SERVER}" "systemctl restart ${SERVICE_NAME}" || die "重启服务失败"

# === 6. 查看状态 ===
echo ">>> 服务状态..."
ssh "${SERVER}" "systemctl status ${SERVICE_NAME} --no-pager" || die "获取服务状态失败"

echo ">>> 部署完成"
