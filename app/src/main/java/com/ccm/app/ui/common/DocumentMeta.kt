package com.ccm.app.ui.common

/**
 * 文档格式推断 —— 从 `web/src/components/documentCardMeta.js` 逐条移植。
 *
 * 这是**纯逻辑**，与 UI 无关，但因为只被 [DocumentCard] 使用，放在 ui/ 包里。
 *
 * ## 推断优先级（三层）
 * 1. `document.format` 字段（若不在「泛化格式」集合里）
 * 2. 文件扩展名 → 格式
 * 3. 内容嗅探（看开头像不像 JSON/YAML/Markdown/HTML/XML）
 * 4. 兜底 `"text"`
 *
 * ## 与 Web 的对应关系
 * | JS 函数 | Kotlin |
 * |---|---|
 * | `resolveDocumentFormat` | [resolveDocumentFormat] |
 * | `getDocumentKindLabel` | [getDocumentKindLabel] |
 * | `getDocumentFormatBadge` | [getDocumentFormatBadge] |
 * | `getDocumentSubtitle` | [getDocumentSubtitle] |
 */
object DocumentMeta {

    /** 视为「没有格式信息」的值 —— 需要继续往下推断 */
    private val GENERIC_FORMATS = setOf(
        "", "text", "plain", "plaintext", "document", "file",
    )

    /** 扩展名 → 内部格式名 */
    private val EXTENSION_TO_FORMAT = mapOf(
        "md" to "markdown", "markdown" to "markdown", "txt" to "text",
        "html" to "html", "htm" to "html", "css" to "css", "scss" to "scss",
        "js" to "javascript", "cjs" to "javascript", "mjs" to "javascript",
        "jsx" to "jsx", "ts" to "typescript", "tsx" to "tsx",
        "json" to "json", "yaml" to "yaml", "yml" to "yaml", "xml" to "xml",
        "csv" to "csv", "sql" to "sql", "py" to "python", "rb" to "ruby",
        "php" to "php", "go" to "go", "rs" to "rust", "java" to "java",
        "c" to "c", "cpp" to "cpp", "cc" to "cpp", "cxx" to "cpp",
        "cs" to "csharp", "sh" to "shell", "bash" to "shell", "zsh" to "shell",
        "ps1" to "powershell", "vue" to "vue", "svelte" to "svelte",
        "toml" to "toml", "ini" to "ini", "dockerfile" to "dockerfile",
        "docx" to "docx", "pdf" to "pdf", "pptx" to "pptx", "xlsx" to "xlsx",
    )

    /** 内部格式名 → 展示用徽章文字（大写） */
    private val FORMAT_DISPLAY = mapOf(
        "markdown" to "MARKDOWN", "text" to "TEXT", "html" to "HTML",
        "css" to "CSS", "scss" to "SCSS", "javascript" to "JAVASCRIPT",
        "jsx" to "REACT JSX", "typescript" to "TYPESCRIPT", "tsx" to "REACT TSX",
        "json" to "JSON", "yaml" to "YAML", "xml" to "XML", "csv" to "CSV",
        "sql" to "SQL", "python" to "PYTHON", "ruby" to "RUBY", "php" to "PHP",
        "go" to "GO", "rust" to "RUST", "java" to "JAVA", "c" to "C",
        "cpp" to "C++", "csharp" to "C#", "shell" to "SHELL",
        "powershell" to "POWERSHELL", "vue" to "VUE", "svelte" to "SVELTE",
        "toml" to "TOML", "ini" to "INI", "dockerfile" to "DOCKERFILE",
        "docx" to "DOCX", "pdf" to "PDF", "pptx" to "PPTX", "xlsx" to "XLSX",
    )

    private val DOCUMENT_KINDS = setOf("markdown", "text", "docx", "pdf")
    private val DATA_KINDS = setOf("json", "yaml", "xml", "csv", "xlsx", "toml", "ini")
    private val ARTIFACT_KINDS = setOf("html", "css", "scss", "jsx", "tsx", "vue", "svelte")

    private fun extensionOf(filename: String): String {
        if (filename.isEmpty()) return ""
        val n = filename.trim().lowercase()
        if (n == "dockerfile") return "dockerfile"
        return Regex("\\.([a-z0-9]+)$").find(n)?.groupValues?.get(1) ?: ""
    }

