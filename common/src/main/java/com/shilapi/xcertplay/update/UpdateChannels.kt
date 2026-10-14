package com.shilapi.xcertplay.update

/** CN release sources. The first entry is the driver's choice; the others are fallbacks. */
internal object UpdateChannels {
    data class Source(val releasesUrl: String, val assetPrefix: String = "") {
        fun assetUrl(url: String): String = assetPrefix + url
    }

    private val gitee = Source(UpdateClient.GITEE_RELEASES_URL)
    private val github = Source(UpdateClient.GITHUB_RELEASES_URL)
    // v100 B561: 代理镜像改为 gh-proxy.org（用户指定）。资产 URL 也要过同一前缀，
    // 否则清单能取到、下载却打回原始 api.github.com。
    private val mirrorOne = Source(
        UpdateClient.GITHUB_PROXY_RELEASES_URL,
        "https://gh-proxy.org/",
    )
    private val mirrorTwo = Source(
        "https://gh-proxy.com/${UpdateClient.GITHUB_RELEASES_URL}",
        "https://gh-proxy.com/",
    )

    fun sources(choice: Int): List<Source> = when (choice) {
        0 -> listOf(github, gitee)
        2 -> listOf(mirrorOne, gitee, github)
        3 -> listOf(mirrorTwo, gitee, github)
        else -> listOf(gitee, github)
    }
}
