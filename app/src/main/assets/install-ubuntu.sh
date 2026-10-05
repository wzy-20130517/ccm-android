#!/system/bin/sh
# ═══════════════════════════════════════════════════════════════════
# CCM 环境安装脚本
#
# 【2026-10-05 全量照搬 Operit AI】
# 需求：「安装工具这条路反复修都有问题，这次完全照搬 Operit AI 的实现」。
#
# 来源：https://github.com/AAswordman/OperitTerminalCore
#      src/main/java/com/ai/assistance/operit/terminal/TerminalManager.kt
#      的 generateStartScript()（约 500 行 shell）
#
# 【照搬的部分（原样保留逻辑）】
#   · install_ubuntu    —— busybox tar 解压 + 锁文件防并发
#   · configure_sources —— apt/pip/uv/npm 四源配置
#   · fix_permissions   —— 补 Android 组（消除 "cannot find name for group ID"）
#   · login_ubuntu      —— proot 启动参数（-0 -r --link2symlink -w）
#   · setup_fake_sysdata—— 假 /proc 数据（proot-distro 方案，独立文件）
#
# 【适配 CCM 的部分】
#   · 路径：CCM 用 filesDir/rootfs，Operit 用 filesDir/usr/var/lib/proot-distro/...
#   · 去掉 chroot 分支（CCM 只用 proot）
#   · 去掉 SSH shell（CCM 暂不需要）
#
# 【调用方式】
#   sh install-ubuntu.sh install     # 只安装（解压+配置+权限）
#   sh install-ubuntu.sh login [cmd] # 进环境（执行 cmd 或交互 bash）
# ═══════════════════════════════════════════════════════════════════

set -u

# ── 由 Kotlin 层注入的环境变量 ──
# BIN         — nativeLibraryDir（proot/busybox 所在）
# HOME        — filesDir
# UBUNTU_PATH — rootfs 安装目标
# UBUNTU      — rootfs 压缩包文件名（在 $HOME 下）
# UBUNTU_NAME — 压缩包内顶层目录名（解压后要 mv 出来）
# TMPDIR      — 临时目录
# PROOT_LOADER— proot loader 路径

export PATH="$BIN:/system/bin:/system/xbin"
export L_NOT_INSTALLED="not installed"
export L_INSTALLING="installing"
export L_INSTALLED="installed"

# ═══ 进度上报（Operit 原样）═══
progress_echo(){
  echo "$@"
  if [ -n "${TMPDIR:-}" ]; then
    echo "$@" > "$TMPDIR/progress_des" 2>/dev/null || true
  fi
}
bump_progress(){
  current=0
  if [ -f "$TMPDIR/progress" ]; then
    current=$(cat "$TMPDIR/progress" 2>/dev/null || echo 0)
  fi
  next=$((current + 1))
  printf "%s" "$next" > "$TMPDIR/progress"
}

write_default_dns(){
  target_file="$1"
  if [ -z "$target_file" ]; then
    return 1
  fi
  cat > "$target_file" <<'EOF'
nameserver 223.5.5.5
nameserver 223.6.6.6
nameserver 119.29.29.29
nameserver 8.8.8.8
EOF
}

can_access_bind_source(){
  bind_source="$1"
  if [ -z "$bind_source" ]; then
    return 1
  fi
  if [ ! -e "$bind_source" ] && [ ! -L "$bind_source" ]; then
    return 1
  fi
  "$BIN/libbusybox.so" ls -Ld "$bind_source" >/dev/null 2>&1
}

append_proot_bind_arg(){
  bind_source="$1"
  bind_target="$2"
  if ! can_access_bind_source "$bind_source"; then
    return 0
  fi
  if [ -z "$bind_target" ] || [ "$bind_source" = "$bind_target" ]; then
    PROOT_BIND_ARGS="$PROOT_BIND_ARGS -b $bind_source"
  else
    PROOT_BIND_ARGS="$PROOT_BIND_ARGS -b $bind_source:$bind_target"
  fi
}

run_proot_binary(){
  LD_LIBRARY_PATH= "$BIN/libproot.so" "$@"
}
exec_proot_binary(){
  LD_LIBRARY_PATH= exec "$BIN/libproot.so" "$@"
}

