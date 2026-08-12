#!/bin/bash
set -eo pipefail

# === 脚本目录 ===
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# === 配置 ===
OLD_SERVER="root@49.235.146.161"
NEW_SERVER="root@124.222.148.233"
NGINX_SERVER="${OLD_SERVER}"
REMOTE_DIR="/root/java"
JAR_NAME="wefolio-java-runtime.jar"
SERVICE_NAME="wefolio.service"
APP_NAME="wefolio-java-runtime"
DEPLOY_LABEL="${APP_NAME} / ${SERVICE_NAME}"
LOCAL_JAR="target/${JAR_NAME}"
ARTIFACT_SHA256=""
NGINX_CONFIG="/etc/nginx/conf.d/myapp.conf"
NGINX_CANDIDATE="${NGINX_CONFIG}.rolling-candidate"
NGINX_BACKUP="${NGINX_CONFIG}.rolling-backup"
HEALTH_INITIAL_DELAY_SECONDS=3
HEALTH_TIMEOUT_SECONDS=30
OLD_HEALTH_URL="http://127.0.0.1:8090/api/health"
NEW_HEALTH_URL="http://10.0.4.7:8090/api/health"

# === 计时工具（毫秒精度）===
_now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }

run_timed_step() {
    local label="$1"
    shift
    local started_at finished_at elapsed_ms status

    started_at="$(_now_ms)"
    if "$@"; then
        status=0
    else
        status=$?
    fi
    finished_at="$(_now_ms)"
    elapsed_ms=$((finished_at - started_at))

    if [[ "${status}" -eq 0 ]]; then
        echo ">>> [耗时] ${label}：成功，${elapsed_ms} ms"
    else
        echo ">>> [耗时] ${label}：失败，${elapsed_ms} ms" >&2
    fi
    return "${status}"
}

# === 错误处理 ===
die() {
    echo "[失败] $1" >&2
    exit 1
}

# === 外部命令边界，测试时替换为本地实现，避免访问生产环境 ===
ssh_exec() {
    ssh -o BatchMode=yes -o ConnectTimeout=8 "$1" "$2"
}

scp_download() {
    scp -o BatchMode=yes -o ConnectTimeout=8 "$1:$2" "$3"
}

scp_upload() {
    scp -o BatchMode=yes -o ConnectTimeout=8 "$1" "$2:$3"
}

now_seconds() {
    date +%s
}

sleep_seconds() {
    sleep "$1"
}

# === 生成 Runtime upstream 的目标路由状态，只修改指定 upstream ===
render_nginx_config() {
    local mode="$1"
    local input_file="$2"
    local output_file="$3"
    local status

    case "${mode}" in
        new_only|old_only|round_robin) ;;
        *)
            echo "[失败] 不支持的 Nginx 路由状态：${mode}" >&2
            return 2
            ;;
    esac

    [[ -f "${input_file}" ]] || return 2

    awk -v mode="${mode}" '
        BEGIN {
            in_runtime = 0
            runtime_count = 0
            old_count = 0
            new_count = 0
        }

        /^[[:space:]]*upstream[[:space:]]+wefolio_runtime[[:space:]]*\{[[:space:]]*$/ {
            runtime_count++
            in_runtime = 1
            print
            next
        }

        in_runtime && /^[[:space:]]*}[[:space:]]*$/ {
            in_runtime = 0
            print
            next
        }

        in_runtime && /^[[:space:]]*least_conn[[:space:]]*;[[:space:]]*$/ {
            next
        }

        in_runtime && /^[[:space:]]*server[[:space:]]+127\.0\.0\.1:8090([[:space:]]|;)/ {
            old_count++
            suffix = mode == "new_only" ? " down" : ""
            print "    server 127.0.0.1:8090 max_fails=3 fail_timeout=10s" suffix ";"
            next
        }

        in_runtime && /^[[:space:]]*server[[:space:]]+10\.0\.4\.7:8090([[:space:]]|;)/ {
            new_count++
            suffix = mode == "old_only" ? " down" : ""
            print "    server 10.0.4.7:8090 max_fails=3 fail_timeout=10s" suffix ";"
            next
        }

        { print }

        END {
            if (in_runtime || runtime_count != 1 || old_count != 1 || new_count != 1) {
                exit 42
            }
        }
    ' "${input_file}" >"${output_file}"
    status=$?

    if [[ "${status}" -ne 0 ]]; then
        rm -f "${output_file}"
        return "${status}"
    fi
}

