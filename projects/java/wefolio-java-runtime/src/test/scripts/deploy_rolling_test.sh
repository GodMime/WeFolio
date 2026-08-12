#!/bin/bash

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
DEPLOY_SCRIPT="${MODULE_DIR}/deploy.sh"
TEST_FAILURES=0

fail() {
    echo "[失败] $1" >&2
    TEST_FAILURES=$((TEST_FAILURES + 1))
}

assert_equals() {
    local expected="$1"
    local actual="$2"
    local message="$3"

    if [[ "${actual}" != "${expected}" ]]; then
        fail "${message}: expected=${expected}, actual=${actual}"
    fi
}

assert_contains() {
    local actual="$1"
    local expected_part="$2"
    local message="$3"

    if [[ "${actual}" != *"${expected_part}"* ]]; then
        fail "${message}: missing=${expected_part}"
    fi
}

assert_not_contains() {
    local actual="$1"
    local unexpected_part="$2"
    local message="$3"

    if [[ "${actual}" == *"${unexpected_part}"* ]]; then
        fail "${message}: unexpected=${unexpected_part}"
    fi
}

record_event() {
    TEST_EVENTS="${TEST_EVENTS}$1
"
}

create_nginx_fixture() {
    local target_file="$1"

    cat >"${target_file}" <<'EOF'
upstream unrelated_service {
    least_conn;
    server 127.0.0.1:9000;
}

upstream wefolio_runtime {
    least_conn;

    server 127.0.0.1:8090 max_fails=3 fail_timeout=10s;
    server 10.0.4.7:8090 max_fails=3 fail_timeout=10s;

    keepalive 32;
}

server {
    listen 443 ssl;
}
EOF
}

test_source_does_not_execute_deployment() {
    local status

    (
        java() { return 97; }
        mvn() { return 97; }
        scp() { return 97; }
        ssh() { return 97; }
        source "${DEPLOY_SCRIPT}" >/dev/null 2>&1
    )
    status=$?

    assert_equals "0" "${status}" "source deploy.sh 时不得执行发布流程"
}

test_timed_step_reports_success_duration_with_server_ip() {
    local output status clock_file
    clock_file="${TEST_TMP_DIR}/timed-success-clock"
    printf '0' >"${clock_file}"

    output="$(
        _now_ms() {
            local call_index
            call_index="$(cat "${clock_file}")"
            if [[ "${call_index}" -eq 0 ]]; then
                printf '1' >"${clock_file}"
                echo "1000"
            else
                echo "1456"
            fi
        }
        timed_success() { return 0; }
        run_timed_step "老节点（49.235.146.161）健康检查" timed_success 2>&1
    )"
    status=$?

    assert_equals "0" "${status}" "成功步骤计时不得改变退出状态"
    assert_equals ">>> [耗时] 老节点（49.235.146.161）健康检查：成功，456 ms" "${output}" "成功步骤必须打印 IP 和毫秒耗时"
}

test_timed_step_reports_failure_duration_and_preserves_status() {
    local output status clock_file
    clock_file="${TEST_TMP_DIR}/timed-failure-clock"
    printf '0' >"${clock_file}"

    output="$(
        _now_ms() {
            local call_index
            call_index="$(cat "${clock_file}")"
            if [[ "${call_index}" -eq 0 ]]; then
                printf '1' >"${clock_file}"
                echo "2000"
            else
                echo "2750"
            fi
        }
        timed_failure() { return 23; }
        run_timed_step "新节点（124.222.148.233）服务重启" timed_failure 2>&1
    )"
    status=$?

    assert_equals "23" "${status}" "失败步骤计时必须保留原退出状态"
    assert_equals ">>> [耗时] 新节点（124.222.148.233）服务重启：失败，750 ms" "${output}" "失败步骤必须打印 IP 和毫秒耗时"
}

