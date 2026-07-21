#!/usr/bin/env bash
set -uo pipefail

# 本脚本在本机执行，通过单次 SSH 会话只读采集服务器和 Java 服务状态。
readonly DEFAULT_SERVER="root@49.235.146.161"
readonly SERVER="${1:-${DEFAULT_SERVER}}"
readonly SSH_COMMAND="${SSH_BIN:-ssh}"

printf '正在连接服务器：%s\n' "${SERVER}"
LC_ALL=C LANG=C "${SSH_COMMAND}" \
    -o BatchMode=yes \
    -o ConnectTimeout=8 \
    "${SERVER}" \
    env LC_ALL=C LANG=C bash -s <<'REMOTE_SCRIPT'
set -uo pipefail

CPU_IDLE=0
CPU_TOTAL=0

print_section() {
    printf '\n========== %s ==========\n' "$1"
}

command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# 读取一次 Linux CPU 累计时间，用于计算采样区间使用率。
read_cpu_sample() {
    local cpu user nice system idle iowait irq softirq steal guest guest_nice
    read -r cpu user nice system idle iowait irq softirq steal guest guest_nice < /proc/stat
    CPU_IDLE=$((idle + iowait))
    CPU_TOTAL=$((user + nice + system + idle + iowait + irq + softirq + steal))
}

print_server_status() {
    local first_idle first_total idle_delta total_delta cpu_count

    print_section "服务器资源"
    printf '主机名：%s\n' "$(hostname 2>/dev/null || printf '不可用')"
    printf '系统时间：%s\n' "$(date '+%Y-%m-%d %H:%M:%S %Z')"
    printf '内核：%s\n' "$(uname -srmo 2>/dev/null || printf '不可用')"
    printf '运行时长/负载：%s\n' "$(uptime 2>/dev/null || printf '不可用')"

    cpu_count="$(getconf _NPROCESSORS_ONLN 2>/dev/null || nproc 2>/dev/null || printf '不可用')"
    printf 'CPU 逻辑核数：%s\n' "${cpu_count}"
    if [[ -r /proc/stat ]]; then
        read_cpu_sample
        first_idle="${CPU_IDLE}"
        first_total="${CPU_TOTAL}"
        sleep 1
        read_cpu_sample
        idle_delta=$((CPU_IDLE - first_idle))
        total_delta=$((CPU_TOTAL - first_total))
        awk -v idle="${idle_delta}" -v total="${total_delta}" \
            'BEGIN { printf "CPU 使用率：%.1f%%\n", (total > 0 ? (1 - idle / total) * 100 : 0) }'
    else
        printf 'CPU 使用率：不可用（无法读取 /proc/stat）\n'
    fi

    printf '\n内存与 Swap：\n'
    if command_exists free; then
        free -h
        free -b | awk '
            function human_size(bytes, unit_index, units) {
                split("B KiB MiB GiB TiB", units, " ")
                unit_index = 1
                while (bytes >= 1024 && unit_index < 5) {
                    bytes /= 1024
                    unit_index++
                }
                return sprintf("%.1f %s", bytes, units[unit_index])
            }
            /^Mem:/ {
                printf "内存总量：%s\n", human_size($2)
                printf "内存已用：%s\n", human_size($3)
                printf "内存可用：%s\n", human_size($7)
                printf "内存使用率：%.1f%%\n", ($2 > 0 ? $3 / $2 * 100 : 0)
            }
            /^Swap:/ {
                printf "Swap 使用率：%.1f%%\n", ($2 > 0 ? $3 / $2 * 100 : 0)
            }
        '
    else
        printf '不可用（未找到 free 命令）\n'
    fi

    printf '\n磁盘：\n'
    if command_exists df; then
        df -hT -x tmpfs -x devtmpfs 2>/dev/null || df -hT
    else
        printf '不可用（未找到 df 命令）\n'
    fi
}

run_jcmd() {
    local pid="$1"
    local diagnostic_command="$2"

    if command_exists timeout; then
        timeout 10s jcmd "${pid}" "${diagnostic_command}"
    else
        jcmd "${pid}" "${diagnostic_command}"
    fi
}

