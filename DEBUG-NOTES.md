# apt 安装 perl-base 失败 —— 排查记录（2026-09-26）

## 已确凿的事实

### 1. 精确复现（用 CCM 自己的 proot）
```
LD_LIBRARY_PATH=$D/lib/arm64 PROOT_LOADER=$D/lib/arm64/libproot-loader.so \
PROOT_TMP_DIR=/data/user/0/com.ccm.app/cache \
PROOT_L2S_DIR=/data/user/0/com.ccm.app/files/l2s \
$D/lib/arm64/libproot.so -0 --link2symlink -L \
  --rootfs=/data/user/0/com.ccm.app/files/rootfs -w / \
  /bin/bash -c "dpkg -i --force-depends /var/cache/apt/archives/perl-base_*.deb"
```
→ **必失败**：`error setting ownership of '/usr/bin/perl5.38.2.dpkg-new': No such file or directory`
              `dpkg-deb: error: paste subprocess was killed by signal (Broken pipe)`

同一环境下换成 `dpkg-deb -x`（纯解包，不安装）→ **必成功**（perl 和 perl5.38.2 都解出来）

### 2. 已排除的假设（都做过对照实验）
| 假设 | 结论 |
|---|---|
| link2symlink 没生效 | ❌ `.l2s` 目录里有正常产出（.l2s.perl0001 等），符号链接形式正确 |
| PROOT_L2S_DIR 路径不在客户机视角 | ❌ 宿主机路径和 rootfs 内路径**都能成功解包** |
| .l2s 目录不存在导致静默失效 | ❌ 目录存在且可写 |
| 旧文件（符号链接）冲突 | ❌ 删掉 perl/perl5.38.2 后依然失败 |
| paste 命令不可用 | ❌ 手动跑 paste 正常（EXIT=0） |
| chown 权限问题 | ❌ proot 的 -0 正确拦截，chown 返回 0 |
| 磁盘满 / OOM | ❌ 103G 可用，内存 4.4G 可用 |
| --change-id 该换 -0 | ❌ 实测两者行为一致（但 -0 更标准，保留） |

### 3. 当前卡点
`dpkg -i` 与 `dpkg-deb -x` 的唯一差别：**`-i` 边解包边 chown**。
失败信息说 `.dpkg-new` 不存在，但 `paste` 被杀才是**先发生的**（Broken pipe 是它的结果）。

推测：dpkg 解包时用 `paste` 拼接文件名列表（`dpkg-deb` 的 `--fsys-tarfile` 流程），
这个子进程在 proot 下被信号杀死。**信号来源未知**（logcat 里没有 OOM/SELinux 记录）。

### 4. 下一步方向（未验证）
- 用 strace 跟 `dpkg -i` 的子进程（proot 下可能跟不了）
- 查 dpkg 源码里 `paste` 的调用点（`dpkg-deb` 的 `tar_subproc`？）
- **绕过 dpkg -i**：直接用 `dpkg-deb -x` 解包 + 手工写 `/var/lib/dpkg/status` 条目
  （激进，但既然 -x 能成功，这条路理论可行）

---

## 追加发现（同日稍后）

### 5. 更大的问题：**整个 rootfs 的包都是「假安装」状态**
```
libc6:  Status: install ok installed     ← dpkg 数据库说装了
但 /lib64/ 目录压根不存在！               ← 文件根本没解出来
```
这不是 perl-base 一个包的问题 —— **rootfs 里大量包都是「解包失败但状态标为已安装」**。
所以修好一个包不够，得让这些包**全部重新解包**。

### 6. 「动态链接器缺失」导致 exit 127 的完整链
```
/lib64/ld-linux-aarch64.so.1  不存在（本该由 libc6 提供）
  → /usr/bin/perl 执行时报 "No such file or directory"
    （文件明明在，是它的 interpreter 找不到 —— 经典误导症状）
  → debconf 的 frontend（perl 脚本）跑不了
  → 所有加载 confmodule 的 postinst 全部 exit 127
```

