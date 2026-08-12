#!/usr/bin/env bash
set -euo pipefail

# 服务器状态脚本的本机行为测试，不连接真实服务器。
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATUS_SCRIPT="${SCRIPT_DIR}/../server-status.sh"
TEST_TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TEST_TMP_DIR}"' EXIT

# 用假 SSH 命令记录每次调用并消费远端脚本内容，避免测试访问网络。
printf '%s\n' \
    '#!/usr/bin/env bash' \
    'count="$(cat "${FAKE_SSH_CALL_COUNT_FILE}" 2>/dev/null || printf '\''0'\'')"' \
    'count=$((count + 1))' \
    'printf '\''%s\n'\'' "${count}" > "${FAKE_SSH_CALL_COUNT_FILE}"' \
    'printf '\''%s\n'\'' "$*" >> "${FAKE_SSH_ARGS_FILE}"' \
    'while IFS= read -r _line; do :; done' \
    'if [[ "${FAKE_SSH_FAIL_CALL:-0}" -eq "${count}" ]]; then' \
    '    exit "${FAKE_SSH_FAIL_STATUS:-255}"' \
    'fi' \
    'exit "${FAKE_SSH_EXIT_CODE:-0}"' \
    > "${TEST_TMP_DIR}/fake-ssh"
chmod +x "${TEST_TMP_DIR}/fake-ssh"

assert_contains() {
    local haystack="$1"
    local needle="$2"
    if [[ "${haystack}" != *"${needle}"* ]]; then
        printf '断言失败：未找到 %s\n' "${needle}" >&2
        exit 1
    fi
}

assert_not_contains() {
    local haystack="$1"
    local needle="$2"
    if [[ "${haystack}" == *"${needle}"* ]]; then
        printf '断言失败：不应包含 %s\n' "${needle}" >&2
        exit 1
    fi
}

assert_equals() {
    local expected="$1"
    local actual="$2"
    local message="$3"
    if [[ "${actual}" != "${expected}" ]]; then
        printf '断言失败：%s，期望=%s，实际=%s\n' "${message}" "${expected}" "${actual}" >&2
        exit 1
    fi
}

reset_fake_ssh() {
    : >"${FAKE_SSH_ARGS_FILE}"
    printf '0' >"${FAKE_SSH_CALL_COUNT_FILE}"
}

export FAKE_SSH_ARGS_FILE="${TEST_TMP_DIR}/ssh-args"
export FAKE_SSH_CALL_COUNT_FILE="${TEST_TMP_DIR}/ssh-call-count"

if [[ ! -x "${STATUS_SCRIPT}" ]]; then
    printf '断言失败：服务器状态脚本应具有可执行权限\n' >&2
    exit 1
fi

reset_fake_ssh
SSH_BIN="${TEST_TMP_DIR}/fake-ssh" bash "${STATUS_SCRIPT}" >/dev/null
assert_equals "2" "$(<"${FAKE_SSH_CALL_COUNT_FILE}")" "默认模式必须检查两台服务器"
DEFAULT_OLD_CALL="$(sed -n '1p' "${FAKE_SSH_ARGS_FILE}")"
DEFAULT_NEW_CALL="$(sed -n '2p' "${FAKE_SSH_ARGS_FILE}")"
assert_contains "${DEFAULT_OLD_CALL}" "root@49.235.146.161 env LC_ALL=C LANG=C bash -s -- 老节点 root@49.235.146.161 yes"
assert_contains "${DEFAULT_NEW_CALL}" "root@124.222.148.233 env LC_ALL=C LANG=C bash -s -- 新节点 root@124.222.148.233 no"

reset_fake_ssh
SSH_BIN="${TEST_TMP_DIR}/fake-ssh" bash "${STATUS_SCRIPT}" \
    ops@old.example.com ops@new.example.com >/dev/null
OVERRIDE_OLD_CALL="$(sed -n '1p' "${FAKE_SSH_ARGS_FILE}")"
OVERRIDE_NEW_CALL="$(sed -n '2p' "${FAKE_SSH_ARGS_FILE}")"
assert_contains "${OVERRIDE_OLD_CALL}" "ops@old.example.com env LC_ALL=C LANG=C bash -s -- 老节点 ops@old.example.com yes"
assert_contains "${OVERRIDE_NEW_CALL}" "ops@new.example.com env LC_ALL=C LANG=C bash -s -- 新节点 ops@new.example.com no"

reset_fake_ssh
SSH_BIN="${TEST_TMP_DIR}/fake-ssh" bash "${STATUS_SCRIPT}" ops@example.com >/dev/null
ONE_ARG_OLD_CALL="$(sed -n '1p' "${FAKE_SSH_ARGS_FILE}")"
ONE_ARG_NEW_CALL="$(sed -n '2p' "${FAKE_SSH_ARGS_FILE}")"
assert_contains "${ONE_ARG_OLD_CALL}" "ops@example.com env LC_ALL=C LANG=C bash -s -- 老节点 ops@example.com yes"
assert_contains "${ONE_ARG_NEW_CALL}" "root@124.222.148.233 env LC_ALL=C LANG=C bash -s -- 新节点 root@124.222.148.233 no"

if FAKE_SSH_EXIT_CODE=255 SSH_BIN="${TEST_TMP_DIR}/fake-ssh" \
    bash "${STATUS_SCRIPT}" >/dev/null 2>&1; then
    printf '断言失败：SSH 失败时脚本不应返回 0\n' >&2
    exit 1
fi

reset_fake_ssh
if FIRST_SSH_FAILURE_OUTPUT="$(
    FAKE_SSH_FAIL_CALL=1 FAKE_SSH_FAIL_STATUS=255 \
        SSH_BIN="${TEST_TMP_DIR}/fake-ssh" bash "${STATUS_SCRIPT}" 2>&1
)"; then
    printf '断言失败：老节点 SSH 失败时脚本不应返回 0\n' >&2
    exit 1
