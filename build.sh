#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-2.1-or-later
# fcitx5-android build script
# Usage:
#   ./build.sh [build] [gradle-args...]   构建 app release APK（默认命令）
#   ./build.sh plugins [gradle-args...]   构建全部插件 release APK
#   ./build.sh install [device]           安装 app 到 adb 设备（自动匹配 ABI）
#   ./build.sh install-plugins [device]   安装全部插件到 adb 设备
#   ./build.sh install-rime [device]      安装 rime-ice + rime-radical-pinyin（紫光双拼）到 adb 设备
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

# ---------- Rime 安装配置（install-rime 子命令） ----------
RIME_ICE_DIR="${RIME_ICE_DIR:-$HOME/rime-ice}"
RIME_RADICAL_DIR="${RIME_RADICAL_DIR:-$HOME/rime-radical-pinyin}"
FCITX_PKG="${FCITX_PKG:-org.fcitx.fcitx5.android}"
RIME_DOUBLE_PINYIN="${RIME_DOUBLE_PINYIN:-ziguang}"   # pinyin natural flypy mspy sogou abc jiajia ziguang
RIME_PAGE_SIZE="${RIME_PAGE_SIZE:-9}"
RIME_DEPLOY_TIMEOUT="${RIME_DEPLOY_TIMEOUT:-300}"

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