test_round_robin_renderer_only_changes_runtime_upstream() {
    local input_file output_file status output
    input_file="${TEST_TMP_DIR}/myapp.conf"
    output_file="${TEST_TMP_DIR}/myapp.round-robin.conf"
    create_nginx_fixture "${input_file}"

    render_nginx_config "round_robin" "${input_file}" "${output_file}"
    status=$?
    assert_equals "0" "${status}" "round_robin 配置渲染应成功"

    if [[ ! -f "${output_file}" ]]; then
        fail "round_robin 配置渲染必须生成输出文件"
        return
    fi

    output="$(cat "${output_file}")"
    assert_contains "${output}" $'upstream unrelated_service {\n    least_conn;' "不得修改其它 upstream"
    assert_not_contains "${output}" $'upstream wefolio_runtime {\n    least_conn;' "Runtime upstream 必须移除 least_conn"
    assert_contains "${output}" "server 127.0.0.1:8090 max_fails=3 fail_timeout=10s;" "round_robin 必须启用老节点"
    assert_contains "${output}" "server 10.0.4.7:8090 max_fails=3 fail_timeout=10s;" "round_robin 必须启用新节点"
    assert_not_contains "${output}" "fail_timeout=10s down;" "round_robin 不得标记节点 down"
}

test_single_node_modes_only_drain_the_deploying_node() {
    local input_file new_only_file old_only_file new_only_output old_only_output
    input_file="${TEST_TMP_DIR}/single-node-input.conf"
    new_only_file="${TEST_TMP_DIR}/new-only.conf"
    old_only_file="${TEST_TMP_DIR}/old-only.conf"
    create_nginx_fixture "${input_file}"

    render_nginx_config "new_only" "${input_file}" "${new_only_file}"
    assert_equals "0" "$?" "new_only 配置渲染应成功"
    new_only_output="$(cat "${new_only_file}")"
    assert_contains "${new_only_output}" "server 127.0.0.1:8090 max_fails=3 fail_timeout=10s down;" "new_only 必须摘除老节点"
    assert_contains "${new_only_output}" "server 10.0.4.7:8090 max_fails=3 fail_timeout=10s;" "new_only 必须保留新节点"
    assert_not_contains "${new_only_output}" "server 10.0.4.7:8090 max_fails=3 fail_timeout=10s down;" "new_only 不得摘除新节点"

    render_nginx_config "old_only" "${input_file}" "${old_only_file}"
    assert_equals "0" "$?" "old_only 配置渲染应成功"
    old_only_output="$(cat "${old_only_file}")"
    assert_contains "${old_only_output}" "server 127.0.0.1:8090 max_fails=3 fail_timeout=10s;" "old_only 必须保留老节点"
    assert_not_contains "${old_only_output}" "server 127.0.0.1:8090 max_fails=3 fail_timeout=10s down;" "old_only 不得摘除老节点"
    assert_contains "${old_only_output}" "server 10.0.4.7:8090 max_fails=3 fail_timeout=10s down;" "old_only 必须摘除新节点"
}

test_renderer_rejects_incomplete_runtime_upstream() {
    local input_file invalid_file output_file status
    input_file="${TEST_TMP_DIR}/validation-input.conf"
    invalid_file="${TEST_TMP_DIR}/missing-new-node.conf"
    output_file="${TEST_TMP_DIR}/invalid-output.conf"
    create_nginx_fixture "${input_file}"
    sed '/10\.0\.4\.7:8090/d' "${input_file}" >"${invalid_file}"

    render_nginx_config "round_robin" "${invalid_file}" "${output_file}"
    status=$?

    if [[ "${status}" -eq 0 ]]; then
        fail "缺少新节点时配置渲染必须失败"
    fi
    if [[ -e "${output_file}" ]]; then
        fail "配置校验失败时不得保留候选文件"
    fi
}

