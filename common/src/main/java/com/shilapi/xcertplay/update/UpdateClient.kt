package com.shilapi.xcertplay.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection

internal object UpdateClient {
    /**
     * ★ v100 B561: 更新源指向本人的 GitHub 仓库（原为上游 serein-morii/DiPlay-CN +
     * Gitee oneeyear 镜像 —— 那些 release 里没有 B561 定制版，会把车机升级到
     * 别人的构建）。
     */
    internal const val GITHUB_RELEASES_URL =
        "https://api.github.com/repos/XiaoBai-Miao/OushangCarPlay-CN/releases?per_page=5"

    /**
     * 备用源：gh-proxy.org 前缀式镜像。
     *
     * 与 git 推送不同，**读**操作走代理是可行的（fetch 实测通过），所以这里
     * 拿它当国内网络下的备份通道。
     */
    internal const val GITHUB_PROXY_RELEASES_URL =
        "https://gh-proxy.org/$GITHUB_RELEASES_URL"

    /**
     * 保留旧常量名以免 [UpdateChannels] 与测试引用断裂 —— 值已指向本人仓库，
     * 走 GitHub 官方 API（api.github.com），不用 Gitee。
     */
    internal const val GITEE_RELEASES_URL = GITHUB_RELEASES_URL
    private const val CONNECT_TIMEOUT_MILLIS = 10_000
    private const val READ_TIMEOUT_MILLIS = 30_000
    private const val MAXIMUM_TEXT_BYTES = 4 * 1024 * 1024
    private const val BUFFER_BYTES = 64 * 1024

    internal fun fetchText(
        url: String,
        accept: String?,
        userAgent: String,
        openConnection: (URL) -> URLConnection = { it.openConnection() },
    ): String {
        val connection = openConnection(URL(url)) as? HttpURLConnection
            ?: throw IOException("Request to $url did not open an HTTP connection")
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.useCaches = false
            accept?.let { connection.setRequestProperty("Accept", it) }
            connection.setRequestProperty("User-Agent", userAgent)
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { readUtf8Limited(url, it) }.orEmpty()
            if (statusCode !in 200..299) throw IOException("Request to $url failed: $statusCode")
            return body
        } finally {
            connection.disconnect()
        }
    }

    internal fun download(url: String, destination: File, onProgress: (Long, Long?) -> Unit) {
        destination.parentFile?.mkdirs()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.useCaches = false
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) throw IOException("Download from $url failed: $statusCode")
            val total = connection.contentLengthLong.takeIf { it > 0 }
            var written = 0L
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                }
            }
            if (total != null && written != total) throw IOException("Incomplete download from $url")
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    private fun readUtf8Limited(url: String, stream: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAXIMUM_TEXT_BYTES) throw IOException("Response from $url is too large")
            output.write(buffer, 0, read)
        }
        return output.toString("UTF-8")
    }
}