fi
assert_equals "2" "$(<"${FAKE_SSH_CALL_COUNT_FILE}")" "老节点 SSH 失败后仍必须检查新节点"
assert_contains "${FIRST_SSH_FAILURE_OUTPUT}" "老节点（root@49.235.146.161）：采集失败（退出码 255）"
assert_contains "${FIRST_SSH_FAILURE_OUTPUT}" "新节点（root@124.222.148.233）：健康"

reset_fake_ssh
if OLD_SERVICE_FAILURE_OUTPUT="$(
    FAKE_SSH_FAIL_CALL=1 FAKE_SSH_FAIL_STATUS=10 \
        SSH_BIN="${TEST_TMP_DIR}/fake-ssh" bash "${STATUS_SCRIPT}" 2>&1
)"; then
    printf '断言失败：老节点服务异常时脚本不应返回 0\n' >&2
    exit 1
fi
assert_equals "2" "$(<"${FAKE_SSH_CALL_COUNT_FILE}")" "老节点服务异常后仍必须检查新节点"
assert_contains "${OLD_SERVICE_FAILURE_OUTPUT}" "老节点（root@49.235.146.161）：服务异常"
assert_contains "${OLD_SERVICE_FAILURE_OUTPUT}" "新节点（root@124.222.148.233）：健康"

reset_fake_ssh
if SECOND_SSH_FAILURE_OUTPUT="$(
    FAKE_SSH_FAIL_CALL=2 FAKE_SSH_FAIL_STATUS=255 \
        SSH_BIN="${TEST_TMP_DIR}/fake-ssh" bash "${STATUS_SCRIPT}" 2>&1
)"; then
    printf '断言失败：新节点 SSH 失败时脚本不应返回 0\n' >&2
    exit 1
fi
assert_equals "2" "$(<"${FAKE_SSH_CALL_COUNT_FILE}")" "新节点失败前必须完成两次服务器检查"
assert_contains "${SECOND_SSH_FAILURE_OUTPUT}" "老节点（root@49.235.146.161）：健康"
assert_contains "${SECOND_SSH_FAILURE_OUTPUT}" "新节点（root@124.222.148.233）：采集失败（退出码 255）"

SCRIPT_CONTENT="$(<"${STATUS_SCRIPT}")"
assert_contains "${SCRIPT_CONTENT}" "wefolio.service"
assert_contains "${SCRIPT_CONTENT}" "http://127.0.0.1:8090/api/health"
assert_contains "${SCRIPT_CONTENT}" "wefolio-job.service"
assert_contains "${SCRIPT_CONTENT}" "http://127.0.0.1:8091/job-api/health"
assert_contains "${SCRIPT_CONTENT}" 'LC_ALL=C LANG=C "${SSH_COMMAND}"'
assert_contains "${SCRIPT_CONTENT}" "env LC_ALL=C LANG=C bash -s"
assert_contains "${SCRIPT_CONTENT}" "GC.heap_info"
assert_contains "${SCRIPT_CONTENT}" "24 hours ago"
assert_contains "${SCRIPT_CONTENT}" "tail -n 50"
assert_contains "${SCRIPT_CONTENT}" 'printf "CPU 使用率：%.1f%%\n", (total > 0 ?'
assert_contains "${SCRIPT_CONTENT}" 'printf "内存总量：%s\n"'
assert_contains "${SCRIPT_CONTENT}" 'printf "内存已用：%s\n"'
assert_contains "${SCRIPT_CONTENT}" 'printf "内存可用：%s\n"'
assert_contains "${SCRIPT_CONTENT}" 'printf "内存使用率：%.1f%%\n", ($2 > 0 ?'
assert_contains "${SCRIPT_CONTENT}" 'printf "Swap 使用率：%.1f%%\n", ($2 > 0 ?'
assert_not_contains "${SCRIPT_CONTENT}" "--value"
assert_contains "${SCRIPT_CONTENT}" "MainPID="
assert_not_contains "${SCRIPT_CONTENT}" "-o pid=,etimes=,%cpu=,%mem=,rss=,vsz=,nlwp=,comm="
assert_contains "${SCRIPT_CONTENT}" "-o pid= -o etime= -o pcpu= -o pmem= -o rss= -o vsz= -o nlwp= -o comm="
assert_not_contains "${SCRIPT_CONTENT}" "grep -Ei 'ERROR|Exception|Caused by|OutOfMemoryError'"
assert_contains "${SCRIPT_CONTENT}" "grep -E '(^|[[:space:]])ERROR[[:space:]]|Exception:|Caused by:|OutOfMemoryError'"

LOG_SAMPLE="$(printf '%s\n' \
    'INFO GlobalExceptionHandler - Authentication required' \
    'INFO errorType=FAILED' \
    'ERROR Service - failed' \
    'com.example.BusinessException: failed' \
    'Caused by: java.io.IOException')"
FILTERED_LOGS="$(printf '%s\n' "${LOG_SAMPLE}" \
    | grep -E '(^|[[:space:]])ERROR[[:space:]]|Exception:|Caused by:|OutOfMemoryError')"
assert_not_contains "${FILTERED_LOGS}" "GlobalExceptionHandler"
assert_not_contains "${FILTERED_LOGS}" "errorType=FAILED"
assert_contains "${FILTERED_LOGS}" "ERROR Service - failed"
assert_contains "${FILTERED_LOGS}" "BusinessException: failed"
assert_contains "${FILTERED_LOGS}" "Caused by: java.io.IOException"

printf 'server-status tests passed\n'