test_switch_nginx_mode_uploads_the_rendered_candidate() {
    local status uploaded_output
    TEST_NGINX_SOURCE="${TEST_TMP_DIR}/switch-source.conf"
    TEST_NGINX_UPLOADED="${TEST_TMP_DIR}/switch-uploaded.conf"
    create_nginx_fixture "${TEST_NGINX_SOURCE}"

    scp_download() {
        cp "${TEST_NGINX_SOURCE}" "$3"
    }
    scp_upload() {
        cp "$1" "${TEST_NGINX_UPLOADED}"
    }
    ssh_exec() {
        return 0
    }

    switch_nginx_mode "new_only"
    status=$?
    assert_equals "0" "${status}" "切换 new_only 应成功"

    if [[ ! -f "${TEST_NGINX_UPLOADED}" ]]; then
        fail "Nginx 切换必须上传渲染后的候选配置"
        return
    fi

    uploaded_output="$(cat "${TEST_NGINX_UPLOADED}")"
    assert_contains "${uploaded_output}" "server 127.0.0.1:8090 max_fails=3 fail_timeout=10s down;" "上传候选配置必须摘除老节点"
    assert_not_contains "${uploaded_output}" $'upstream wefolio_runtime {\n    least_conn;' "上传候选配置必须使用默认 round-robin 策略"
}

test_nginx_validation_failure_restores_previous_config() {
    local remote_dir original_content actual_content status
    remote_dir="${TEST_TMP_DIR}/remote-nginx"
    mkdir -p "${remote_dir}"
    NGINX_CONFIG="${remote_dir}/myapp.conf"
    NGINX_CANDIDATE="${remote_dir}/myapp.conf.rolling-candidate"
    NGINX_BACKUP="${remote_dir}/myapp.conf.rolling-backup"
    NGINX_CALL_COUNT_FILE="${remote_dir}/nginx-call-count"
    create_nginx_fixture "${NGINX_CONFIG}"
    original_content="$(cat "${NGINX_CONFIG}")"
    echo "0" >"${NGINX_CALL_COUNT_FILE}"

    scp_download() {
        cp "$2" "$3"
    }
    scp_upload() {
        cp "$1" "$3"
    }
    nginx() {
        local count
        count="$(cat "${NGINX_CALL_COUNT_FILE}")"
        count=$((count + 1))
        echo "${count}" >"${NGINX_CALL_COUNT_FILE}"
        [[ "${count}" -gt 1 ]]
    }
    systemctl() {
        return 0
    }
    ssh_exec() {
        (eval "$2")
    }

    switch_nginx_mode "old_only"
    status=$?
    actual_content="$(cat "${NGINX_CONFIG}")"

    if [[ "${status}" -eq 0 ]]; then
        fail "首次 nginx -t 失败时切换操作必须返回失败"
    fi
    assert_equals "${original_content}" "${actual_content}" "nginx -t 失败时必须恢复切换前配置"
}

test_nginx_reload_failure_restores_previous_config() {
    local remote_dir original_content actual_content status
    remote_dir="${TEST_TMP_DIR}/remote-nginx-reload"
    mkdir -p "${remote_dir}"
    NGINX_CONFIG="${remote_dir}/myapp.conf"
    NGINX_CANDIDATE="${remote_dir}/myapp.conf.rolling-candidate"
    NGINX_BACKUP="${remote_dir}/myapp.conf.rolling-backup"
    SYSTEMCTL_CALL_COUNT_FILE="${remote_dir}/systemctl-call-count"
    create_nginx_fixture "${NGINX_CONFIG}"
    original_content="$(cat "${NGINX_CONFIG}")"
    echo "0" >"${SYSTEMCTL_CALL_COUNT_FILE}"

    scp_download() {
        cp "$2" "$3"
    }
    scp_upload() {
        cp "$1" "$3"
    }
    nginx() {
        return 0
    }
    systemctl() {
        local count
        count="$(cat "${SYSTEMCTL_CALL_COUNT_FILE}")"
        count=$((count + 1))
        echo "${count}" >"${SYSTEMCTL_CALL_COUNT_FILE}"
        [[ "${count}" -gt 1 ]]
    }
    ssh_exec() {
        (eval "$2")
    }

    switch_nginx_mode "new_only"
    status=$?
    actual_content="$(cat "${NGINX_CONFIG}")"

    if [[ "${status}" -eq 0 ]]; then
        fail "首次 Nginx reload 失败时切换操作必须返回失败"
    fi
    assert_equals "${original_content}" "${actual_content}" "Nginx reload 失败时必须恢复切换前配置"
}

