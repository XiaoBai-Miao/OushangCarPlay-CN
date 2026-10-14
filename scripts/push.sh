#!/usr/bin/env bash
# 推送脚本：优先走 gh-proxy.org，失败自动回退直连。
#
# 背景：本机 7890 代理时断时续（git push 常报
# `schannel: server closed abruptly (missing close_notify)`），
# 而 gh-proxy.org 是 URL 前缀式代理，不依赖本地 HTTP 代理端口。
# 所以策略是：**先试 gh-proxy 通道，失败再落回 origin 直连**。
#
# 用法：
#   scripts/push.sh                 # 推当前分支
#   scripts/push.sh main            # 指定分支
#   scripts/push.sh --tags          # 推标签
set -uo pipefail

BRANCH="${1:-$(git rev-parse --abbrev-ref HEAD)}"
TAGS="${2:-}"

# gh-proxy 通道的 remote 名（见 .git/config 的 remote.gh-proxy.url）
PROXY_REMOTE="gh-proxy"
PROXY_BASE="https://gh-proxy.org/"

have_proxy() {
  git remote get-url "$PROXY_REMOTE" >/dev/null 2>&1
}

# 拼出 gh-proxy 形式的 URL（不改动已配置的 remote）
proxy_url() {
  local url
  url="$(git remote get-url origin 2>/dev/null)"
  [ -n "$url" ] || return 1
  echo "${PROXY_BASE}${url}"
}

echo "==> 分支: $BRANCH"

if have_proxy; then
  echo "==> 尝试 gh-proxy 通道（$PROXY_REMOTE）"
  if git push "$PROXY_REMOTE" "$BRANCH" 2>&1 | tee /tmp/.push-proxy.log; then
    echo "==> gh-proxy 推送成功"
    exit 0
  fi
  echo "==> gh-proxy 推送失败，回退直连 origin"
  grep -iE "error|fatal|denied|could not|unable" /tmp/.push-proxy.log | head -5
else
  echo "==> 未配置 $PROXY_REMOTE remote，直接走 origin"
fi

echo "==> 尝试直连 origin"
if git push origin "$BRANCH"; then
  echo "==> 直连推送成功"
else
  echo "==> 直连也失败。诊断："
  git ls-remote origin HEAD 2>&1 | head -3
  exit 1
fi

[ -n "$TAGS" ] && git push origin --tags