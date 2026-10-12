package com.ccm.app.tools.file

import com.ccm.app.tools.bash.BashChannel

/**
 * 通道文件系统 —— 通过 [BashChannel] 在 Termux 侧完成文件读写。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么需要它（问题的本质）
 * ══════════════════════════════════════════════════════════════
 *
 * 用户选「外接 Termux」后，Bash 命令跑在 Termux 进程里，而文件工具用
 * `java.io.File`（Android API）。两者**不是同一个文件系统视图**：
 *
 * ```
 * Bash : cd /data/data/com.termux/files/home && ls     → Termux 的 home
 * Write: File("/data/data/com.termux/files/home/a.txt") → Permission denied
 * ```
 *
 * 后果：Write 写的文件 Bash 读不到，Bash 写的文件 Read 看不到 ——
 * 模型会陷入「我明明写了」的循环。
 *
 * **绕不开**：CCM 直接读不了 Termux 私有目录（跨沙箱，`run-as com.termux`
 * 实测 Permission denied）。唯一可行路径是把读写也交给 Termux 执行。
 *
 * ══════════════════════════════════════════════════════════════
 *  路径语义
 * ══════════════════════════════════════════════════════════════
 *
 * · **相对路径** → 基于 Termux 的 `$HOME`（[TERMUX_HOME]）。本类的所有命令
 *   都以 `workDir = null` 下发，[com.ccm.app.tools.bash.TermuxChannel] 会把它
 *   换成 TERMUX_HOME。所以 `cat a.txt` 就是 `$HOME/a.txt`。
 * · **绝对路径** → 原样交给 Termux 解析（`/sdcard/...` 两边都通，因为
 *   `/sdcard` 是同一个物理目录的挂载点）。
 *
 * ⚠️ Android 侧的 `ctx.cwd` 在这里**不参与解析** —— 它是 App 私有目录下的
 * 路径，Termux 侧看不到，拿来当 workDir 只会让命令 cd 失败。通道模式忽略它。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么写文件用 base64 而不是 heredoc
 * ══════════════════════════════════════════════════════════════
 *
 * heredoc（`cat > f << 'EOF' ... EOF`）看着更省体积，但有一个**无法根除**
 * 的风险：内容里恰好出现定界符那一行就会提前截断。随机定界符只把概率
 * 降到极低 —— 而文件内容是模型生成的，完全不可控。
 *
 * base64 编码后只剩 `A-Za-z0-9+/=`，**不可能**破坏 shell 结构。
 * 代价是体积 +33%，用分块（[CHUNK_BYTES]）摊掉。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ 两个硬限制（改这个文件前必须知道）
 * ══════════════════════════════════════════════════════════════
 *
 * 1. **Binder 事务约 1MB**：RUN_COMMAND 的 Intent extras 和结果回传都过
 *    Binder。单条命令、单次输出都不能逼近 1MB，否则
 *    `TransactionTooLargeException` —— 而且**收不到任何结果**，表现为超时。
 *    这就是 [CHUNK_BYTES] / [MAX_OUTPUT_BYTES] 的由来，别随手调大。
 * 2. **stat 要额外往返**：所以本类把「stat + 读内容」**合并成一条命令**
 *    （先 echo 一行状态，再输出内容），省一次 Intent 往返。
 */
class ChannelFs(private val channel: BashChannel) {