test_remote_install_keeps_exactly_one_previous_jar_backup() {
    local remote_dir backup_count status
    remote_dir="${TEST_TMP_DIR}/remote-jar-success"
    mkdir -p "${remote_dir}"
    REMOTE_DIR="${remote_dir}"
    LOCAL_JAR="${TEST_TMP_DIR}/new-runtime.jar"
    printf 'old-runtime' >"${REMOTE_DIR}/${JAR_NAME}"
    printf 'stale-backup' >"${REMOTE_DIR}/${JAR_NAME}.backup"
    printf 'new-runtime' >"${LOCAL_JAR}"
    ARTIFACT_SHA256="$(shasum -a 256 "${LOCAL_JAR}" | awk '{print $1}')"

    scp_upload() {
        cp "$1" "$3"
    }
    ssh_exec() {
        (eval "$2")
    }

    install_remote_jar "test-server"
    status=$?
    assert_equals "0" "${status}" "远端 JAR 安装应成功"
    assert_equals "old-runtime" "$(cat "${REMOTE_DIR}/${JAR_NAME}.backup")" "固定备份必须保存替换前 JAR"
    assert_equals "new-runtime" "$(cat "${REMOTE_DIR}/${JAR_NAME}")" "正式路径必须替换为新 JAR"
    backup_count="$(find "${REMOTE_DIR}" -maxdepth 1 -type f -name "${JAR_NAME}.backup*" | wc -l | tr -d ' ')"
    assert_equals "1" "${backup_count}" "每个节点只能保留一个 JAR 备份"
}

test_remote_checksum_failure_preserves_formal_jar_without_backup() {
    local remote_dir status
    remote_dir="${TEST_TMP_DIR}/remote-jar-checksum-failure"
    mkdir -p "${remote_dir}"
    REMOTE_DIR="${remote_dir}"
    LOCAL_JAR="${TEST_TMP_DIR}/invalid-runtime.jar"
    printf 'current-runtime' >"${REMOTE_DIR}/${JAR_NAME}"
    printf 'uploaded-runtime' >"${LOCAL_JAR}"
    ARTIFACT_SHA256="ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"

    scp_upload() {
        cp "$1" "$3"
    }
    ssh_exec() {
        (eval "$2")
    }

    install_remote_jar "test-server" >/dev/null 2>&1
    status=$?

    if [[ "${status}" -eq 0 ]]; then
        fail "远端 SHA-256 不一致时安装必须失败"
    fi
    assert_equals "current-runtime" "$(cat "${REMOTE_DIR}/${JAR_NAME}")" "校验失败不得覆盖正式 JAR"
    if [[ -e "${REMOTE_DIR}/${JAR_NAME}.backup" ]]; then
        fail "校验失败不得创建或更新 JAR 备份"
    fi
}

test_health_polling_starts_after_three_seconds() {
    local status expected_events
    TEST_CLOCK=0
    TEST_PROBE_COUNT=0
    TEST_EVENTS=""

    now_seconds() {
        echo "${TEST_CLOCK}"
    }
    sleep_seconds() {
        TEST_EVENTS="${TEST_EVENTS}sleep:$1
"
        TEST_CLOCK=$((TEST_CLOCK + $1))
    }
    check_health_once() {
        TEST_PROBE_COUNT=$((TEST_PROBE_COUNT + 1))
        TEST_EVENTS="${TEST_EVENTS}probe:${TEST_CLOCK}
"
        [[ "${TEST_PROBE_COUNT}" -ge 3 ]]
    }

    wait_for_health "http://127.0.0.1:8090/api/health" "老节点" >/dev/null 2>&1
    status=$?
    expected_events="sleep:3
probe:3
sleep:1
probe:4
sleep:1
probe:5"

    assert_equals "0" "${status}" "第三次探测健康时应成功"
    assert_equals "${expected_events}" "${TEST_EVENTS%$'\n'}" "健康检查必须先等 3 秒再每秒探测"
}