cmd_install_rime() {
    local dev
    dev="$(resolve_device "${1:-}")"

    # 校验源仓库
    if [[ ! -d "$RIME_ICE_DIR" ]]; then
        echo "error: rime-ice 目录不存在: $RIME_ICE_DIR" >&2
        echo "       set RIME_ICE_DIR" >&2
        exit 1
    fi
    if [[ ! -d "$RIME_RADICAL_DIR" ]]; then
        echo "error: rime-radical-pinyin 目录不存在: $RIME_RADICAL_DIR" >&2
        echo "       set RIME_RADICAL_DIR" >&2
        exit 1
    fi
    local required=(cn_dicts en_dicts lua opencc)
    local d
    for d in "${required[@]}"; do
        if [[ ! -d "$RIME_ICE_DIR/$d" ]]; then
            echo "error: $RIME_ICE_DIR/$d 不存在，请确认是 rime-ice 仓库" >&2
            exit 1
        fi
    done
    if [[ ! -f "$RIME_ICE_DIR/custom_phrase.txt" ]]; then
        echo "error: $RIME_ICE_DIR/custom_phrase.txt 不存在" >&2
        exit 1
    fi

    # 双拼变体 -> 主方案 / radical_pinyin 的 algebra 段
    local main_schema algebra
    case "$RIME_DOUBLE_PINYIN" in
        pinyin)  main_schema="rime_ice";              algebra="algebra_pinyin" ;;
        natural) main_schema="double_pinyin";         algebra="algebra_double_pinyin" ;;
        flypy)   main_schema="double_pinyin_flypy";   algebra="algebra_double_pinyin_flypy" ;;
        mspy)    main_schema="double_pinyin_mspy";    algebra="algebra_double_pinyin_mspy" ;;
        sogou)   main_schema="double_pinyin_sogou";   algebra="algebra_double_pinyin_sogou" ;;
        abc)     main_schema="double_pinyin_abc";     algebra="algebra_double_pinyin_abc" ;;
        jiajia)  main_schema="double_pinyin_jiajia";  algebra="algebra_double_pinyin_jiajia" ;;
        ziguang) main_schema="double_pinyin_ziguang"; algebra="algebra_double_pinyin_ziguang" ;;
        *)
            echo "error: unknown RIME_DOUBLE_PINYIN: $RIME_DOUBLE_PINYIN" >&2
            echo "       valid: pinyin natural flypy mspy sogou abc jiajia ziguang" >&2
            exit 1
            ;;
    esac

    # 组装 Rime 用户目录
    STAGE_DIR="$(mktemp -d)"
    trap 'rm -rf "$STAGE_DIR"' EXIT
    echo "== staging: $RIME_ICE_DIR + $RIME_RADICAL_DIR (variant: $RIME_DOUBLE_PINYIN)"

    shopt -s nullglob
    local f
    for f in "$RIME_ICE_DIR"/*.yaml; do
        case "$(basename "$f")" in
            squirrel.yaml|weasel.yaml|recipe.yaml) continue ;;
        esac
        cp "$f" "$STAGE_DIR/"
    done
    cp -r "$RIME_ICE_DIR"/cn_dicts "$RIME_ICE_DIR"/en_dicts "$RIME_ICE_DIR"/lua "$RIME_ICE_DIR"/opencc "$STAGE_DIR/"
    cp "$RIME_ICE_DIR"/custom_phrase.txt "$STAGE_DIR/"
    cp "$RIME_RADICAL_DIR"/radical_pinyin.schema.yaml "$RIME_RADICAL_DIR"/radical_pinyin.dict.yaml "$STAGE_DIR/"
    shopt -u nullglob

    cat > "$STAGE_DIR/default.custom.yaml" <<EOF
patch:
  "menu/page_size": ${RIME_PAGE_SIZE}
  schema_list:
    - schema: ${main_schema}
    - schema: radical_pinyin
EOF

    cat > "$STAGE_DIR/radical_pinyin.custom.yaml" <<EOF
patch:
  speller/algebra:
    __include: radical_pinyin.schema.yaml:/${algebra}
EOF

    cat > "$STAGE_DIR/custom_phrase_double.txt" <<'EOF'
# Rime table
# coding: utf-8
#@/db_name	custom_phrase_double.txt
#@/db_type	tabledb
#
EOF

    # 推送
    local dest="/storage/emulated/0/Android/data/${FCITX_PKG}/files/data/rime"
    adb -s "$dev" shell mkdir -p "$dest"
    local n=0
    for f in "$STAGE_DIR"/*; do
        adb -s "$dev" push "$f" "$dest/" >/dev/null
        n=$((n + 1))
    done
    echo "== pushed $n entries to $dev:$dest"

    # 重启 app 触发 librime 部署
    echo "== restarting $FCITX_PKG to trigger Rime deployment"
    adb -s "$dev" shell am force-stop "$FCITX_PKG"
    sleep 2
    adb -s "$dev" shell monkey -p "$FCITX_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true

    # 轮询验证部署产物
    local targets=("${main_schema}.prism.bin" "rime_ice.table.bin" "radical_pinyin.table.bin")
    local waited=0 all=1 t
    echo "== waiting for deployment: ${targets[*]} (timeout ${RIME_DEPLOY_TIMEOUT}s)"
    while (( waited < RIME_DEPLOY_TIMEOUT )); do
        all=1
        for t in "${targets[@]}"; do
            if ! adb -s "$dev" shell "[ -f $dest/build/$t ]" 2>/dev/null; then
                all=0
                break
            fi
        done
        if (( all )); then
            break
        fi
        sleep 5
        waited=$((waited + 5))
    done
    if (( all )); then
        echo "== deployment complete"
    else
        echo "warning: deployment not confirmed within ${RIME_DEPLOY_TIMEOUT}s" >&2
        echo "         check manually: adb -s $dev shell ls $dest/build/" >&2
    fi

    cat <<EOF

Rime 已安装到 $dev:
  用户目录: $dest
  主方案:   ${main_schema}（${RIME_DOUBLE_PINYIN}，每页 ${RIME_PAGE_SIZE} 候选）
  拆字方案: radical_pinyin（部件拆字，${RIME_DOUBLE_PINYIN}）
  反查:     uU + 部件拼音（如 uUwu nb = 鹜）
  辅码:     输入拼音后按 \` 再输部件码筛选候选
  改配置:   $dest/default.custom.yaml
EOF
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
    sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
}

main() {
    local cmd="${1:-build}"
    case "$cmd" in
        build) shift || true; cmd_build "$@" ;;
        plugins) shift || true; cmd_plugins "$@" ;;
        install) shift || true; cmd_install "${1:-}" ;;
        install-plugins) shift || true; cmd_install_plugins "${1:-}" ;;
        install-rime) shift || true; cmd_install_rime "${1:-}" ;;
        apk) cmd_apk ;;
        clean) cmd_clean ;;
        shell) shift || true; cmd_shell "$@" ;;
        -h|--help|help) usage ;;
        *) echo "unknown command: $cmd" >&2; usage >&2; exit 1 ;;
    esac
}

main "$@"