### 7. 所有单独步骤都成功，组合起来失败
| 操作 | 结果 |
|---|---|
| `dpkg-deb -x perl-base.deb` | ✅ 成功 |
| `dpkg-deb --fsys-tarfile \| tar -x`（模拟 dpkg 的方式） | ✅ 成功 |
| `paste` 命令本身 | ✅ 正常 |
| **`dpkg -i perl-base.deb`** | ❌ 失败 |

**这是本次排查最硬的矛盾** —— 解包能力没问题，问题出在 `dpkg -i` 自己的流程里。

### 8. 两种 proot 参数的错误对照（同一根因，两种面具）
```
不带 --link2symlink: error creating hard link './usr/bin/perl5.38.2': Permission denied
带 --link2symlink:   error setting ownership of '/usr/bin/perl5.38.2.dpkg-new':
                     No such file or directory
                     dpkg-deb: error: paste subprocess was killed by signal (Broken pipe)
```
**结论：`--link2symlink` 确实在拦截，但没有真正解决** —— 只是把
「权限拒绝」换成了「文件不存在」，后者更难看懂。

### 9. 下一步方向（建议）
1. **优先**：查 `dpkg -i` 内部为什么 `paste` 子进程被杀。
   候选：proot 的 ptrace 在 fork+exec 密集场景下的竞态；
   或 dpkg 用了某个 proot 未完整模拟的系统调用。
2. **备选（激进但可行）**：绕开 `dpkg -i`，改用
   `dpkg-deb -x` 解包 + 手工维护 `/var/lib/dpkg/status`。
   既然 -x 和 tar 都能成功，这条路技术上成立，只是要自己实现
   「解包 → 跑 postinst → 更新数据库」三步。
3. 检查 rootfs 是不是**一开始就没解压完整**（ubuntu-base tar 解压时也失败过？）
   —— 如果源头就缺文件，那装多少遍包都没用。

---

# 最终定位（2026-09-26 收尾）

## 核心矛盾（一句话）
**所有分解动作都成功，只有 `dpkg -i` 失败。**

| 动作（都在 CCM 的 proot + rootfs 里） | 结果 |
|---|---|
| `ln h1 h2` 在 /usr/bin（PROOT_L2S_DIR=宿主机路径） | ✅ EXIT=0 |
| `ln h1 h2` 在 /usr/bin（PROOT_L2S_DIR=/.l2s） | ❌ Operation not permitted |
| `dpkg-deb -x perl-base.deb` | ✅ 成功 |
| `dpkg-deb --fsys-tarfile \| tar -x` 在 /usr/bin 下 | ✅ 成功（链接数 2） |
| `dpkg-deb -I`（读控制信息） | ✅ 正常 |
| **`dpkg -i perl-base.deb`** | ❌ error setting ownership of '.dpkg-new' + paste subprocess killed |

## PROOT_L2S_DIR 的正确取值（已实测确认）
- ✅ **宿主机绝对路径** → link2symlink 生效
- ❌ 客户机路径（/.l2s）→ 静默失效
- ❌ 不设该变量 → 也失效
（proot 靠这个变量找自己的工作目录，所以必须是它自己视角的路径。
 曾担心链接目标会变断链，实测 proot 会做转换，不会断。）

## 未解的最后一步
`dpkg -i` 内部用 `paste` 子进程（dpkg 处理 `--fsys-tarfile` 的
文件列表时拼接用），该子进程**被信号杀死**（Broken pipe 是它的结果，
不是原因）。同一条管道手工跑完全正常。

**信号来源未知** —— logcat 无 OOM/SELinux 记录。
候选方向：
1. proot 的 ptrace 在 dpkg 的 fork+exec 密集场景下有竞态
2. dpkg 用了 proot 未完整模拟的系统调用（如 `process_vm_readv`、
   或 `linkat` 的某个 flag 组合）
3. 需要 strace 跟踪（但 proot 下 strace 通常也跑不了）

## 建议的下一步
既然 `dpkg-deb -x` 和 `tar -x` 都稳定成功，
**绕开 `dpkg -i` 自己实现安装**是可行的：
  1. `dpkg-deb -x` 解包到 rootfs
  2. 手工跑 postinst（大部分能跑；跑不了的记下来）
  3. 手工往 /var/lib/dpkg/status 追加条目
这比继续跟 dpkg 的 paste 子进程较劲更实际。