test_health_polling_stops_at_thirty_second_deadline() {
    local status
    TEST_CLOCK=0
    TEST_PROBE_COUNT=0
    TEST_LAST_PROBE_TIME=-1

    now_seconds() {
        echo "${TEST_CLOCK}"
    }
    sleep_seconds() {
        TEST_CLOCK=$((TEST_CLOCK + $1))
    }
    check_health_once() {
        TEST_PROBE_COUNT=$((TEST_PROBE_COUNT + 1))
        TEST_LAST_PROBE_TIME="${TEST_CLOCK}"
        return 1
    }

    wait_for_health "http://10.0.4.7:8090/api/health" "新节点" >/dev/null 2>&1
    status=$?

    if [[ "${status}" -eq 0 ]]; then
        fail "持续不健康时必须返回失败"
    fi
    assert_equals "30" "${TEST_LAST_PROBE_TIME}" "最后一次健康检查必须发生在 30 秒截止点"
    assert_equals "30" "${TEST_CLOCK}" "健康检查不得等待超过 30 秒"
    assert_equals "28" "${TEST_PROBE_COUNT}" "第 3 秒到第 30 秒应共探测 28 次"
}

test_deploy_node_installs_restarts_once_and_then_checks_health() {
    local status expected_events
    TEST_EVENTS=""

    install_remote_jar() {
        TEST_EVENTS="${TEST_EVENTS}install:$1
"
        return 0
    }
    ssh_exec() {
        TEST_EVENTS="${TEST_EVENTS}restart:$1:$2
"
        return 0
    }
    wait_for_health() {
        TEST_EVENTS="${TEST_EVENTS}health:$1:$2
"
        return 0
    }

    deploy_node "root@old-server" "http://127.0.0.1:8090/api/health" "老节点" >/dev/null 2>&1
    status=$?
    expected_events="install:root@old-server
restart:root@old-server:systemctl restart wefolio.service
health:http://127.0.0.1:8090/api/health:老节点"

    assert_equals "0" "${status}" "单节点发布应成功"
    assert_equals "${expected_events}" "${TEST_EVENTS%$'\n'}" "单节点发布必须按安装、单次重启、健康检查执行"
}

test_deploy_node_health_failure_does_not_restart_or_restore_again() {
    local status restart_count
    TEST_EVENTS=""

    install_remote_jar() {
        TEST_EVENTS="${TEST_EVENTS}install
"
        return 0
    }
    ssh_exec() {
        TEST_EVENTS="${TEST_EVENTS}restart
"
        return 0
    }
    wait_for_health() {
        TEST_EVENTS="${TEST_EVENTS}health-failed
"
        return 1
    }

    deploy_node "root@new-server" "http://10.0.4.7:8090/api/health" "新节点" >/dev/null 2>&1
    status=$?
    restart_count="$(printf '%s' "${TEST_EVENTS}" | grep -c '^restart$')"

    if [[ "${status}" -eq 0 ]]; then
        fail "节点健康检查失败时发布必须失败"
    fi
    assert_equals "1" "${restart_count}" "健康失败后不得自动恢复备份并再次重启"
    assert_not_contains "${TEST_EVENTS}" "restore" "健康失败后不得自动执行备份恢复"
}

test_deploy_node_checksum_failure_never_restarts_service() {
    local status
    TEST_EVENTS=""

    install_remote_jar() {
        record_event "install-failed"
        return 1
    }
    ssh_exec() {
        record_event "restart"
        return 0
    }
    wait_for_health() {
        record_event "health"
        return 0
    }

    deploy_node "root@old-server" "http://127.0.0.1:8090/api/health" "老节点" >/dev/null 2>&1
    status=$?

    if [[ "${status}" -eq 0 ]]; then
        fail "JAR 安装失败时节点发布必须失败"
    fi
    assert_equals "install-failed" "${TEST_EVENTS%$'\n'}" "JAR 校验或备份失败后不得重启或检查健康"
}