# === 安全切换 Runtime upstream，失败时恢复切换前的 Nginx 配置 ===
switch_nginx_mode() {
    local mode="$1"
    local temp_dir original_file candidate_file remote_command status

    temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/wefolio-nginx.XXXXXX")" || return 1
    original_file="${temp_dir}/myapp.conf"
    candidate_file="${temp_dir}/myapp.candidate"

    if ! scp_download "${NGINX_SERVER}" "${NGINX_CONFIG}" "${original_file}"; then
        rm -rf "${temp_dir}"
        return 1
    fi

    if ! render_nginx_config "${mode}" "${original_file}" "${candidate_file}"; then
        rm -rf "${temp_dir}"
        return 1
    fi

    if ! scp_upload "${candidate_file}" "${NGINX_SERVER}" "${NGINX_CANDIDATE}"; then
        rm -rf "${temp_dir}"
        return 1
    fi

    remote_command="set -u
config='${NGINX_CONFIG}'
candidate='${NGINX_CANDIDATE}'
backup='${NGINX_BACKUP}'
restore_previous() {
    cp -f \"\${backup}\" \"\${config}\" || return 1
    nginx -t || return 1
    systemctl reload nginx || return 1
}
cp -f \"\${config}\" \"\${backup}\" || exit 1
mv -f \"\${candidate}\" \"\${config}\" || exit 1
if ! nginx -t; then
    restore_previous
    exit 1
fi
if ! systemctl reload nginx; then
    restore_previous
    exit 1
fi
rm -f \"\${backup}\""

    ssh_exec "${NGINX_SERVER}" "${remote_command}"
    status=$?
    rm -rf "${temp_dir}"
    return "${status}"
}

