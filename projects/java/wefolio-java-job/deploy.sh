#!/bin/bash
set -eo pipefail

# === 打印当前日期和时间 ===
date '+>>> 当前日期和时间：%Y-%m-%d %H:%M:%S'

# === 切换到脚本所在目录（确保从任意位置执行都能正确找到 pom.xml 和 target/），执行完回到原目录 ===
ORIG_DIR="$(pwd)"
trap 'cd "$ORIG_DIR"' EXIT
cd "$(dirname "$0")"

# === 配置 ===
SERVER="root@49.235.146.161"
REMOTE_DIR="/root/java/job"
JAR_NAME="wefolio-java-job.jar"
UPLOAD_JAR_NAME="${JAR_NAME}.uploading"
SERVICE_NAME="wefolio-job.service"
APP_NAME="wefolio-java-job"
DEPLOY_LABEL="${APP_NAME} / ${SERVICE_NAME}"
DISABLE_URL="https://api.we-folio.dingchenyong.top/job-api/scheduling/disable"
DISABLE_MAX_ATTEMPTS=3
RETRY_DELAY_SECONDS=3
WAIT_AFTER_DISABLE_SECONDS=3

# === 计时工具（毫秒精度）===
_now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }
SCRIPT_START="$(_now_ms)"
NOW_SCRIPT="$SCRIPT_START"
elapsed() {
    local now diff ms
    now="$(_now_ms)"
    diff=$(( now - NOW_SCRIPT ))
    ms="$diff"
    echo "[耗时 ${ms} ms]"
    NOW_SCRIPT="$now"
}

# === 错误处理 ===
die() {
    echo "[失败] $1" >&2
    exit 1
}

# === 1. 切换 JDK ===
echo ">>> 部署服务：${DEPLOY_LABEL}"
echo ">>> 目标服务器：${SERVER}"
echo ">>> 远端目录：${REMOTE_DIR}"
echo ">>> [${DEPLOY_LABEL}] 切换 JDK 21..."
export JAVA_HOME="/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home"
export PATH="${JAVA_HOME}/bin:${PATH}"
java -version 2>&1 || die "JDK 切换失败"
elapsed

# === 2. 构建 ===
echo ">>> [${DEPLOY_LABEL}] 构建 JAR..."
mvn clean package -DskipTests -q || die "Maven 构建失败，请检查编译错误"
elapsed

# === 3. 准备远端目录 ===
echo ">>> [${DEPLOY_LABEL}] 准备远端目录 ${SERVER}:${REMOTE_DIR}/ ..."
ssh "${SERVER}" "mkdir -p ${REMOTE_DIR}" || die "远端目录创建失败"
elapsed

# === 4. 上传（服务仍在运行，先上传到临时文件）===
echo ">>> [${DEPLOY_LABEL}] 上传 JAR 到 ${SERVER}:${REMOTE_DIR}/${UPLOAD_JAR_NAME} ..."
scp "target/${JAR_NAME}" "${SERVER}:${REMOTE_DIR}/${UPLOAD_JAR_NAME}" || die "SCP 上传失败，请检查网络或 SSH 配置"
elapsed

# === 5. 覆盖 JAR（同目录内 mv，避免直接写正在运行的 JAR 文件）===
echo ">>> [${DEPLOY_LABEL}] 覆盖 JAR..."
ssh "${SERVER}" "mv -f ${REMOTE_DIR}/${UPLOAD_JAR_NAME} ${REMOTE_DIR}/${JAR_NAME}" || die "覆盖 JAR 失败"
elapsed

# === 6. 停用旧实例调度并等待排空 ===
echo ">>> [${DEPLOY_LABEL}] 停用旧实例调度 ${DISABLE_URL} ..."
ADMIN_SECRET="$(grep '^POINT_ADMIN_SECRET=' .env | cut -d= -f2)" || die "读取 .env 运维密钥失败（缺少 POINT_ADMIN_SECRET）"
disable_attempt=1
while [ "${disable_attempt}" -le "${DISABLE_MAX_ATTEMPTS}" ]; do
    disable_response="$(curl -s -m 15 -w $'\n%{http_code}' \
        -X POST -H "X-Admin-Point-Secret: ${ADMIN_SECRET}" "${DISABLE_URL}")" \
        || die "停用接口请求失败（网络不可达或服务异常）"
    disable_code="${disable_response##*$'\n'}"
    disable_body="${disable_response%$'\n'*}"
    case "${disable_code}" in
        200)
            echo ">>> [${DEPLOY_LABEL}] 停用成功：${disable_body}"
            break
            ;;
        503)
            echo ">>> [警告] 第 ${disable_attempt}/${DISABLE_MAX_ATTEMPTS} 次停用未排空（响应：${disable_body}），等待 ${RETRY_DELAY_SECONDS}s 后重试..."
            if [ "${disable_attempt}" -ge "${DISABLE_MAX_ATTEMPTS}" ]; then
                die "旧实例仍有运行中任务未排空，已保留旧实例未重启，请稍后重试或人工排查"
            fi
            sleep "${RETRY_DELAY_SECONDS}"
            ;;
        401)
            die "停用接口返回 401：请确认生产实例注入了 ADMIN_POINT_SECRET 且与本地 .env 一致"
            ;;
        *)
            die "停用接口返回异常状态码 ${disable_code}：${disable_body}"
            ;;
    esac
    disable_attempt=$((disable_attempt + 1))
done
echo ">>> [${DEPLOY_LABEL}] 已停用，等待 ${WAIT_AFTER_DISABLE_SECONDS}s 后继续发布..."
sleep "${WAIT_AFTER_DISABLE_SECONDS}"
elapsed

# === 7. 重启服务 ===
echo ">>> [${DEPLOY_LABEL}] 重启服务 ${SERVICE_NAME}..."
ssh "${SERVER}" "systemctl restart ${SERVICE_NAME}" || die "重启服务失败"
elapsed

# === 8. 查看状态 ===
echo ">>> [${DEPLOY_LABEL}] 服务状态..."
ssh "${SERVER}" "systemctl status ${SERVICE_NAME} --no-pager" || die "获取服务状态失败"
elapsed

echo ">>> [${DEPLOY_LABEL}] 部署完成"
echo ">>> [${DEPLOY_LABEL}] 总耗时 $(( $(_now_ms) - SCRIPT_START )) ms"