    private fun looksLikeJson(content: String): Boolean {
        if (content.isEmpty()) return false
        val t = content.trim()
        if (t.isEmpty() || (!t.startsWith("{") && !t.startsWith("["))) return false
        return try {
            org.json.JSONTokener(t).nextValue()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 从内容开头嗅探格式（对应 JS 的 `inferFormatFromContent`） */
    private fun inferFormatFromContent(content: String): String {
        val trimmed = content.trimStart()
        if (trimmed.isEmpty()) return ""
        val lower = trimmed.take(240).lowercase()

        if (lower.startsWith("<!doctype html") || lower.startsWith("<html")) return "html"
        if (lower.startsWith("<?xml")) return "xml"
        if (looksLikeJson(trimmed)) return "json"
        // 对应 JS: /^---\s*$[\s\S]*?^---\s*$/m  或  /^([A-Za-z0-9_-]+):\s.+$/m
        if (Regex("(?m)^---\\s*$").containsMatchIn(trimmed) ||
            Regex("(?m)^[A-Za-z0-9_-]+:\\s.+$").containsMatchIn(trimmed)
        ) return "yaml"
        // 对应 JS: /^#\s+\S/m  或  /^[-*]\s+\S/m  或  [text](url)
        if (Regex("(?m)^#\\s+\\S").containsMatchIn(trimmed) ||
            Regex("(?m)^[-*]\\s+\\S").containsMatchIn(trimmed) ||
            Regex("\\[[^\\]]+\\]\\([^)]+\\)").containsMatchIn(trimmed)
        ) return "markdown"
        return ""
    }

    private fun normalizeGeneratedFormat(filename: String, content: String): String {
        val ext = extensionOf(filename)
        return EXTENSION_TO_FORMAT[ext] ?: inferFormatFromContent(content).ifEmpty { "text" }
    }

    /**
     * 解析文档格式 —— 对应 JS 的 `resolveDocumentFormat`。
     *
     * @param format   文档声明的格式（可能为空或泛化值）
     * @param filename 文件名（用于取扩展名）
     * @param content  内容（用于嗅探）
     */
    fun resolveDocumentFormat(format: String, filename: String, content: String): String {
        val raw = format.trim().lowercase()
        if (raw.isNotEmpty() && raw !in GENERIC_FORMATS) {
            return EXTENSION_TO_FORMAT[raw] ?: raw
        }
        return normalizeGeneratedFormat(filename, content)
    }

    /** 文档大类 —— `Data` / `Artifact` / `Document` / `Code` */
    fun getDocumentKindLabel(format: String): String = when (format) {
        in DATA_KINDS -> "Data"
        in ARTIFACT_KINDS -> "Artifact"
        in DOCUMENT_KINDS -> "Document"
        else -> "Code"
    }

    /** 格式徽章文字（如 `MARKDOWN` / `REACT TSX`） */
    fun getDocumentFormatBadge(format: String): String =
        FORMAT_DISPLAY[format] ?: format.uppercase()

    /** 副标题 —— `Document · MARKDOWN` 这样的组合 */
    fun getDocumentSubtitle(format: String): String =
        "${getDocumentKindLabel(format)} · ${getDocumentFormatBadge(format)}"

    /**
     * 二进制格式判定 —— 这些格式的下载走 `/api/documents/{id}/raw`，
     * 其余（文本类）直接用内容生成 Blob 下载。
     *
     * 对应 JS `DocumentCard.tsx` 的 `handleDownload` 里的 `binaryFormats`。
     */
    val BINARY_FORMATS = setOf("docx", "pptx", "xlsx", "pdf")

    /** 文本类格式下载时的扩展名映射（对应 JS 的 `langToExt`） */
    private val LANG_TO_EXT = mapOf(
        "markdown" to "md", "python" to "py", "javascript" to "js",
        "typescript" to "ts", "java" to "java", "c" to "c", "cpp" to "cpp",
        "csharp" to "cs", "go" to "go", "rust" to "rs", "ruby" to "rb",
        "php" to "php", "swift" to "swift", "kotlin" to "kt", "scala" to "scala",
        "html" to "html", "css" to "css", "scss" to "scss", "sql" to "sql",
        "shell" to "sh", "bash" to "sh", "powershell" to "ps1",
        "yaml" to "yml", "json" to "json", "xml" to "xml", "toml" to "toml",
        "ini" to "ini", "dockerfile" to "Dockerfile", "r" to "r",
        "matlab" to "m", "lua" to "lua", "perl" to "pl", "dart" to "dart",
        "vue" to "vue", "svelte" to "svelte",
    )

    /** 取下载扩展名 */
    fun downloadExtension(format: String): String = LANG_TO_EXT[format] ?: format

    /** 二进制格式的下载后缀（带点） */
    private val BINARY_EXT = mapOf(
        "docx" to ".docx", "pptx" to ".pptx", "xlsx" to ".xlsx", "pdf" to ".pdf",
    )

    /** 取二进制下载后缀 */
    fun binaryExtension(format: String): String = BINARY_EXT[format] ?: ".bin"
}
