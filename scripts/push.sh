#!/usr/bin/env bash
# 推送脚本：读走 gh-proxy.org，写直连 GitHub。
#
# ## 为什么这么配
#
# 本机 7890 代理时断时续（git push 常报
# `schannel: server closed abruptly (missing close_notify)`），
# 而 gh-proxy.org 是 URL 前缀式代理，不依赖本地 HTTP 代理端口。
#
# 但实测（本仓库验证过）：
#   - `git fetch gh-proxy main`  → **成功**（匿名读没问题）
#   - `git push gh-proxy main`   → **认证失败**。
#     gh-proxy.org 是只读镜像，**不接受** GitHub PAT 做写操作；
#     带 `x-access-token:TOKEN@` 也一样被拒（会 401）。
#   所以 remote.gh-proxy **故意不写凭据**（写了反而会 401）。
#
# 策略：**fetch 优先 gh-proxy（更稳），push 走直连 origin（唯一能写的通道）**。
#
# ## 用法
#   scripts/push.sh              # 推当前分支
#   scripts/push.sh main         # 指定分支
#   scripts/push.sh main --tags  # 顺带推标签
set -uo pipefail

BRANCH="${1:-$(git rev-parse --abbrev-ref HEAD)}"
PUSH_TAGS="${2:-}"

PROXY_REMOTE="gh-proxy"

have_proxy() { git remote get-url "$PROXY_REMOTE" >/dev/null 2>&1; }

# ── fetch：优先 gh-proxy，失败落回origin ──────────────────────────────────
if have_proxy; then
  echo "==> fetch 走 gh-proxy（$PROXY_REMOTE）"
  if git fetch "$PROXY_REMOTE" "$BRANCH" 2>&1 | sed 's/^/    /'; then
    echo "==> gh-proxy fetch 成功"
  else
    echo "==> gh-proxy fetch 失败，回退 origin"
    git fetch origin "$BRANCH" 2>&1 | sed 's/^/    /' || true
  fi
else
  echo "==> 未配置 $PROXY_REMOTE，直接 fetch origin"
  git fetch origin "$BRANCH" 2>&1 | sed 's/^/    /' || true
fi

# ── push：只能直连（gh-proxy 是只读镜像）─────────────────────────────────
echo "==> push 走 origin 直连（gh-proxy 不支持写）"
if ! git push origin "$BRANCH"; then
  echo "==> push 失败。诊断："
  echo "    1) 确认已登录： gh auth status"
  echo "    2) 7890 代理是否在跑： curl -s -o /dev/null -w '%{http_code}' -x http://127.0.0.1:7890 https://github.com"
  echo "    3) 分叉时先 fetch + rebase 再推"
  exit 1
fi
echo "==> push 成功"

[ -n "$PUSH_TAGS" ] && { echo "==> 推标签"; git push origin --tags; }
exit 0