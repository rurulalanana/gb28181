import { ref } from 'vue'

/**
 * 流播放的「会话 + 自愈」统一封装。
 *
 * <h3>为什么需要它（这是真实踩过的坑）</h3>
 * 「画面未就绪 → 停会话 → 重新点播」这套逻辑此前在四个视图里各抄了一份：
 * 通道预览、电视墙、设备录像、分享页。抄的时候看着一样，但后续改动只落在其中一处，
 * 已经出现三种偏差：
 *
 * <ol>
 *   <li><b>只有设备录像页接了 {@code onStable}</b>，其余三处没有。
 *       {@code onStable} 是「稳定播放 10 秒后归还重试预算」的唯一机制，
 *       不接的话预算一旦用满（哪怕两次故障毫无关系）就<b>永不归还</b>，
 *       之后画面真出问题再也不会自动重建 —— 用户只能手动停止再点播。</li>
 *   <li>各处的「有没有可用地址」判断口径不同：有的用
 *       {@link import('@/player/resolvePlayUrls').hasPlayUrl}，
 *       有的写成 {@code !urls.wsFlv && !urls.webrtc}，
 *       后者会把「只有 HLS 可用」的通道误判成取不到地址。</li>
 *   <li>重试上限的复位时机不同：电视墙在「清空格子」时复位，
 *       单路预览在「开始播放」时复位，录像页在「用户主动点播」时复位。</li>
 * </ol>
 *
 * <h3>为什么重建必须「停会话 + 重新点播」</h3>
 * 只重连播放器没用：ZLM 等轨超时会把未就绪的视频轨<b>永久丢弃</b>
 * （{@code Track not ready for a long time, ignored: H264}），
 * 那个决定绑定在媒体源上，重连 FLV / WebRTC 连的还是同一个源。
 * 只有 {@code stream/stop} 拆掉会话、重新 INVITE 才会生成新源。
 *
 * <h3>为什么要有上限</h3>
 * 设备自己坏了（断电、编码异常）重试多少次都没用。电视墙尤其不能无限重建，
 * 否则一格异常就把整墙刷成不停 INVITE。上限取 2：够覆盖「偶发一次协商失败」，
 * 又不至于把设备打爆。
 *
 * @param {object} opts
 * @param {number} [opts.maxRetry=2] 单轮最多重建几次
 * @param {(key?: any) => any} [opts.stopSession] 停掉会话（异常会被吞掉，会话可能已被空闲巡检关闭）
 * @param {(key?: any) => any} [opts.replay] 重新点播并播放
 * @param {(key?: any) => void} [opts.onExhausted] 预算用完时的回调（一般用来提示用户）
 * @param {(used: number, max: number, key?: any) => void} [opts.onRebuilding] 开始重建时的回调（一般用来改状态文案）
 */
export function useStreamPlayback({
  maxRetry = 2,
  stopSession,
  replay,
  onExhausted,
  onRebuilding
} = {}) {
  /**
   * 各路的已用次数。单路视图（预览 / 录像 / 分享）传 {@code undefined} 当 key，
   * 电视墙传格子 id —— 不分开记的话，一格故障会把整墙的预算一起吃掉。
   */
  const used = new Map()
  /** 是否有某一路已耗尽预算（给界面做提示用）。 */
  const exhausted = ref(false)

  const keyOf = (key) => (key === undefined || key === null ? '__default__' : key)

  /**
   * 归还某一路的重试预算。
   *
   * <p><b>必须接在播放器的 {@code onStable} 上。</b>不接就等于预算用满后
   * 永久失效 —— 这正是本组件诞生时修掉的偏差之一。
   *
   * @param {any} [key] 路的标识；单路视图不传
   */
  function refund(key) {
    used.delete(keyOf(key))
    if (used.size === 0) {
      exhausted.value = false
    }
  }

  /**
   * 重置某一路（新一轮播放 / 用户主动点播 / 格子被腾空时调用）。
   *
   * <p>与 {@link refund} 的区别只是语义：这里表示「从头开始」，
   * 而 refund 表示「这一轮成功了，把预算还回去」。实现相同。
   */
  function reset(key) {
    refund(key)
  }

  /**
   * 画面未就绪时的自愈入口，接在播放器的 {@code onStall} 上。
   *
   * @param {any} [key] 路的标识；单路视图不传
   * @returns {Promise<boolean>} 有没有真的执行重建（false = 预算已用完）
   */
  async function handleStall(key) {
    const k = keyOf(key)
    const n = used.get(k) || 0
    if (n >= maxRetry) {
      exhausted.value = true
      onExhausted?.(key)
      return false
    }
    used.set(k, n + 1)
    onRebuilding?.(n + 1, maxRetry, key)
    try {
      await stopSession?.(key)
    } catch (e) {
      /*
       * 吞掉：会话可能已被空闲巡检关掉，或设备已 BYE。
       * 继续重新点播即可 —— 抛出去反而会让这次自愈半途而废。
       */
    }
    await replay?.(key)
    return true
  }

  /** 某一路已用次数（诊断用）。 */
  function usedCount(key) {
    return used.get(keyOf(key)) || 0
  }

  return { handleStall, refund, reset, exhausted, usedCount }
}
