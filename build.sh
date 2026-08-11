#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-2.1-or-later
# fcitx5-android build script
# Usage:
#   ./build.sh [build] [gradle-args...]   构建 app release APK（默认命令）
#   ./build.sh plugins [gradle-args...]   构建全部插件 release APK
#   ./build.sh install [device]           安装 app 到 adb 设备（自动匹配 ABI）
#   ./build.sh install-plugins [device]   安装全部插件到 adb 设备
#   ./build.sh apk                       打印 app release APK 路径
#   ./build.sh clean                     清理构建产物
#   ./build.sh shell                     进入 nix develop 开发环境
#
# 签名（release 需要）：
#   默认从 ~/.keystores/ 读取；可通过环境变量覆盖：
#   SIGN_KEY_FILE / SIGN_KEY_PWD / SIGN_KEY_ALIAS / KEY_PWD_FILE
#
# ABI：
#   默认只编译 arm64-v8a；可通过 BUILD_ABI 环境变量覆盖（如 BUILD_ABI=x86_64）

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ---------- 签名配置 ----------
KEYSTORE_DIR="${KEYSTORE_DIR:-$HOME/.keystores}"
KEY_FILE="${SIGN_KEY_FILE:-$KEYSTORE_DIR/fcitx5-release.ks}"
KEY_ALIAS="${SIGN_KEY_ALIAS:-fcitx5}"
KEY_PWD_FILE="${KEY_PWD_FILE:-$KEYSTORE_DIR/fcitx5-release.pwd}"

nix_dev() {
    nix --extra-experimental-features 'nix-command flakes' develop .#noAS --command "$@"
}

nix_dev_gradle() {
    # 注入签名环境变量后执行 gradlew
    if [[ -z "${SIGN_KEY_PWD:-}" ]]; then
        if [[ ! -f "$KEY_PWD_FILE" ]]; then
            echo "error: keystore password file not found: $KEY_PWD_FILE" >&2
            echo "       set SIGN_KEY_PWD or KEY_PWD_FILE" >&2
            exit 1
        fi
        SIGN_KEY_PWD="$(cat "$KEY_PWD_FILE")"
    fi
    nix_dev env \
        SIGN_KEY_FILE="$KEY_FILE" \
        SIGN_KEY_PWD="$SIGN_KEY_PWD" \
        SIGN_KEY_ALIAS="$KEY_ALIAS" \
        BUILD_ABI="${BUILD_ABI:-arm64-v8a}" \
        ./gradlew "$@"
}

cmd_build() {
    nix_dev_gradle :app:assembleRelease "$@"
}

cmd_plugins() {
    nix_dev_gradle :assembleReleasePlugins "$@"
}

# 解析设备：优先命令行参数，其次环境变量 DEVICE，再取唯一在线设备
resolve_device() {
    local arg="${1:-}"
    if [[ -n "$arg" ]]; then
        echo "$arg"
        return 0
    fi
    if [[ -n "${DEVICE:-}" ]]; then
        echo "$DEVICE"
        return 0
    fi
    local devices
    devices="$(adb devices | awk '$2 == "device" { print $1 }')"
    local count
    count="$(printf '%s\n' "$devices" | sed '/^$/d' | wc -l)"
    if [[ "$count" -eq 0 ]]; then
        echo "error: no adb device connected" >&2
        exit 1
    elif [[ "$count" -gt 1 ]]; then
        echo "error: multiple devices connected, specify one:" >&2
        printf '%s\n' "$devices" | sed 's/^/  /' >&2
        exit 1
    fi
    echo "$devices"
}

cmd_install() {
    local dev
    dev="$(resolve_device "${1:-}")"
    local abi apk
    abi="$(adb -s "$dev" shell getprop ro.product.cpu.abi | tr -d '\r')"
    apk="$(ls app/build/outputs/apk/release/*"${abi}"-release.apk 2>/dev/null | head -1)"
    if [[ -z "$apk" ]]; then
        echo "error: no release APK for ABI $abi, run ./build.sh first" >&2
        exit 1
    fi
    echo "== installing $apk -> $dev ($abi)"
    adb -s "$dev" install -r "$apk"
}

cmd_install_plugins() {
    local dev
    dev="$(resolve_device "${1:-}")"
    local abi
    abi="$(adb -s "$dev" shell getprop ro.product.cpu.abi | tr -d '\r')"
    local apks=()
    # shellcheck disable=SC2207
    apks=($(ls plugin/*/build/outputs/apk/release/*"${abi}"-release.apk 2>/dev/null || true))
    if [[ ${#apks[@]} -eq 0 ]]; then
        echo "error: no plugin release APKs found, run ./build.sh plugins first" >&2
        exit 1
    fi
    for apk in "${apks[@]}"; do
        echo "== installing $apk"
        adb -s "$dev" install -r "$apk"
    done
}

cmd_apk() {
    ls app/build/outputs/apk/release/*-release.apk 2>/dev/null || {
        echo "no release APK found, run ./build.sh first" >&2
        exit 1
    }
}

cmd_clean() {
    nix_dev ./gradlew clean
}

cmd_shell() {
    nix --extra-experimental-features 'nix-command flakes' develop .#noAS "$@"
}

usage() {
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
}

main() {
    local cmd="${1:-build}"
    case "$cmd" in
        build) shift || true; cmd_build "$@" ;;
        plugins) shift || true; cmd_plugins "$@" ;;
        install) shift || true; cmd_install "${1:-}" ;;
        install-plugins) shift || true; cmd_install_plugins "${1:-}" ;;
        apk) cmd_apk ;;
        clean) cmd_clean ;;
        shell) shift || true; cmd_shell "$@" ;;
        -h|--help|help) usage ;;
        *) echo "unknown command: $cmd" >&2; usage >&2; exit 1 ;;
    esac
}

main "$@"
