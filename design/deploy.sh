#!/bin/bash
set -e

# ============================================================
# deploy.sh - 将 prototype.html 上传到服务器
#
# 服务器说明:
#   8.141.14.226 上部署了 Node.js 静态文件服务，负责展示静态网页。
#   本脚本将 prototype.html 上传到该服务器的静态资源目录，
#   上传后即可通过域名直接访问。
#
# 服务器: 8.141.14.226
# 目标路径: /root/node/static/
# 线上地址: http://marry.dingchenyong.top/prototype.html
# ============================================================

SERVER="root@8.141.14.226"
LOCAL_FILE="prototype.html"
REMOTE_DIR="/root/node/static"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "==> 正在上传 ${LOCAL_FILE} 到 ${SERVER}:${REMOTE_DIR}/ ..."

scp "${SCRIPT_DIR}/${LOCAL_FILE}" "${SERVER}:${REMOTE_DIR}/"

echo "==> 上传完成 ✅"
echo "    访问地址: http://marry.dingchenyong.top/prototype.html"