    companion object {
        /** 通道里的默认工作目录 —— Termux 的 home（与 TermuxChannel 保持一致）。 */
        const val TERMUX_HOME = "/data/data/com.termux/files/home"

        const val TIMEOUT_READ = 30_000L
        const val TIMEOUT_WRITE = 60_000L
        const val TIMEOUT_SEARCH = 60_000L
        const val TIMEOUT_PROBE = 8_000L

        /**
         * 单次读操作回传的字节上限。
         *
         * 不是「磁盘上的限制」，是 **Binder 事务限制**下的安全值：
         * stdout 要经 Termux 的 Bundle → PendingIntent → CCM 的 Service 三层传递，
         * 逼近 1MB 会直接抛异常且调用方什么都收不到。
         */
        const val MAX_OUTPUT_BYTES = 256 * 1024

        /** 单次写入的原始字节上限（超过请让模型分多次写）。 */
        const val MAX_WRITE_BYTES = 4L * 1024 * 1024

        /**
         * 每块 base64 承载的**原始**字节数。
         *
         * ⚠️ **这个值是实测出来的硬限制，不要随手调大**。
         *
         * 命令最终以 `bash -c "<整条命令>"` 的形式下发，所以整条命令是
         * **一个 argv 元素**，受 Linux 的 `MAX_ARG_STRLEN = 128KB` 约束。
         * 超过时 `execve` 直接失败 —— 进程被内核杀掉，**没有任何输出、
         * 没有报错**，调用方只看到「超时 / 空结果」，极难排查。
         *
         * 实测（Termux，bash 5.x）：命令长度 131008 字节可通过，
         * 131072 字节（正好 128KB）失败。所以留足余量：
         * 64KB 原始字节 → base64 后约 87KB，加上命令包装仍在 128KB 内。
         *
         * 另一条约束：Intent extras 过 Binder（1MB），87KB 远在安全区。
         *
         * 代价是写 1MB 文件要 16 次 Binder 往返（每次约 50~200ms）——
         * 慢，但正确。想优化只能换方案（如先写 /sdcard 再让 Termux 搬运），
         * 那会引入「/sdcard 不可写」的新失败模式，不值当。
         */
        const val CHUNK_BYTES = 64 * 1024

        /**
         * 批量读（[readBatch]）的单文件上限。
         *
         * 超过它的文件**不参与批量**（调用方可以退化成逐个 [read]，
         * 那条路的上限是 [MAX_OUTPUT_BYTES]）。
         */
        const val MAX_BATCH_FILE_BYTES = 120 * 1024

        /**
         * 批量读单批最多几个文件。
         *
         * 整条命令是**一个 argv 元素**，受 `MAX_ARG_STRLEN = 128KB` 约束
         * （见 [CHUNK_BYTES] 的说明）。路径平均 80 字节 → 60 个约 5KB，安全。
         */
        const val MAX_BATCH_FILES = 60

        /**
         * 批量读单批的**累计**原始字节上限。
         *
         * 与 [MAX_BATCH_FILE_BYTES] 是两个正交的闸门：前者防「一个巨文件」，
         * 后者防「六十个中等文件加起来撑爆 Binder」。
         *
         * ⚠️ 必须**大于** [MAX_BATCH_FILE_BYTES]，否则一个合法大小的文件
         * 会既通过单文件检查、又被总预算拒掉（自相矛盾的配置）。
         * 当前 160KB 原始 → base64 约 213KB，加标记行仍在 [MAX_OUTPUT_BYTES] 之下。
         */
        const val MAX_BATCH_TOTAL_BYTES = 160 * 1024

        private const val STAT_PREFIX = "CCM_S:"
        private const val OK_PREFIX = "CCM_OK:"
        private const val ERR_PREFIX = "CCM_E:"

        /**
         * 批量读（[readBatch]）的标记行前缀。
         *
         * 选 `CCM_B:` 有个**安全理由**：base64 的字符集是
         * `A-Za-z0-9+/=`，**不含冒号** —— 所以内容行永远不可能被误认成标记行，
         * 解析不需要任何转义或长度前缀。这是刻意的设计，不是巧合。
         */
        private const val BATCH_PREFIX = "CCM_B:"

        /**
         * 批量读里**内容行**的前缀。
         *
         * 与 [BATCH_PREFIX] 分开是为了解析时能一眼区分「元数据行」和「内容行」，
         * 不必靠「隔行取」这种脆弱约定（一旦某行缺失就全盘错位）。
         */
        private const val CONTENT_PREFIX = "CCM_C:"
    }

    // ══════════════════════════════════════════════════════════════
    //  结果类型
    // ══════════════════════════════════════════════════════════════

    sealed class ReadResult {
        data class Ok(val content: String, val size: Long, val mtimeMs: Long) : ReadResult()
        data class NotFound(val path: String) : ReadResult()
        data class IsDirectory(val path: String) : ReadResult()
        data class TooLarge(val path: String, val size: Long, val limit: Long) : ReadResult()
        data class Failed(val path: String, val message: String) : ReadResult()
    }

    sealed class WriteResult {
        data class Ok(val path: String, val bytes: Long, val mtimeMs: Long) : WriteResult()
        data class TooLarge(val path: String, val size: Long, val limit: Long) : WriteResult()
        data class Failed(val path: String, val message: String) : WriteResult()
    }

    data class ListResult(
        val files: List<String>,
        val truncated: Boolean = false,
        val error: String? = null,
    )

    data class GrepHit(val path: String, val line: Int, val text: String)

    data class GrepResult(val hits: List<GrepHit>, val error: String? = null)

    /** 批量读回来的单个文件。 */
    data class BatchFile(
        val path: String,
        val content: String,
        val size: Long,
        val mtimeMs: Long,
    )

    /**
     * 批量读的结果。
     *
     * @param files 读到的文件（**按输入顺序**，不是路径字典序）
     * @param skipped 没能读回来的路径。两种原因：超过 [MAX_BATCH_FILE_BYTES]
     *   （大文件），或**不是普通文件**（读的过程中被删了 / 是目录）。
     *   ⚠️ 写操作（如 SafeRename）必须把它报给用户 —— 静默跳过会造成
     *   「以为全改了、其实有文件没改」的半成品。
     * @param processed 本次实际处理了几个输入路径（含 [skipped] 的）。
     *   ⚠️ **不是** `files.size` —— 批内因累计预算（[MAX_BATCH_TOTAL_BYTES]）
     *   提前停下时，两者不等。调用方靠它决定「下一批从哪里开始」，
     *   用 `files.size` 会重复读或漏读。
     * @param truncated 因 [readBatch] 的 `maxFiles` 被截断（目录里还有更多）
     * @param error 致命错误（通道超时 / 不可用）。非 null 时 [files] 可能不完整。
     */
    data class BatchReadResult(
        val files: List<BatchFile> = emptyList(),
        val skipped: List<String> = emptyList(),
        val processed: Int = 0,
        val truncated: Boolean = false,
        val error: String? = null,
    )

    // ══════════════════════════════════════════════════════════════
    //  shell 基础工具
    // ══════════════════════════════════════════════════════════════