print_jvm_status() {
    local label="$1"
    local pid="$2"
    local fd_count diagnostic_command

    print_section "${label} JVM 状态"
    if [[ ! "${pid}" =~ ^[1-9][0-9]*$ ]] || ! kill -0 "${pid}" 2>/dev/null; then
        printf '不可用（没有有效的运行中主 PID）\n'
        return 0
    fi

    printf '%-8s %-12s %-7s %-7s %-12s %-12s %-8s %s\n' \
        'PID' '运行时长' 'CPU%' '内存%' 'RSS(KiB)' 'VSZ(KiB)' '线程数' '进程名'
    ps -p "${pid}" -o pid= -o etime= -o pcpu= -o pmem= -o rss= -o vsz= -o nlwp= -o comm= 2>/dev/null \
        || printf '进程概要不可用\n'

    fd_count="$(find "/proc/${pid}/fd" -mindepth 1 -maxdepth 1 2>/dev/null | wc -l | tr -d ' ')"
    printf '打开文件描述符：%s\n' "${fd_count:-不可用}"

    if ! command_exists jcmd; then
        printf 'JVM 诊断：不可用（服务器未安装 jcmd）\n'
        return 0
    fi

    for diagnostic_command in VM.version VM.uptime GC.heap_info; do
        printf '\n-- jcmd %s --\n' "${diagnostic_command}"
        if ! run_jcmd "${pid}" "${diagnostic_command}" 2>&1; then
            printf 'jcmd 执行失败或超时\n'
        fi
    done
}

print_error_logs() {
    local label="$1"
    local unit="$2"
    local logs

    print_section "${label} 最近 24 小时错误日志（最多 50 条）"
    if ! command_exists journalctl; then
        printf '不可用（未找到 journalctl 命令）\n'
        return 0
    fi

    logs="$(
        journalctl --unit "${unit}" --since "24 hours ago" --no-pager --output short-iso 2>/dev/null \
            | grep -E '(^|[[:space:]])ERROR[[:space:]]|Exception:|Caused by:|OutOfMemoryError' \
            | tail -n 50 \
            || true
    )"

    if [[ -n "${logs}" ]]; then
        printf '%s\n' "${logs}"
    else
        printf '未发现匹配的错误日志。\n'
    fi
}

inspect_service() {
    local label="$1"
    local unit="$2"
    local health_url="$3"
    local state pid response curl_status http_code body
    local process_ok=0
    local health_ok=0

    print_section "${label} 服务健康"

    if command_exists systemctl; then
        state="$(systemctl is-active "${unit}" 2>/dev/null || true)"
        pid="$(
            systemctl show "${unit}" --property MainPID 2>/dev/null \
                | awk -F= '/^MainPID=/{print $2; exit}' \
                || true
        )"
    else
        state="不可用（未找到 systemctl 命令）"
        pid=""
    fi

    if [[ "${pid}" =~ ^[1-9][0-9]*$ ]] && kill -0 "${pid}" 2>/dev/null; then
        process_ok=1
    fi

    if command_exists curl; then
        response="$(
            curl --silent --show-error \
                --connect-timeout 2 \
                --max-time 5 \
                --write-out $'\n%{http_code}' \
                "${health_url}" 2>&1
        )"
        curl_status=$?
        http_code="${response##*$'\n'}"
        body="${response%$'\n'*}"
        if [[ ${curl_status} -eq 0 && "${http_code}" =~ ^2[0-9][0-9]$ ]]; then
            health_ok=1
        fi
    else
        http_code="不可用"
        body="未找到 curl 命令"
    fi

    printf 'systemd：%s\n' "${state:-unknown}"
    printf '主 PID：%s\n' "${pid:-无}"
    printf '健康接口：%s（HTTP %s）\n' "${health_url}" "${http_code:-不可用}"
    printf '健康响应：%.500s\n' "${body:-无}"

    print_jvm_status "${label}" "${pid}"
    print_error_logs "${label}" "${unit}"

    if [[ "${state}" == "active" && ${process_ok} -eq 1 && ${health_ok} -eq 1 ]]; then
        return 0
    fi
    return 1
}

overall_status=0
runtime_result="异常"
job_result="异常"

print_server_status

if inspect_service "Runtime" "wefolio.service" "http://127.0.0.1:8090/api/health"; then
    runtime_result="健康"
else
    overall_status=10
fi

if inspect_service "Job" "wefolio-job.service" "http://127.0.0.1:8091/job-api/health"; then
    job_result="健康"
else
    overall_status=10
fi

print_section "汇总"
printf 'Runtime：%s\n' "${runtime_result}"
printf 'Job：%s\n' "${job_result}"

exit "${overall_status}"
REMOTE_SCRIPT
ssh_status=$?

case "${ssh_status}" in
    0)
        exit 0
        ;;
    10)
        printf '\n[异常] 状态采集完成，但至少一个 Java 服务不健康。\n' >&2
        exit 1
        ;;
    *)
        printf '\n[失败] 无法完成服务器状态采集，SSH/远端命令退出码：%s\n' \
            "${ssh_status}" >&2
        exit "${ssh_status}"
        ;;
esac
