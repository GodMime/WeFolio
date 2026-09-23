#!/usr/bin/env bash
# 独立验收入口，不读取应用 .env，不安装工具或连接生产资源。
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
: "${FONT_TEST_PYTHON:?必须提供 FONT_TEST_PYTHON}"
: "${FONT_TEST_HARFBUZZ:?必须提供 FONT_TEST_HARFBUZZ}"
export PYTHONDONTWRITEBYTECODE=1
exec "$FONT_TEST_PYTHON" "$SCRIPT_DIR/font-verification.py"