    /**
     * 单引号转义 —— shell 里唯一**绝对安全**的引用方式。
     *
     * 单引号内除了单引号本身，其余字符（`$` 反引号 `\` `!` 空格换行…）
     * 全部是字面量。单引号本身的写法是经典的 `'\''`（收尾 + 转义 + 重开）。
     */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * 给版本追踪 / 展示用的规范化 key。
     *
     * 做两件事：
     *  1. 相对路径展开成基于 [TERMUX_HOME] 的绝对形式 —— 否则同一个文件
     *     因为「这次用相对路径、下次用绝对路径」被当成两个文件，
     *     并发保护会静默失效。
     *  2. 清理 `.` 段与重复斜杠 —— `find .` 会产出 `./a.txt` 这种带 `./` 的
     *     路径，直接拼出来是 `/data/.../home/./a.txt`，与 Read 时记下的
     *     `/data/.../home/a.txt` 对不上，同样会让并发保护失效。
     *
     * 【实现】按 `/` 切段、丢掉空段与 `.` 段、再拼回去。
     * 不用「扫字符串 + 跳字符」那种写法 —— 连续 `././` 序列会让状态机
     * 漏掉分隔符（实测踩过：`/a/././b` 会变成 `/a/./b`）。
     * 切段法没有状态，每个输入都等价于「保留所有有意义的段」，不会错。
     *
     * ⚠️ **`..` 不解析** —— 与 Android 侧 [FileVersionTracker] 用
     *    `File.canonicalPath`（会解析 `..`）不同。这里刻意不做：解析 `..`
     *    要么发命令问 Termux（多一次 Binder 往返），要么自己写符号链接
     *    语义（容易错）。`..` 在模型给的路径里很罕见，代价可接受。
     *    已知局限，写在这里避免后人误以为「和 canonicalPath 等价」。
     *
     * 纯字符串处理，不发命令（所以不是 `realpath`）。
     */
    fun canonicalKey(path: String): String {
        val abs = if (path.startsWith("/")) path else "$TERMUX_HOME/$path"
        val parts = abs.split("/").filter { it.isNotEmpty() && it != "." }
        // abs 一定以 `/` 开头（绝对路径本身如此；相对路径拼的 HOME 也是绝对路径），
        // 所以结果也一定以 `/` 开头。全被过滤掉时就是根目录。
        return "/" + parts.joinToString("/")
    }

    private suspend fun run(cmd: String, timeoutMs: Long): BashChannel.ExecResult =
        try {
            channel.execute(cmd, null, timeoutMs) {}
        } catch (e: Throwable) {
            BashChannel.ExecResult(exitCode = -1, stdout = "", stderr = e.message ?: "通道调用失败")
        }

    /** 通道报错时的可读描述（stderr 优先，因为它通常带真正的原因）。 */
    private fun describe(r: BashChannel.ExecResult): String = when {
        r.timedOut -> "通道执行超时"
        r.stderr.isNotBlank() -> r.stderr.trim()
        r.stdout.isNotBlank() -> r.stdout.trim().take(300)
        else -> "通道无输出（exit=${r.exitCode}）"
    }

    // ══════════════════════════════════════════════════════════════
    //  读
    // ══════════════════════════════════════════════════════════════

    /**
     * 读文件。一次往返同时拿到内容与 stat（size / mtime）。
     *
     * @param startLine 起始行（1-based）；null = 从第 1 行
     * @param endLine 结束行（含）；null = 到文件末尾
     */
    suspend fun read(
        path: String,
        startLine: Int? = null,
        endLine: Int? = null,
    ): ReadResult {
        val p = quote(path)
        val range: String? = if (startLine != null) {
            if (endLine != null) "$startLine,${endLine}p" else "$startLine,\$p"
        } else {
            null
        }
        val sedBody = if (range != null) "sed -n ${quote(range)} \"\$p\"" else "cat \"\$p\""

        val cmd = buildString {
            append("p=").append(p).append("; ")
            append("if [ ! -e \"\$p\" ]; then echo '${STAT_PREFIX}N'; exit 0; fi; ")
            append("if [ -d \"\$p\" ]; then echo '${STAT_PREFIX}D'; exit 0; fi; ")
            append("sz=\$(wc -c < \"\$p\" 2>/dev/null | tr -d ' '); [ -z \"\$sz\" ] && sz=0; ")
            append("mt=\$(stat -c '%Y' \"\$p\" 2>/dev/null || echo 0); ")

            if (range == null) {
                // 整文件读：先看总大小，超限直接拒绝（省一次 I/O）
                append("if [ \"\$sz\" -gt ").append(MAX_OUTPUT_BYTES)
                    .append(" ]; then echo '${STAT_PREFIX}T' \$sz; exit 0; fi; ")
                append("echo \"${STAT_PREFIX}F \$sz \$mt\"; ")
                append(sedBody)
            } else {
                // ⚠️ 分段读也要挡大小 —— 只有 end_line 给了才有上界；
                // `start_line` 不给 end_line（如 `sed -n '5000,$p'`）是**开区间**，
                // 大文件的尾部照样能把 Binder 撑爆（表现为超时 / 收不到结果）。
                //
                // 量的时候用 `head -c MAX+1` 截断，所以第一遍**最多只读 MAX+1 字节**
                // （head 提前退出会让 sed 收到 SIGPIPE 停下），不会为了量大小
                // 把整个大文件过一遍。
                append("n=\$(").append(sedBody).append(" 2>/dev/null | head -c ")
                    .append(MAX_OUTPUT_BYTES + 1).append(" | wc -c | tr -d ' '); ")
                append("[ -z \"\$n\" ] && n=0; ")
                append("if [ \"\$n\" -gt ").append(MAX_OUTPUT_BYTES)
                    .append(" ]; then echo '${STAT_PREFIX}T' \$sz; exit 0; fi; ")
                append("echo \"${STAT_PREFIX}F \$sz \$mt\"; ")
                append(sedBody)
            }
        }

        return parseRead(path, run(cmd, TIMEOUT_READ))
    }