# === 校验上传包后保留一份固定备份，再原子替换正式 JAR ===
install_remote_jar() {
    local server="$1"
    local formal_jar="${REMOTE_DIR}/${JAR_NAME}"
    local uploading_jar="${REMOTE_DIR}/${JAR_NAME}.uploading"
    local backup_jar="${REMOTE_DIR}/${JAR_NAME}.backup"
    local remote_command

    [[ -f "${LOCAL_JAR}" ]] || return 1
    [[ "${ARTIFACT_SHA256}" =~ ^[0-9a-f]{64}$ ]] || return 1

    scp_upload "${LOCAL_JAR}" "${server}" "${uploading_jar}" || return 1

    remote_command="set -eu
expected='${ARTIFACT_SHA256}'
formal='${formal_jar}'
uploading='${uploading_jar}'
backup='${backup_jar}'
actual=\$(sha256sum \"\${uploading}\" | awk '{print \$1}')
if [ \"\${actual}\" != \"\${expected}\" ]; then
    echo '[失败] 上传 JAR 的 SHA-256 与本地不一致' >&2
    exit 1
fi
cp -f \"\${formal}\" \"\${backup}\"
mv -f \"\${uploading}\" \"\${formal}\""

    ssh_exec "${server}" "${remote_command}"
}

# === 从 Nginx 所在服务器检查节点健康，避免依赖开发机访问内网 ===
check_health_once() {
    local health_url="$1"
    local remote_command

    remote_command="body=\$(curl --silent --show-error --fail --max-time 2 '${health_url}') || exit 1
printf '%s' \"\${body}\" | grep -Eq '\"status\"[[:space:]]*:[[:space:]]*\"UP\"'"
    ssh_exec "${NGINX_SERVER}" "${remote_command}"
}

wait_for_health() {
    local health_url="$1"
    local label="$2"
    local start_time deadline current_time

    start_time="$(now_seconds)" || return 1
    deadline=$((start_time + HEALTH_TIMEOUT_SECONDS))
    sleep_seconds "${HEALTH_INITIAL_DELAY_SECONDS}"

    while true; do
        if check_health_once "${health_url}"; then
            echo ">>> [${label}] 健康检查通过"
            return 0
        fi

        current_time="$(now_seconds)" || return 1
        if [[ "${current_time}" -ge "${deadline}" ]]; then
            echo "[失败] [${label}] ${HEALTH_TIMEOUT_SECONDS} 秒内健康检查未通过" >&2
            return 1
        fi
        sleep_seconds 1
    done
}

# === 发布单个节点；失败时保持摘流状态，不自动恢复备份 ===
deploy_node() {
    local server="$1"
    local health_url="$2"
    local label="$3"
    local server_ip

    server_ip="${server#*@}"

    echo ">>> [${label}] 上传、校验并替换 JAR..."
    run_timed_step "${label}（${server_ip}）JAR 上传安装" install_remote_jar "${server}" || return 1

    echo ">>> [${label}] 重启 ${SERVICE_NAME}..."
    run_timed_step "${label}（${server_ip}）服务重启" ssh_exec "${server}" "systemctl restart ${SERVICE_NAME}" || return 1

    run_timed_step "${label}（${server_ip}）健康检查" wait_for_health "${health_url}" "${label}"
}

# === 本地只构建一次，两个节点部署同一个 SHA-256 包 ===
build_artifact() {
    echo ">>> [${DEPLOY_LABEL}] 使用 JDK 21 构建 JAR..."
    export JAVA_HOME="/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home"
    export PATH="${JAVA_HOME}/bin:${PATH}"

    java -version >/dev/null 2>&1 || return 1
    mvn clean package -DskipTests -q || return 1
    [[ -f "${LOCAL_JAR}" ]] || return 1

    ARTIFACT_SHA256="$(shasum -a 256 "${LOCAL_JAR}" | awk '{print $1}')" || return 1
    [[ "${ARTIFACT_SHA256}" =~ ^[0-9a-f]{64}$ ]]
}

# === 验证当前配置结构和两个节点健康，切流前不修改线上状态 ===
preflight() {
    local temp_dir current_config rendered_config status

    echo ">>> 发布前检查 Nginx 配置和两个 Runtime 节点..."
    ssh_exec "${NGINX_SERVER}" "nginx -t" || return 1

    temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/wefolio-preflight.XXXXXX")" || return 1
    current_config="${temp_dir}/myapp.conf"
    rendered_config="${temp_dir}/myapp.round-robin"

    if ! scp_download "${NGINX_SERVER}" "${NGINX_CONFIG}" "${current_config}"; then
        rm -rf "${temp_dir}"
        return 1
    fi
    render_nginx_config "round_robin" "${current_config}" "${rendered_config}"
    status=$?
    rm -rf "${temp_dir}"
    [[ "${status}" -eq 0 ]] || return 1

    check_health_once "${OLD_HEALTH_URL}" || return 1
    check_health_once "${NEW_HEALTH_URL}" || return 1
}

# === 双节点滚动发布状态机 ===
rolling_deploy() {
    run_timed_step "本地构建" build_artifact || {
        echo "[失败] Runtime 构建失败，未修改线上状态" >&2
        return 1
    }
    run_timed_step "发布前检查（Nginx：${NGINX_SERVER#*@}，老节点：${OLD_SERVER#*@}，新节点：${NEW_SERVER#*@}）" preflight || {
        echo "[失败] 发布前检查失败，未修改线上状态" >&2
        return 1
    }
    run_timed_step "Nginx 切流 new_only（${NGINX_SERVER#*@}）" switch_nginx_mode "new_only" || {
        echo "[失败] 无法将流量切换到新节点" >&2
        return 1
    }
    run_timed_step "老节点发布（${OLD_SERVER#*@}）" deploy_node "${OLD_SERVER}" "${OLD_HEALTH_URL}" "老节点" || {
        echo "[失败] 老节点发布失败，Nginx 保持 new_only" >&2
        return 1
    }

    run_timed_step "Nginx 切流 old_only（${NGINX_SERVER#*@}）" switch_nginx_mode "old_only" || {
        echo "[失败] 无法将流量切换到老节点，Nginx 已恢复 new_only" >&2
        return 1
    }
    run_timed_step "新节点发布（${NEW_SERVER#*@}）" deploy_node "${NEW_SERVER}" "${NEW_HEALTH_URL}" "新节点" || {
        echo "[失败] 新节点发布失败，Nginx 保持 old_only" >&2
        return 1
    }

    run_timed_step "Nginx 切流 round_robin（${NGINX_SERVER#*@}）" switch_nginx_mode "round_robin" || {
        echo "[失败] 无法恢复双节点轮询，Nginx 已恢复 old_only" >&2
        return 1
    }
}

main() {
    (
        cd "${SCRIPT_DIR}"

        echo ">>> 部署服务：${DEPLOY_LABEL}"
        echo ">>> 发布顺序：${OLD_SERVER} -> ${NEW_SERVER}"
        echo ">>> Nginx 配置：${NGINX_SERVER}:${NGINX_CONFIG}"
        run_timed_step "Runtime 双节点滚动发布" rolling_deploy || die "Runtime 双节点滚动发布失败"

        echo ">>> [${DEPLOY_LABEL}] 双节点滚动发布完成"
    )
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
