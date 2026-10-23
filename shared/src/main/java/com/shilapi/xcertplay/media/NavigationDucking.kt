package com.shilapi.xcertplay.media

/**
 * 导航播报时**压低媒体声音**的判定与增益换算（纯逻辑，可单测）。
 *
 * ## 与已有开关的区别
 *
 * 项目里已有一个开关 [NavigationAudioFocus]（「导航播报是否申请音频焦点」）：
 * 那个是**把决定权交给系统** —— 申请焦点后由车机音频策略决定是否 duck 或暂停其他音频。
 * 车机怎么做我们控制不了（不同 ROM 行为差别很大）。
 *
 * 本类对应的开关是**我们自己压低媒体**：媒体轨道上直接调 `AudioTrack.setStereoVolume`，
 * 不碰 Android 的系统流音量，所以**在所有车机上行为一致**。
 *
 * ## 为什么需要"结束判定"
 *
 * 导航播报是**一段一段**的音频流：播报 → 停几秒 → 再播报。
 * 如果只在「有 PCM 写入时压低」，会在两段播报之间忽大忽小，听感很差。
 * 所以用**保持时间**：最后一次收到导航 PCM 后再维持一小段时间才恢复音量。
 *
 * ## 时间单位
 *
 * 用 [android.os.SystemClock.elapsedRealtime] 的毫秒数（单调时钟，不受系统时间调整影响）。
 */
object NavigationDucking {

    /** 压低后的媒体音量比例（0..1）。0.35 ≈ 降 9 dB，听得清音乐但不抢话。 */
    const val DUCKED_VOLUME = 0.35f

    /**
     * 导航播报结束后**继续压低**多久（毫秒）。
     *
     * 3 秒：两段播报之间的典型间隔短于它，所以听感是「导航在说话时音乐一直是小的」，
     * 而不是一段段地忽大忽小。播报真正结束后 3 秒音乐恢复。
     */
    const val HOLD_MILLIS = 3_000L

    /**
     * 判定结果。
     *
     * @param ducked true = 媒体应当被压低。
     */
    fun shouldDuck(navigationActive: Boolean, lastNavigationAtMillis: Long, nowMillis: Long): Boolean {
        if (navigationActive) return true
        val last = lastNavigationAtMillis
        // 从没收到过导航音频（last == 0）时不该压低。
        if (last <= 0L) return false
        return nowMillis - last < HOLD_MILLIS
    }

    /**
     * 媒体音量：压低时 [DUCKED_VOLUME]，否则 1.0。
     *
     * 传入的 [fullVolume] 是媒体原本的音量（正常是 1.0），
     * 这样压低是**相对**的，不会把用户已经调低的音量再放大。
     */
    fun mediaVolume(ducked: Boolean, fullVolume: Float): Float =
        if (ducked) fullVolume * DUCKED_VOLUME else fullVolume

    /**
     * 这段 PCM 里有没有**实际的声音**。
     *
     * ## 为什么不能只看「有没有数据」
     *
     * 导航的音频流是**长驻**的：整个导航会话期间 AirPlay 一直在发包，
     * 播报间歇期发的是**静音包**。所以「收到 PCM」不等于「在播报」——
     * 按包判会得出「导航流一开就一直压低媒体」，播报早就结束了音乐还是小的。
     *
     * ## 判定方法
     *
     * 看有没有**非零采样**。16bit PCM 的静音就是全 0，
     * 有声音必然有非零字节。
     *
     * 这里是**逐字节全扫**而不是抽样 —— 抽样会漏掉窄脉冲
     * （16bit 采样下一个非零样本只占 2 字节，步长 16 抽样很容易跳过），
     * 漏判的后果是「该压低时没压低」。一个 20ms 帧约 1764 字节，
     * 全扫的开销可以忽略。
     */
    fun hasVoice(data: ByteArray, offset: Int, length: Int): Boolean {
        if (length <= 0) return false
        val end = minOf(offset + length, data.size)
        var i = maxOf(offset, 0)
        while (i < end) {
            if (data[i].toInt() != 0) return true
            i++
        }
        return false
    }
}