test_deploy_node_reports_timed_substeps_with_server_ip() {
    local old_output new_output status

    install_remote_jar() { return 0; }
    ssh_exec() { return 0; }
    wait_for_health() { return 0; }

    old_output="$(deploy_node "root@49.235.146.161" "http://127.0.0.1:8090/api/health" "老节点" 2>&1)"
    status=$?
    assert_equals "0" "${status}" "老节点计时发布应成功"
    assert_contains "${old_output}" ">>> [耗时] 老节点（49.235.146.161）JAR 上传安装：成功，" "老节点 JAR 计时必须包含公网 IP"
    assert_contains "${old_output}" ">>> [耗时] 老节点（49.235.146.161）服务重启：成功，" "老节点重启计时必须包含公网 IP"
    assert_contains "${old_output}" ">>> [耗时] 老节点（49.235.146.161）健康检查：成功，" "老节点健康计时必须包含公网 IP"

    new_output="$(deploy_node "root@124.222.148.233" "http://10.0.4.7:8090/api/health" "新节点" 2>&1)"
    status=$?
    assert_equals "0" "${status}" "新节点计时发布应成功"
    assert_contains "${new_output}" ">>> [耗时] 新节点（124.222.148.233）JAR 上传安装：成功，" "新节点 JAR 计时必须包含公网 IP"
    assert_contains "${new_output}" ">>> [耗时] 新节点（124.222.148.233）服务重启：成功，" "新节点重启计时必须包含公网 IP"
    assert_contains "${new_output}" ">>> [耗时] 新节点（124.222.148.233）健康检查：成功，" "新节点健康计时必须包含公网 IP"
}

test_build_artifact_packages_once_and_records_sha256() {
    local status expected_sha
    LOCAL_JAR="${TEST_TMP_DIR}/built-runtime.jar"
    ARTIFACT_SHA256=""
    TEST_MAVEN_CALLS=0

    java() {
        return 0
    }
    mvn() {
        TEST_MAVEN_CALLS=$((TEST_MAVEN_CALLS + 1))
        printf 'built-runtime' >"${LOCAL_JAR}"
        return 0
    }

    build_artifact >/dev/null 2>&1
    status=$?
    expected_sha="$(shasum -a 256 "${LOCAL_JAR}" | awk '{print $1}')"

    assert_equals "0" "${status}" "Runtime 构建应成功"
    assert_equals "1" "${TEST_MAVEN_CALLS}" "一次滚动发布只能构建一次"
    assert_equals "${expected_sha}" "${ARTIFACT_SHA256}" "构建后必须记录本地 JAR SHA-256"
}

test_preflight_validates_nginx_and_both_nodes_before_switching() {
    local status expected_events
    TEST_EVENTS=""
    TEST_NGINX_SOURCE="${TEST_TMP_DIR}/preflight-myapp.conf"
    NGINX_SERVER="root@49.235.146.161"
    NGINX_CONFIG="/etc/nginx/conf.d/myapp.conf"
    create_nginx_fixture "${TEST_NGINX_SOURCE}"

    ssh_exec() {
        record_event "nginx-test:$1:$2"
        return 0
    }
    scp_download() {
        record_event "download:$1:$2"
        cp "${TEST_NGINX_SOURCE}" "$3"
    }
    check_health_once() {
        record_event "health:$1"
        return 0
    }

    preflight >/dev/null 2>&1
    status=$?
    expected_events="nginx-test:${NGINX_SERVER}:nginx -t
download:${NGINX_SERVER}:${NGINX_CONFIG}
health:${OLD_HEALTH_URL}
health:${NEW_HEALTH_URL}"

    assert_equals "0" "${status}" "发布前检查应成功"
    assert_equals "${expected_events}" "${TEST_EVENTS%$'\n'}" "切流前必须验证 Nginx 配置和两个节点"
}

