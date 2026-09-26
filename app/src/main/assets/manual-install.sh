#!/bin/bash
# ─────────────────────────────────────────────────────────────
# 手动包安装器 —— 绕开 dpkg/apt，用于 Android proot 环境
#
# 【为什么需要它】2026-09-26 实测确认：
#   · dpkg -i 在这个环境里**必失败** —— 它要用裸 link() 做两件事：
#       升级前备份旧文件、备份自己的 status 数据库
#     而 Android App 沙箱禁止普通应用建硬链接。
#     proot 的 --link2symlink 帮不上（它建的是指向宿主机路径的断链）。
#   · 但 apt 的**下载**功能（纯 HTTP）和 dpkg-deb -x 的**解包**功能都正常。
# 所以这里自己实现「下载 → 解包 → 跑 postinst → 记状态」四步。
#
# 【已验证】用这套流程装 git，git init/add/commit/log 全部正常。
# ─────────────────────────────────────────────────────────────
set -u
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DEBIAN_FRONTEND=noninteractive
export TERM=dumb
export HOME=/root

WORK=/tmp/.ccm-pkg
STATUS=/var/lib/dpkg/status
mkdir -p "$WORK"
cd "$WORK" || exit 1

log() { echo "$@"; }

# ── 1) 解析依赖（递归，一层）──────────────────────────────
# apt-cache depends 不需要 dpkg 工作，可以拿来算依赖树。
resolve_deps() {
  local pkg
  for pkg in "$@"; do
    echo "$pkg"
    apt-cache depends --no-recommends --no-suggests --no-conflicts \
      --no-breaks --no-replaces --no-enhances "$pkg" 2>/dev/null \
      | awk '/^  (Depends|PreDepends):/ {gsub(/[<>]/,"",$2); print $2}' \
      | grep -v '^libc6$' || true
  done | sort -u
}

# ── 2) 下载 ────────────────────────────────────────────────
log "=== 解析依赖 ==="
PKGS=$(resolve_deps "$@" | tr '\n' ' ')
log "  需要: $PKGS"

log "=== 下载 ==="
# apt-get download 不会调用 dpkg，安全
# shellcheck disable=SC2086
apt-get download $PKGS 2>&1 | tail -5 || true
DEBS=$(ls *.deb 2>/dev/null | wc -l)
log "  已下载 $DEBS 个包"
[ "$DEBS" -eq 0 ] && { log "❌ 什么都没下到"; exit 1; }

# ── 3) 解包（不用 --link2symlink，硬链接失败不影响主文件）──
log "=== 解包 ==="
for d in *.deb; do
  # 记录硬链接失败项 —— 解包后要手动补相对符号链接
  out=$(dpkg-deb -x "$d" / 2>&1)
  if echo "$out" | grep -qi "hard link"; then
    echo "$out" | grep -i "hard link" | sed "s/^/  [$d] /"
  fi
done

# ── 3.5) 补硬链接：把 tar 报错的项建成相对符号链接 ─────────
# 保守做法：只处理那些「归档里是硬链接、但目标文件已存在」的情况。
# 复杂情况留给以后 —— 实测这批基础包没有需要补的。
fix_hardlinks() {
  local d link target
  for d in *.deb; do
    dpkg-deb --fsys-tarfile "$d" 2>/dev/null | tar -tvf - 2>/dev/null \
      | awk '$1 ~ /^hrw/ {print $NF, $(NF-2)}' | while read -r link _ target; do
        [ -z "$link" ] && continue
        # 目标在 rootfs 里的绝对路径
        local lp="/${link#./}" tp="/${target#./}"
        if [ -e "$tp" ] && [ ! -e "$lp" ]; then
          ln -sfn "$(basename "$tp")" "$lp" 2>/dev/null \
            && log "  补链接: $lp -> $(basename "$tp")"
        fi
      done
  done
}
log "=== 补硬链接 ==="
fix_hardlinks

# ── 4) 跑 postinst（失败不中断）─────────────────────────────
log "=== 配置（postinst）==="
for d in *.deb; do
  ctrl="$WORK/ctrl-$$"
  rm -rf "$ctrl"; mkdir -p "$ctrl"
  dpkg-deb -e "$d" "$ctrl" 2>/dev/null || continue
  if [ -x "$ctrl/postinst" ]; then
    if "$ctrl/postinst" configure 2>&1 | grep -viE '^$' | head -3; then
      :
    fi
  fi
  rm -rf "$ctrl"
done

# ── 5) 登记到 dpkg 数据库（让后续 apt 认为已装）─────────────
log "=== 登记状态 ==="
for d in *.deb; do
  pkg=$(dpkg-deb -f "$d" Package)
  ver=$(dpkg-deb -f "$d" Version)
  arch=$(dpkg-deb -f "$d" Architecture)
  [ -z "$pkg" ] && continue
  # 已登记就跳过
  if grep -q "^Package: $pkg$" "$STATUS" 2>/dev/null; then
    log "  $pkg 已在状态库，跳过"
    continue
  fi
  {
    echo ""
    echo "Package: $pkg"
    echo "Status: install ok installed"
    echo "Priority: optional"
    echo "Section: utils"
    echo "Installed-Size: 1"
    echo "Maintainer: ccm-manual-install"
    echo "Architecture: $arch"
    echo "Version: $ver"
    echo "Description: installed by ccm manual installer"
    echo "  (dpkg -i is unusable in this proot environment; see install.log)"
  } >> "$STATUS"
  log "  已登记 $pkg $ver"
done

log "=== 完成 ==="