    private fun parseRead(path: String, r: BashChannel.ExecResult): ReadResult {
        if (r.timedOut) return ReadResult.Failed(path, "读取超时（${TIMEOUT_READ}ms）")

        val out = r.stdout
        if (out.isEmpty()) return ReadResult.Failed(path, describe(r))

        // 第一行一定是状态行（命令保证），其余是文件内容 —— 不能用 split，
        // 因为内容里可能有任意多的换行，split 会把它们全部拆开再拼回去（浪费）。
        val nl = out.indexOf('\n')
        val head = if (nl >= 0) out.substring(0, nl) else out
        val rest = if (nl >= 0) out.substring(nl + 1) else ""

        if (!head.startsWith(STAT_PREFIX)) {
            return ReadResult.Failed(path, r.stderr.ifBlank { "通道返回异常：${head.take(200)}" })
        }
        val payload = head.substring(STAT_PREFIX.length)

        if (payload == "N") return ReadResult.NotFound(path)
        if (payload == "D") return ReadResult.IsDirectory(path)
        if (payload.startsWith("T")) {
            val sz = payload.removePrefix("T").trim().toLongOrNull() ?: 0L
            return ReadResult.TooLarge(path, sz, MAX_OUTPUT_BYTES.toLong())
        }
        if (payload.startsWith("F")) {
            val parts = payload.removePrefix("F").trim().split(Regex("\\s+"))
            val sz = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            val mt = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            return ReadResult.Ok(rest, sz, mt * 1000L)
        }
        return ReadResult.Failed(path, "通道返回无法解析：${head.take(200)}")
    }

    // ══════════════════════════════════════════════════════════════
    //  写
    // ══════════════════════════════════════════════════════════════

    /**
     * 写文件（覆盖）。
     *
     * 与 Android 侧的 [AtomicFile] 同语义：先写同目录临时文件再 `mv` 覆盖，
     * 保证观察者要么看到完整旧内容、要么看到完整新内容。
     *
     * ⚠️ 临时文件名由 **Kotlin 侧**生成并固定（不能用 shell 的 `$$`）——
     * 分块写会发多条命令，每条都是**新的 shell 进程**，`$$` 不同，
     * 后面的块会写进另一个临时文件。
     *
     * @return 成功时带写入后的字节数与 mtime（省掉调用方再 stat 一次）
     */
    suspend fun write(path: String, content: String): WriteResult {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_WRITE_BYTES) {
            return WriteResult.TooLarge(path, bytes.size.toLong(), MAX_WRITE_BYTES)
        }

        val p = quote(path)
        // token 只要「同一次写入的多块共享、且不同次写入不撞」即可 ——
        // 纳秒时间戳足够（两次写入不可能在同一纳秒发起）。
        val token = "ccm" + System.nanoTime().toString(36)
        val tmpVar = "t=\"\$d/.$token.tmp\"; "

        // 每条命令都自包含（重算 d）—— 分块之间是独立 shell，不能依赖上一次的变量。
        val head = "p=$p; d=\$(dirname \"\$p\"); " +
            "mkdir -p \"\$d\" 2>/dev/null || { echo '${ERR_PREFIX}mkdir'; exit 11; }; " +
            tmpVar +
            // 记住原来的可执行位：mv 覆盖会换成临时文件的权限（644），脚本会失去 +x
            "x=0; [ -x \"\$p\" ] && x=1; "
        val tail = "mv -f \"\$t\" \"\$p\" || { rm -f \"\$t\"; echo '${ERR_PREFIX}mv'; exit 13; }; " +
            "[ \"\$x\" = 1 ] && chmod +x \"\$p\" 2>/dev/null; " +
            "echo \"${OK_PREFIX}\$(wc -c < \"\$p\" 2>/dev/null | tr -d ' ')" +
            ":\$(stat -c '%Y' \"\$p\" 2>/dev/null || echo 0)\""