# ═══ 安装（照搬 Operit install_ubuntu）═══
install_ubuntu(){
  OK_FILE="$UBUNTU_PATH/.ccm_installed_ok"
  LOCK_DIR="$UBUNTU_PATH.install.lock"
  LOCK_PID_FILE="$LOCK_DIR/pid"
  TMP_DIR="$UBUNTU_PATH.install.tmp"

  UBUNTU_PARENT="${UBUNTU_PATH%/*}"
  mkdir -p "$UBUNTU_PARENT" 2>/dev/null

  attempt=0
  while true; do
    if mkdir "$LOCK_DIR" 2>/dev/null; then
      echo "$$" > "$LOCK_PID_FILE" 2>/dev/null || true
      break
    fi
    if [ -f "$LOCK_PID_FILE" ]; then
      lock_pid=$(cat "$LOCK_PID_FILE" 2>/dev/null)
      if [ -z "$lock_pid" ]; then
        if [ "$attempt" -gt 2 ]; then
          rm -rf "$LOCK_DIR" 2>/dev/null
          continue
        fi
      elif ! kill -0 "$lock_pid" 2>/dev/null; then
        rm -rf "$LOCK_DIR" 2>/dev/null
        continue
      fi
    else
      if [ "$attempt" -gt 2 ]; then
        rm -rf "$LOCK_DIR" 2>/dev/null
        continue
      fi
    fi
    attempt=$((attempt + 1))
    if [ "$attempt" -gt 120 ]; then
      progress_echo "Ubuntu install lock timeout"
      return 1
    fi
    sleep 1
  done

  cleanup_install(){
    rm -rf "$TMP_DIR" 2>/dev/null
    rm -rf "$LOCK_DIR" 2>/dev/null
  }
  trap 'cleanup_install' EXIT INT TERM

  if [ -f "$OK_FILE" ]; then
    VERSION=$(cat "$UBUNTU_PATH/etc/issue.net" 2>/dev/null)
    progress_echo "Ubuntu $L_INSTALLED -> $VERSION"
  else
    if [ -f "$UBUNTU_PATH/etc/issue.net" ]; then
      echo "ok" > "$OK_FILE" 2>/dev/null || true
      VERSION=$(cat "$UBUNTU_PATH/etc/issue.net" 2>/dev/null)
      progress_echo "Ubuntu $L_INSTALLED -> $VERSION"
    else
      progress_echo "Ubuntu $L_NOT_INSTALLED, $L_INSTALLING..."
      if [ ! -f "$HOME/$UBUNTU" ]; then
        progress_echo "错误：找不到 $HOME/$UBUNTU"
        cleanup_install
        trap - EXIT INT TERM
        return 1
      fi
      rm -rf "$TMP_DIR" 2>/dev/null
      mkdir -p "$TMP_DIR" 2>/dev/null
      progress_echo "解压 rootfs…"
      "$BIN/libbusybox.so" tar xf "$HOME/$UBUNTU" -C "$TMP_DIR"/ 2>&1 | tail -5
      if [ $? -ne 0 ]; then
        progress_echo "解压失败"
        cleanup_install
        trap - EXIT INT TERM
        return 1
      fi
      progress_echo "解压完成"
      # 顶层目录移出来（Operit 原样）
      if [ -d "$TMP_DIR/$UBUNTU_NAME" ]; then
        mv "$TMP_DIR/$UBUNTU_NAME"/* "$TMP_DIR"/ 2>/dev/null
        rm -rf "$TMP_DIR/$UBUNTU_NAME" 2>/dev/null
      fi

      mkdir -p "$TMP_DIR/root" 2>/dev/null
      echo 'export ANDROID_DATA=/home/' >> "$TMP_DIR/root/.bashrc"
      mkdir -p "$TMP_DIR/etc" 2>/dev/null
      write_default_dns "$TMP_DIR/etc/resolv.conf"
      echo "ok" > "$TMP_DIR/.ccm_installed_ok" 2>/dev/null || true

      rm -rf "$UBUNTU_PATH" 2>/dev/null
      mv "$TMP_DIR" "$UBUNTU_PATH" 2>/dev/null
      if [ $? -ne 0 ]; then
        progress_echo "移动失败"
        cleanup_install
        trap - EXIT INT TERM
        return 1
      fi
    fi
  fi

  mkdir -p "$UBUNTU_PATH/etc" 2>/dev/null
  write_default_dns "$UBUNTU_PATH/etc/resolv.conf"

  rm -rf "$LOCK_DIR" 2>/dev/null
  trap - EXIT INT TERM
  return 0
}

# ═══ 源配置（照搬 Operit configure_sources，源换成 CCM 默认）═══
configure_sources(){
  progress_echo "配置软件源…"
  mkdir -p "$UBUNTU_PATH/etc/apt" 2>/dev/null
  cat > "$UBUNTU_PATH/etc/apt/sources.list" <<'EOF'
deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble main restricted universe multiverse
deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble-updates main restricted universe multiverse
deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble-backports main restricted universe multiverse
deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble-security main restricted universe multiverse
EOF

  # pip / uv
  mkdir -p "$UBUNTU_PATH/root/.config/pip" 2>/dev/null
  cat > "$UBUNTU_PATH/root/.config/pip/pip.conf" <<'EOF'
[global]
index-url = https://pypi.tuna.tsinghua.edu.cn/simple
EOF
  mkdir -p "$UBUNTU_PATH/root/.config/uv" 2>/dev/null
  echo 'index-url = "https://pypi.tuna.tsinghua.edu.cn/simple"' > "$UBUNTU_PATH/root/.config/uv/uv.toml"

  # npm
  mkdir -p "$UBUNTU_PATH/root" 2>/dev/null
  echo 'registry=https://registry.npmmirror.com' > "$UBUNTU_PATH/root/.npmrc"
}

# ═══ 权限修复（照搬 Operit fix_permissions）═══
fix_permissions(){
  progress_echo "修复权限…"
  current_groups=$(id -G)
  for gid in $current_groups; do
    if ! grep -q ":$gid:" "$UBUNTU_PATH/etc/group" 2>/dev/null; then
      echo "android_group_$gid:x:$gid:" >> "$UBUNTU_PATH/etc/group"
    fi
  done
  # 宿主 UID 条目（消除 "cannot find name for user ID" 警告）
  uid=$(id -u)
  if ! grep -q "aid_u$uid:" "$UBUNTU_PATH/etc/passwd" 2>/dev/null; then
    echo "aid_u$uid:x:$uid:$uid:Android User:/:/bin/bash" >> "$UBUNTU_PATH/etc/passwd"
  fi
}

# ═══ 进环境（照搬 Operit login_ubuntu，去掉 chroot 分支）═══
login_ubuntu(){
  COMMAND_TO_EXEC="$1"
  if [ -z "$COMMAND_TO_EXEC" ]; then
    COMMAND_TO_EXEC="/bin/bash -il"
  fi

  # 假 /proc 数据（照搬 Operit）
  export INSTALLED_ROOTFS_DIR=$(dirname "$UBUNTU_PATH")
  export distro_name=$(basename "$UBUNTU_PATH")
  if [ -f "$HOME/setup_fake_sysdata.sh" ]; then
    . "$HOME/setup_fake_sysdata.sh"
    setup_fake_sysdata
  fi

  # 必要的挂载点目录
  mkdir -p "$UBUNTU_PATH/proc" "$UBUNTU_PATH/sys" "$UBUNTU_PATH/dev" 2>/dev/null
  mkdir -p "$UBUNTU_PATH/dev/pts" 2>/dev/null
  mkdir -p "$UBUNTU_PATH/sdcard" 2>/dev/null
  mkdir -p "$UBUNTU_PATH/data/local/tmp" 2>/dev/null
  mkdir -p "$UBUNTU_PATH/storage/emulated" 2>/dev/null

  PROOT_BIND_ARGS=""
  append_proot_bind_arg "/dev" "/dev"
  append_proot_bind_arg "/sys" "/sys"
  append_proot_bind_arg "/dev/pts" "/dev/pts"
  append_proot_bind_arg "/data/local/tmp" "/data/local/tmp"
  append_proot_bind_arg "/storage/emulated/0" "/sdcard"

  # 假 /proc 文件绑定（仅当宿主没有时）
  if [ ! -e /proc/stat ]; then append_proot_bind_arg "$UBUNTU_PATH/proc/.stat" "/proc/stat"; fi
  if [ ! -e /proc/loadavg ]; then append_proot_bind_arg "$UBUNTU_PATH/proc/.loadavg" "/proc/loadavg"; fi
  if [ ! -e /proc/uptime ]; then append_proot_bind_arg "$UBUNTU_PATH/proc/.uptime" "/proc/uptime"; fi
  if [ ! -e /proc/version ]; then append_proot_bind_arg "$UBUNTU_PATH/proc/.version" "/proc/version"; fi
  if [ ! -e /proc/vmstat ]; then append_proot_bind_arg "$UBUNTU_PATH/proc/.vmstat" "/proc/vmstat"; fi

  if [ -n "$PROOT_BIND_ARGS" ]; then
    set -- $PROOT_BIND_ARGS
  else
    set --
  fi

  exec_proot_binary \
    -0 \
    -r "$UBUNTU_PATH" \
    --link2symlink \
    "$@" \
    -w /root \
    /usr/bin/env -i \
      HOME=/root \
      TERM=xterm-256color \
      LANG=en_US.UTF-8 \
      PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
      COMMAND_TO_EXEC="$COMMAND_TO_EXEC" \
      /bin/bash -lc 'echo LOGIN_SUCCESSFUL; echo TERMINAL_READY; eval "$COMMAND_TO_EXEC"'
}

# ═══ 主入口 ═══
ACTION="${1:-install}"
shift 2>/dev/null || true

case "$ACTION" in
  install)
    install_ubuntu
    rc=$?
    if [ $rc -ne 0 ]; then
      progress_echo "安装失败（install_ubuntu 返回 $rc）"
      exit 1
    fi
    configure_sources
    fix_permissions
    progress_echo "环境安装完成"
    exit 0
    ;;
  login)
    login_ubuntu "$@"
    ;;
  *)
    echo "用法: $0 {install|login [命令]}"
    exit 1
    ;;
esac