test_preflight_fails_when_either_node_is_unhealthy() {
    local status health_count
    TEST_NGINX_SOURCE="${TEST_TMP_DIR}/preflight-unhealthy.conf"
    create_nginx_fixture "${TEST_NGINX_SOURCE}"
    health_count=0

    ssh_exec() { return 0; }
    scp_download() { cp "${TEST_NGINX_SOURCE}" "$3"; }
    check_health_once() {
        health_count=$((health_count + 1))
        [[ "${health_count}" -eq 1 ]]
    }

    preflight >/dev/null 2>&1
    status=$?
    if [[ "${status}" -eq 0 ]]; then
        fail "任一节点不健康时发布前检查必须失败"
    fi
}

test_rolling_deploy_uses_the_required_success_sequence() {
    local status expected_events
    TEST_EVENTS=""

    build_artifact() { record_event "build"; return 0; }
    preflight() { record_event "preflight"; return 0; }
    switch_nginx_mode() { record_event "nginx:$1"; return 0; }
    deploy_node() {
        if [[ "$1" == "${OLD_SERVER}" ]]; then
            record_event "deploy:old"
        else
            record_event "deploy:new"
        fi
        return 0
    }
    rolling_deploy
    status=$?
expected_events="build
preflight
nginx:new_only
deploy:old
nginx:old_only
deploy:new
nginx:round_robin"

    assert_equals "0" "${status}" "完整滚动发布应成功"
    assert_equals "${expected_events}" "${TEST_EVENTS%$'\n'}" "完整发布顺序必须先老后新并最终恢复 round_robin"
}

test_old_node_failure_stays_on_new_only() {
    local status expected_events
    TEST_EVENTS=""

    build_artifact() { record_event "build"; return 0; }
    preflight() { record_event "preflight"; return 0; }
    switch_nginx_mode() { record_event "nginx:$1"; return 0; }
    deploy_node() { record_event "deploy:old"; return 1; }

    rolling_deploy >/dev/null 2>&1
    status=$?
expected_events="build
preflight
nginx:new_only
deploy:old"

    if [[ "${status}" -eq 0 ]]; then
        fail "老节点发布失败时完整发布必须失败"
    fi
    assert_equals "${expected_events}" "${TEST_EVENTS%$'\n'}" "老节点失败后必须停留在 new_only"
}

test_new_node_failure_stays_on_old_only() {
    local status expected_events deploy_count
    TEST_EVENTS=""
    deploy_count=0

    build_artifact() { record_event "build"; return 0; }
    preflight() { record_event "preflight"; return 0; }
    switch_nginx_mode() { record_event "nginx:$1"; return 0; }
    deploy_node() {
        deploy_count=$((deploy_count + 1))
        if [[ "${deploy_count}" -eq 1 ]]; then
            record_event "deploy:old"
            return 0
        fi
        record_event "deploy:new"
        return 1
    }

    rolling_deploy >/dev/null 2>&1
    status=$?
expected_events="build
preflight
nginx:new_only
deploy:old
nginx:old_only
deploy:new"

    if [[ "${status}" -eq 0 ]]; then
        fail "新节点发布失败时完整发布必须失败"
    fi
    assert_equals "${expected_events}" "${TEST_EVENTS%$'\n'}" "新节点失败后必须停留在 old_only"
}

