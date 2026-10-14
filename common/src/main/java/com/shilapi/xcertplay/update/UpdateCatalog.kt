package com.shilapi.xcertplay.update

import org.json.JSONArray
import org.json.JSONObject

/** The published release that a newer build can be downloaded from. */
internal data class UpdateRelease(
    val tagName: String,
    val apkName: String,
    val apkUrl: String,
    val checksumsUrl: String,
)

internal object UpdateCatalog {
    internal const val CHECKSUMS_FILE = "SHA256SUMS.txt"

    /**
     * ★ v100 B561: 发布件命名前缀。
     *
     * 本仓库的 release 资产统一叫 `OushangCarPlay-B561-vNNN-*.apk`；
     * 上游 CN 用 `DiPlay-cn-<tag>.apk`，两个都认（见 [parse]）。
     */
    private const val APK_NAME_PREFIX = "OushangCarPlay"

    /**
     * tag 格式：接受上游的 `v1.2.3-cn.4`，也接受本仓库的纯 `vNNN`
     * （B561 定制版按迭代序号发版，不带上游版本号）。
     */
    private val TAG_PATTERN =
        Regex("^v(\\d+\\.\\d+\\.\\d+(?:\\.\\d+)?-cn\\.\\d+|\\d+)$")

    /** Returns the first published release that carries an APK and its checksums, or null. */
    internal fun parse(json: String): UpdateRelease? {
        val releases = if (json.trimStart().startsWith("[")) JSONArray(json) else JSONArray().put(JSONObject(json))
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft")) continue
            val assets = release.optJSONArray("assets") ?: continue
            val tag = release.optString("tag_name")
            if (!TAG_PATTERN.matches(tag)) continue
            // ★ v100 B561: 资产名放宽 ——
            // 原规则要求 `DiPlay-cn-<tag>.apk`（上游 CN 命名），但本仓库的发布件
            // 叫 `OushangCarPlay-B561-vNNN-*.apk`。所以改成**前缀匹配 + 单一 APK**：
            // 名字以 `OushangCarPlay` 开头（或等于历史 CN 名）且以 .apk 结尾即认。
            // 这样以后发版不必为了迎合解析器去改文件名。
            var apkName = ""
            var apkUrl = ""
            var legacyChecksumsUrl = ""
            val checksumsByName = mutableMapOf<String, String>()
            val apkCandidates = mutableListOf<Pair<String, String>>()
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (url.isEmpty()) continue
                if (name.startsWith(APK_NAME_PREFIX) && name.endsWith(".apk", ignoreCase = true)) {
                    apkCandidates += name to url
                }
                if (name.equals(CHECKSUMS_FILE, ignoreCase = true)) legacyChecksumsUrl = url
                if (name.endsWith(".apk.sha256", ignoreCase = true)) checksumsByName[name] = url
            }
            // 多个 APK 时取名字最短的（`OushangCarPlay-B561-v101.apk` 这类），
            // 避免误取带说明后缀的大文件。
            for ((name, url) in apkCandidates.sortedBy { it.first.length }) {
                apkName = name
                apkUrl = url
                break
            }
            // 校验和：优先 `<apk名>.sha256`，其次任意 `.apk.sha256`，最后老式汇总文件。
            val checksumsUrl = checksumsByName["$apkName.sha256"]
                ?: checksumsByName.values.firstOrNull()
                ?: legacyChecksumsUrl
            if (apkUrl.isEmpty() || checksumsUrl.isEmpty()) continue
            return UpdateRelease(tag, apkName, apkUrl, checksumsUrl)
        }
        return null
    }
}