        val chunks = splitChunks(bytes)
        chunks.forEachIndexed { i, b64 ->
            val redir = if (i == 0) ">" else ">>"
            val isLast = i == chunks.size - 1
            val cmd = head +
                "printf '%s' ${quote(b64)} | base64 -d $redir \"\$t\" " +
                "|| { rm -f \"\$t\"; echo '${ERR_PREFIX}b64'; exit 12; }; " +
                if (isLast) tail else ""

            val r = run(cmd, TIMEOUT_WRITE)

            // ⚠️ 只有最后一块才会打印 OK 标记（前面几块只落盘、不 mv）——
            // 所以「检查 OK_PREFIX」这个判断**只能对最后一块做**，
            // 否则第 1 块必然报「写入失败」（它本来就不该有标记）。
            if (isLast) {
                if (r.timedOut || r.exitCode != 0 || !r.stdout.contains(OK_PREFIX)) {
                    cleanupTmp(path, token)
                    return WriteResult.Failed(
                        path,
                        "写入失败（第 ${i + 1}/${chunks.size} 块）：${describe(r)}",
                    )
                }
                val payload = r.stdout.substringAfter(OK_PREFIX).trim()
                val seg = payload.split(":")
                val written = seg.getOrNull(0)?.toLongOrNull() ?: bytes.size.toLong()
                val mt = seg.getOrNull(1)?.toLongOrNull() ?: 0L
                return WriteResult.Ok(path, written, mt * 1000L)
            } else {
                if (r.timedOut || r.exitCode != 0) {
                    cleanupTmp(path, token)
                    return WriteResult.Failed(
                        path,
                        "第 ${i + 1}/${chunks.size} 块写入失败：${describe(r)}",
                    )
                }
            }
        }
        // chunks 至少有一个元素（空内容也有一块空 base64），走不到这里
        return WriteResult.Failed(path, "写入流程异常：没有可写的块")
    }

    private suspend fun cleanupTmp(path: String, token: String) {
        run(
            "p=${quote(path)}; d=\$(dirname \"\$p\"); rm -f \"\$d/.$token.tmp\" 2>/dev/null; true",
            TIMEOUT_PROBE,
        )
    }

    /** 空内容也返回一块（空 base64），否则 `>` 重定向根本不会执行、文件建不出来。 */
    private fun splitChunks(bytes: ByteArray): List<String> {
        if (bytes.isEmpty()) return listOf("")
        val out = mutableListOf<String>()
        var off = 0
        while (off < bytes.size) {
            val end = minOf(off + CHUNK_BYTES, bytes.size)
            out += java.util.Base64.getEncoder().encodeToString(bytes.copyOfRange(off, end))
            off = end
        }
        return out
    }

    // ══════════════════════════════════════════════════════════════
    //  删除
    // ══════════════════════════════════════════════════════════════

    suspend fun remove(path: String): Boolean {
        val r = run("rm -f ${quote(path)}", TIMEOUT_PROBE)
        return r.exitCode == 0 && !r.timedOut
    }

    /**
     * 原子重命名（`mv -f`）。
     *
     * ⚠️ **只有当 [from] 与 [to] 在同一个文件系统上时才是原子的**。
     * 跨文件系统时 `mv` 退化成「复制 + 删除」，中途失败会留下半个文件。
     * 调用方（[com.ccm.app.tools.dev.DevTools] 的两阶段提交）保证两者同目录。
     *
     * 这是 SafeRename 两阶段提交的 **commit 原语** —— 同目录 rename 在
     * Linux 上是原子的系统调用，不会因为磁盘满而半途而废，所以
     * 「prepare 全部成功后逐个 commit」能把失败窗口压到最小。
     */
    suspend fun rename(from: String, to: String): Boolean {
        val cmd = "mv -f ${quote(from)} ${quote(to)} 2>/dev/null"
        val r = run(cmd, TIMEOUT_WRITE)
        return !r.timedOut && r.exitCode == 0
    }

    // ══════════════════════════════════════════════════════════════
    //  列文件（Glob / 搜索的底座）
    // ══════════════════════════════════════════════════════════════

    /**
     * 列出 [root] 下的所有**普通文件**（递归）。
     *
     * ⚠️ `find` 的输出路径是**以 root 的字面形式为前缀**的：
     * `find .` → `./a.txt`；`find sub` → `sub/a.txt`。
     * 调用方要算「相对 root 的路径」时用 [relativeTo]。
     *
     * @param maxFiles 最多回传多少条路径。
     *   ⚠️ 这个值同时是**输出体积的闸门** —— 路径平均 80 字节，
     *   20000 条就是 1.6MB，会撞 Binder 事务限制（结果收不到，表现为超时）。
     *   需要大目录时靠 [excludeDirs] 砍掉噪音，而不是调大这个值。
     * @param excludeDirs 要跳过的目录**名**（如 `node_modules`）。
     *   走 `find -prune`，在被遍历到时就整棵剪掉 —— 而不是列出来再过滤，
     *   否则 node_modules 会先把 maxFiles 名额占满。
     */
    suspend fun listFiles(
        root: String,
        maxFiles: Int = 50_000,
        excludeDirs: Set<String> = emptySet(),
    ): ListResult {
        val r = quote(root)
        // ⚠️ `-mindepth 1` **不是可选的**，是修 bug 的关键：
        // `find .` 的起始点就是 `.` 本身，它会匹配 `-name '.*'` → 整棵树
        // 被 `-prune` 剪掉，**输出为空**。而调用方默认传的就是 `"."`
        // （模型写 `path: "."` 或省略），于是「列目录」永远返回空列表，
        // 表现为「项目里一个文件都没有」—— 静默、且看起来很像真的。
        // 实测确认（Termux 的 GNU findutils）。
        //
        // ⚠️ 隐藏项（`.*`）**始终**剪掉 —— 与本地 [FileWalker] 的
        // `name.startsWith('.')` 保持一致。不剪的话同一个项目在两种环境下
        // 会列出不同的文件（本地看不到 `.env`、通道看得到），模型会困惑。
        //
        // 表达式结构：`\( <排除项> \) -prune -o -type f -print`
        // —— 括号是 find 的表达式分组，必须转义；`-prune` 命中即不下降子树。
        // 对隐藏**文件**（非目录）`-prune` 无实际作用，但整个表达式仍为真，
        // 所以它同样不会走到 `-print` 分支 —— 正是我们要的。
        val prune = buildString {
            append("\\( -name '.*'")
            excludeDirs.forEach { d -> append(" -o -name ").append(quote(d)) }
            append(" \\) -prune -o ")
        }
        val cmd = "r=$r; " +
            "if [ -f \"\$r\" ]; then printf '%s\\n' \"\$r\"; exit 0; fi; " +
            "if [ ! -d \"\$r\" ]; then echo '${ERR_PREFIX}nodir'; exit 4; fi; " +
            "find \"\$r\" -mindepth 1 ${prune}-type f -print 2>/dev/null | head -n ${maxFiles + 1} " +
            "| head -c ${MAX_OUTPUT_BYTES + 1}"

        val res = run(cmd, TIMEOUT_SEARCH)
        if (res.exitCode == 4 || res.stdout.contains("${ERR_PREFIX}nodir")) {
            return ListResult(emptyList(), error = "路径不存在或不是目录：$root")
        }
        if (res.timedOut) return ListResult(emptyList(), error = "列目录超时：$root")

        // ⚠️ `head -c` 的截断判据：正常输出**每条路径都以换行结尾**（含最后一条），
        // 所以「末尾没有换行」= 被字节数切断了。丢掉最后那条不完整的路径 ——
        // 留着它会让调用方拿着一个残缺路径去 read，报出莫名其妙的「文件不存在」。
        //
        // 这道保护是必须的：`maxFiles` 只挡条数，不挡体积。路径平均 80 字节时
        // 50000 条就是 4MB，远超 Binder 事务限制 —— 结果是**什么都收不到**
        // （表现为超时），而不是「收到前 N 条」。
        var out = res.stdout
        var byteTruncated = false
        if (out.isNotEmpty() && !out.endsWith("\n")) {
            byteTruncated = true
            val nl = out.lastIndexOf('\n')
            out = if (nl >= 0) out.substring(0, nl + 1) else ""
        }

        val all = out.split("\n").filter { it.isNotEmpty() }
        return when {
            all.size > maxFiles -> ListResult(all.take(maxFiles), truncated = true)
            byteTruncated -> ListResult(all, truncated = true)
            else -> ListResult(all)
        }
    }

    /** 把 `find` 输出的路径换算成「相对 root」的形式（glob 匹配要用）。 */
    fun relativeTo(file: String, root: String): String {
        val prefix = if (root.endsWith("/")) root else "$root/"
        return when {
            file == root -> file
            file.startsWith(prefix) -> file.substring(prefix.length)
            file.startsWith("./") -> file.substring(2)
            else -> file
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  批量读（RepoMap / Symbols 的底座）
    // ══════════════════════════════════════════════════════════════

    /**
     * 批量读多个文件 —— **一次 Binder 往返读一整批**。
     *
     * ══════════════════════════════════════════════════════════════
     *  为什么需要它
     * ══════════════════════════════════════════════════════════════
     *
     * RepoMap / Symbols 要遍历整个仓库、读**每一个**源码文件。若用 [read]
     * 逐个读，N 个文件就是 N 次 Intent 往返（每次 50~200ms）—— 一个 300 文件
     * 的项目要 **15~60 秒**，用户会以为卡死了。批量读把往返数降到 N/60。
     *
     * ══════════════════════════════════════════════════════════════
     *  协议（为什么这么设计）
     * ══════════════════════════════════════════════════════════════
     *
     * 每个文件输出**两行**：
     * ```
     * CCM_B:F <size> <mtime> <路径的base64>
     * CCM_C:<内容的base64>
     * ```
     * 跳过/缺失的文件只输出一行（没有内容行）：
     * ```
     * CCM_B:S <路径的base64>     ← 超过 MAX_BATCH_FILE_BYTES
     * CCM_B:N <路径的base64>     ← 不是普通文件
     * ```
     *
     * 三点设计理由：
     *  1. **内容走 base64**：文件内容可能含任意字节（包括换行、`CCM_B:` 字样）。
     *     base64 的字符集 `A-Za-z0-9+/=` **不含冒号**，所以内容行永远不可能
     *     被误认成标记行 —— 解析不需要长度前缀或转义，这是刻意的。
     *  2. **路径也走 base64**：路径可能含空格、引号、非 ASCII。虽然 [quote]
     *     能保证 shell 侧安全，但回传时按空格 split 会切错 —— base64 一劳永逸。
     *  3. **先 stat 再读**：一条命令里同时拿到 size/mtime（供 [FileVersionTracker]
     *     的并发保护用），不必为每个文件再发一次 stat。
     *
     * @param paths 要读的路径（顺序会被保留）
     * @param maxFiles 最多读几个（超出部分丢弃并置 `truncated`）
     */
    suspend fun readBatch(
        paths: List<String>,
        maxFiles: Int = 50_000,
    ): BatchReadResult {
        if (paths.isEmpty()) return BatchReadResult()
        val wanted = if (paths.size > maxFiles) paths.take(maxFiles) else paths
        var truncated = wanted.size < paths.size

        val files = mutableListOf<BatchFile>()
        val skipped = mutableListOf<String>()
        var error: String? = null

        var i = 0
        while (i < wanted.size) {
            // 分批依据是**命令长度**，不是文件个数 —— 单条命令是一个 argv 元素，
            // 受 MAX_ARG_STRLEN（128KB）约束。留足余量取 8KB 装路径。
            val batch = mutableListOf<String>()
            var chars = 0
            while (i < wanted.size && batch.size < MAX_BATCH_FILES) {
                val p = wanted[i]
                val cost = p.length + 3 // 引号 + 空格
                if (batch.isNotEmpty() && chars + cost > 8_000) break
                batch += p
                chars += cost
                i++
            }

            val r = readBatchOnce(batch)
            files += r.files
            skipped += r.skipped
            if (r.error != null) {
                // 一批失败就整体停下并上报 —— 继续读剩下的只会让用户等到超时，
                // 且「部分结果」比「明确失败」更容易误导（以为扫全了）。
                error = r.error
                break
            }
            // ⚠️ 批内可能因累计预算提前停下（只处理了前 k 个）。剩下的一定要
            // 接着读，否则静默漏文件 —— 对只读的 RepoMap 是「少几个符号」，
            // 对写操作的 SafeRename 就是「以为全改了、其实没改」。
            val done = r.processed
            if (done < batch.size) {
                i = i - batch.size + done
                if (done == 0) break // 防死循环：一个都没处理说明卡住了
            }
        }

        return BatchReadResult(files, skipped, truncated = truncated, error = error)
    }

    /** 读一批（调用方保证 [paths] 已按长度切好）。 */
    private suspend fun readBatchOnce(paths: List<String>): BatchReadResult {
        if (paths.isEmpty()) return BatchReadResult()
        val list = paths.joinToString(" ") { quote(it) }

        val cmd = buildString {
            // `base64 -w0` 是 GNU 的（不加会 76 字符换行，多行会把协议打乱）；
            // busybox 的 base64 本来就不换行、也不认 -w0。探测一次再定。
            append("B='base64 -w0'; printf x | base64 -w0 >/dev/null 2>&1 || B='base64'; ")
            // 累计预算：单文件上限挡不住「60 个中等文件加起来撑爆 Binder」。
            // 超了就发 T 标记并停止 —— 剩下的下一批再读（调用方会重新分批）。
            append("tot=0; ")
            append("for p in ").append(list).append("; do ")
            append("if [ ! -f \"\$p\" ]; then ")
            append("echo \"").append(BATCH_PREFIX).append("N \$(printf '%s' \"\$p\" | \$B)\"; continue; fi; ")
            append("sz=\$(wc -c < \"\$p\" 2>/dev/null | tr -d ' '); [ -z \"\$sz\" ] && sz=0; ")
            append("if [ \"\$sz\" -gt ").append(MAX_BATCH_FILE_BYTES).append(" ]; then ")
            append("echo \"").append(BATCH_PREFIX).append("S \$(printf '%s' \"\$p\" | \$B)\"; continue; fi; ")
            append("tot=\$((tot + sz)); ")
            append("if [ \"\$tot\" -gt ").append(MAX_BATCH_TOTAL_BYTES).append(" ]; then ")
            append("echo \"").append(BATCH_PREFIX).append("T\"; break; fi; ")
            append("mt=\$(stat -c '%Y' \"\$p\" 2>/dev/null || echo 0); ")
            append("echo \"").append(BATCH_PREFIX)
                .append("F \$sz \$mt \$(printf '%s' \"\$p\" | \$B)\"; ")
            // 内容行：即使 base64 失败也占一行（`CCM_C:` 空内容），
            // 否则解析器会把下一个文件的标记行当成这一行的内容，全盘错位。
            append("c=\$(\$B \"\$p\" 2>/dev/null) || c=''; ")
            append("echo \"").append(CONTENT_PREFIX).append("\$c\"; ")
            append("done")
        }

        val r = run(cmd, TIMEOUT_SEARCH)
        if (r.timedOut) return BatchReadResult(error = "批量读超时（${TIMEOUT_SEARCH}ms，${paths.size} 个文件）")
        if (r.stdout.isEmpty()) return BatchReadResult(error = describe(r))

        val lines = r.stdout.split("\n")
        val files = mutableListOf<BatchFile>()
        val skipped = mutableListOf<String>()

        var idx = 0
        var processed = 0
        while (idx < lines.size) {
            val line = lines[idx]
            if (!line.startsWith(BATCH_PREFIX)) {
                idx++
                continue
            }
            val body = line.substring(BATCH_PREFIX.length)

            when {
                // 累计预算用尽：本批到此为止，剩下的交给下一批。
                // 注意这里**不**置 truncated —— 那个字段是给「maxFiles 截断」
                // 用的；预算停下由 processed < batch.size 表达，调用方会续读。
                body.startsWith("T") -> break

                body.startsWith("N") -> {
                    decodePath(body.substring(1))?.let { skipped += it }
                    processed++
                    idx++
                }

                body.startsWith("S") -> {
                    decodePath(body.substring(1))?.let { skipped += it }
                    processed++
                    idx++
                }

                body.startsWith("F") -> {
                    // `F <size> <mtime> <pathB64>` —— 路径 base64 里没有空格，
                    // 所以按空格切三段是安全的（limit=3 让最后一段吃下剩余内容）。
                    val parts = body.substring(1).trim().split(" ", limit = 3)
                    val size = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                    val mtime = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                    val path = parts.getOrNull(2)?.let { decodePath(" $it") }

                    val contentLine = lines.getOrNull(idx + 1)
                    if (path == null || contentLine == null || !contentLine.startsWith(CONTENT_PREFIX)) {
                        // 内容行缺失 = 输出被截断（Binder 上限 / 命令被杀）。
                        // 不能继续解析：后面的行归属已经不可信了。
                        return BatchReadResult(
                            files, skipped, processed,
                            error = "批量读输出不完整（第 ${idx + 1} 行后缺少内容行）—— " +
                                "可能是单批体积超过 Binder 限制",
                        )
                    }
                    val text = decodeContent(contentLine.substring(CONTENT_PREFIX.length))
                    if (text == null) {
                        return BatchReadResult(
                            files, skipped, processed,
                            error = "批量读内容解码失败（第 ${idx + 2} 行）",
                        )
                    }
                    files += BatchFile(path, text, size, mtime * 1000L)
                    processed++
                    idx += 2
                }

                else -> idx++
            }
        }

        return BatchReadResult(files, skipped, processed)
    }

    /** 解析 `F`/`N`/`S` 行尾部的「空格 + 路径 base64」。解不出来返回 null。 */
    private fun decodePath(raw: String): String? {
        val b64 = raw.trim()
        if (b64.isEmpty()) return null
        return try {
            String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    /** 内容 base64 → 文本。空串是合法的（空文件）。 */
    private fun decodeContent(b64: String): String? = try {
        String(java.util.Base64.getDecoder().decode(b64.trim()), Charsets.UTF_8)
    } catch (_: Throwable) {
        null
    }

    // ══════════════════════════════════════════════════════════════
    //  grep
    // ══════════════════════════════════════════════════════════════

    /**
     * 在 [root] 下搜索正则 [pattern]，返回 `路径:行号:内容`。
     *
     * 【为什么在 Termux 侧过滤而不是把文件读回来在 Kotlin 侧搜】
     * 输出有 Binder 大小限制（见类注释），把整个目录的内容传回来根本不可行。
     * 所以搜索**必须**在 Termux 侧完成，只回传命中的行。
     *
     * 【为什么用 `grep -r` 而不是 `find | xargs grep`】
     * 后者要依赖 `xargs -r -d` 这些 GNU 扩展，且管道中间任何一环失败都会
     * 静默变成「没匹配」。`-r` 是 GNU/busybox 都支持的基础能力，一条命令、
     * 一个退出码，失败模式干净。
     *
     * 【正则方言】优先 `grep -P`（PCRE，与 Kotlin 的 Regex 最接近：
     * `\d` `\w` `\s` lookahead 都支持）。老版本 grep 没有 -P 时退回 `-E`，
     * 此时 `\d` 之类的扩展会被当成字面量 —— 属于已知降级。
     */
    suspend fun grep(
        pattern: String,
        root: String,
        maxMatches: Int = 200,
        ignoreCase: Boolean = false,
    ): GrepResult {
        val r = quote(root)
        val pat = quote(pattern)
        // 单文件不加 -r（部分 grep 对 `-r 文件` 的行为不一致）；目录必须加 -r
        val fileFlags = if (ignoreCase) "-inH" else "-nH"
        val dirFlags = if (ignoreCase) "-rinH" else "-rnH"

        // 两处能力探测都在**同一条命令**里做完，避免多次往返：
        //   G = -P（PCRE，与 Kotlin Regex 最接近）还是 -E（退化，\d 会变字面量）
        //   E = 是否支持 --exclude-dir（跳过 .git / node_modules 这类噪音）
        // 探测方式都是「真跑一次看退出码」，不用 `grep --version`（它不报选项支持）
        val cmd = "r=$r; " +
            "G='-P'; echo x | grep -P x >/dev/null 2>&1 || G='-E'; " +
            "E=''; grep --exclude-dir=.ccmprobe x /dev/null >/dev/null 2>&1 && " +
            "E='--exclude-dir=.git --exclude-dir=node_modules --exclude-dir=.gradle --exclude-dir=build'; " +
            "if [ -f \"\$r\" ]; then " +
            "grep \$G $fileFlags -- $pat \"\$r\" 2>/dev/null | head -n $maxMatches; " +
            "elif [ -d \"\$r\" ]; then " +
            "grep \$G $dirFlags \$E -- $pat \"\$r\" 2>/dev/null | head -n $maxMatches; " +
            "else echo '${ERR_PREFIX}nodir'; exit 4; fi"

        val res = run(cmd, TIMEOUT_SEARCH)
        if (res.exitCode == 4 || res.stdout.contains("${ERR_PREFIX}nodir")) {
            return GrepResult(emptyList(), error = "路径不存在：$root")
        }
        if (res.timedOut) return GrepResult(emptyList(), error = "搜索超时：$root")

        val lineRe = Regex("^(.+?):(\\d+):(.*)$")
        val hits = mutableListOf<GrepHit>()
        for (line in res.stdout.split("\n")) {
            if (line.isEmpty()) continue
            val m = lineRe.find(line) ?: continue
            hits += GrepHit(
                path = m.groupValues[1],
                line = m.groupValues[2].toIntOrNull() ?: 0,
                text = m.groupValues[3],
            )
        }
        return GrepResult(hits)
    }
}
