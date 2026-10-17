#!/usr/bin/env bash
# B561 交付构建：平台签名 + 本地 MFi 配件身份。
#
# ★ 为什么必须注入 DIPLAY_AUTH_ASSETS_DIR：
#   DiPlayBootstrap.ensure() 在**全新安装**首启时把 assets/offline-mfi/
#   {identity.pk8, certificate.p7b} 拷进 noBackupFilesDir 再加载。v100~v108
#   漏了这一步，APK 里没有身份文件 → 全新安装必弹 setup_error_auth
#   （"无法加载 CarPlay 认证"），只有覆盖安装（旧数据目录还在）才侥幸能用。
#   身份文件放在 local-auth/（已被 .gitignore 排除，不进公开仓库）。
#   哈希须与 v84 交付件一致：
#     identity.pk8     bd50eda2d8dd95a8464440f1aebca7068dcab46a2461ecaf826c7f98f5621a75
#     certificate.p7b  634a93dd6c4338080524025411752597ecb828591839cd062a8292fd7e9e844e
#
# 用法：scripts/build-b561.sh
set -euo pipefail
cd "$(dirname "$0")/.."

export HTTPS_PROXY="${HTTPS_PROXY:-http://127.0.0.1:7890}"
export HTTP_PROXY="${HTTP_PROXY:-http://127.0.0.1:7890}"

export ANDROID_KEYSTORE_PATH="${ANDROID_KEYSTORE_PATH:-$PWD/../OushangCarPlay-B561/OushangCarPlay-B561-v48/keys/b561-platform.p12}"
export ANDROID_KEYSTORE_PASSWORD="${ANDROID_KEYSTORE_PASSWORD:-android}"
export ANDROID_KEY_ALIAS="${ANDROID_KEY_ALIAS:-androiddebugkey}"
export ANDROID_KEY_PASSWORD="${ANDROID_KEY_PASSWORD:-android}"

# 本地 MFi 配件身份：默认仓库内 local-auth/，可用环境变量覆盖。
export DIPLAY_AUTH_ASSETS_DIR="${DIPLAY_AUTH_ASSETS_DIR:-$PWD/local-auth}"
for f in identity.pk8 certificate.p7b; do
  [ -s "$DIPLAY_AUTH_ASSETS_DIR/offline-mfi/$f" ] || {
    echo "build-b561: missing $DIPLAY_AUTH_ASSETS_DIR/offline-mfi/$f" >&2
    exit 1
  }
done

OUT="mobile/build/outputs/apk/release/mobile-release.apk"
./gradlew :mobile:assembleRelease \
  -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease \
  --console=plain

PY="$(command -v python3 || command -v python)"
"$PY" scripts/verify_apk_identity.py "$OUT"
echo "build-b561: OK -> $OUT"