test_rolling_deploy_reports_major_step_durations_with_server_ips() {
    local output status

    build_artifact() { return 0; }
    preflight() { return 0; }
    switch_nginx_mode() { return 0; }
    deploy_node() { return 0; }

    output="$(rolling_deploy 2>&1)"
    status=$?

    assert_equals "0" "${status}" "计时滚动发布应成功"
    assert_contains "${output}" ">>> [耗时] 本地构建：成功，" "必须统计本地构建耗时"
    assert_contains "${output}" ">>> [耗时] 发布前检查（Nginx：49.235.146.161，老节点：49.235.146.161，新节点：124.222.148.233）：成功，" "发布前检查计时必须包含全部服务器 IP"
    assert_contains "${output}" ">>> [耗时] Nginx 切流 new_only（49.235.146.161）：成功，" "new_only 切流计时必须包含 Nginx IP"
    assert_contains "${output}" ">>> [耗时] 老节点发布（49.235.146.161）：成功，" "必须统计老节点发布总耗时"
    assert_contains "${output}" ">>> [耗时] Nginx 切流 old_only（49.235.146.161）：成功，" "old_only 切流计时必须包含 Nginx IP"
    assert_contains "${output}" ">>> [耗时] 新节点发布（124.222.148.233）：成功，" "必须统计新节点发布总耗时"
    assert_contains "${output}" ">>> [耗时] Nginx 切流 round_robin（49.235.146.161）：成功，" "round_robin 切流计时必须包含 Nginx IP"
}

test_main_reports_total_duration_when_rolling_deploy_fails() {
    local output status

    output="$(
        (
            rolling_deploy() { return 19; }
            main
        ) 2>&1
    )"
    status=$?

    if [[ "${status}" -eq 0 ]]; then
        fail "滚动发布失败时 main 必须返回失败"
    fi
    assert_contains "${output}" ">>> [耗时] Runtime 双节点滚动发布：失败，" "发布失败时仍必须打印总耗时"
}

test_main_enters_the_rolling_deploy_state_machine() {
    local status event_file
    event_file="${TEST_TMP_DIR}/main-event"

    (
        java() { return 0; }
        mvn() { return 0; }
        scp() { return 0; }
        ssh() { return 0; }
        rolling_deploy() {
            echo "rolling" >"${event_file}"
            return 0
        }
        main >/dev/null 2>&1
    )
    status=$?

    assert_equals "0" "${status}" "main 应成功调用滚动发布状态机"
    if [[ ! -f "${event_file}" ]]; then
        fail "main 必须进入 rolling_deploy，不得继续执行旧单节点流程"
    fi
}

test_source_does_not_execute_deployment

TEST_TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/wefolio-deploy-test.XXXXXX")"
trap 'rm -rf "${TEST_TMP_DIR}"' EXIT

source "${DEPLOY_SCRIPT}"
set +e

test_timed_step_reports_success_duration_with_server_ip
test_timed_step_reports_failure_duration_and_preserves_status
test_round_robin_renderer_only_changes_runtime_upstream
test_single_node_modes_only_drain_the_deploying_node
test_renderer_rejects_incomplete_runtime_upstream
test_switch_nginx_mode_uploads_the_rendered_candidate
test_nginx_validation_failure_restores_previous_config
test_nginx_reload_failure_restores_previous_config
test_remote_install_keeps_exactly_one_previous_jar_backup
test_remote_checksum_failure_preserves_formal_jar_without_backup
test_health_polling_starts_after_three_seconds
test_health_polling_stops_at_thirty_second_deadline
test_deploy_node_installs_restarts_once_and_then_checks_health
test_deploy_node_health_failure_does_not_restart_or_restore_again
test_deploy_node_checksum_failure_never_restarts_service
test_deploy_node_reports_timed_substeps_with_server_ip
test_build_artifact_packages_once_and_records_sha256
test_preflight_validates_nginx_and_both_nodes_before_switching
test_preflight_fails_when_either_node_is_unhealthy
test_rolling_deploy_uses_the_required_success_sequence
test_old_node_failure_stays_on_new_only
test_new_node_failure_stays_on_old_only
test_rolling_deploy_reports_major_step_durations_with_server_ips
test_main_enters_the_rolling_deploy_state_machine
test_main_reports_total_duration_when_rolling_deploy_fails

if [[ "${TEST_FAILURES}" -ne 0 ]]; then
    echo "共 ${TEST_FAILURES} 个测试失败" >&2
    exit 1
fi

echo "全部滚动发布脚本测试通过"
