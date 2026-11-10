import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { visionApi } from '@/api'

/**
 * AI 检测的开关与结果轮询。
 *
 * <h3>为什么用轮询而不是 WebSocket</h3>
 * 检测结果每秒更新一次（fps=1），轮询 1 秒一次的心跳开销远小于维护一条
 * 双向通道的复杂度。且 WebSocket 要处理断线重连、鉴权续期、多标签页
 * 重复订阅，收益不明显。
 *
 * <h3>为什么一个 composable 而不是写在页面里</h3>
 * 直播页已经 1100 多行。检测涉及「开关状态 + 定时器 + 过期判定 + 错误降噪」，
 * 塞进页面会让人不敢改。这里把状态和生命周期封起来，页面只拿
 * `toggle / boxesOf / isOn`。
 *
 * @param {object} options
 * @param {import('vue').Ref<number>} options.intervalMs 轮询间隔（默认 500ms）
 * @returns 检测状态与操作方法
 */
export function useVision(options = {}) {
  /*
   * 轮询间隔必须比后端出帧间隔短，否则「框的延迟」里有一半是轮询在等。
   *
   * 后端默认 fps=2（每 500ms 一帧），这里取 500ms：平均额外等待 250ms，
   * 最坏 500ms。之前是 1000ms，叠加 1fps 的 1000ms 出帧间隔，
   * 单帧结果最坏要 2 秒才上屏 —— 这就是「框和画面延迟挺大」的直接原因。
   *
   * 为什么不更短（比如 200ms）：请求频率翻 2.5 倍，而拿到的还是同一帧，
   * 只增加服务端压力。真要更低延迟得把结果改成 WebSocket 推送，
   * 那是另一个量级的改动，见 useVision 的类注释。
   *
   * <p><b>这个值必须和 vision.fps 匹配</b>：轮询比推理快，拿到的还是同一帧，
   * 白费请求；比推理慢，则最新一帧要等下一次轮询才上屏，白白多出一段延迟。
   * 所以 {@link refreshStatus} 会用后端返回的 fps 覆盖它（见 syncIntervalToBackend）。
   */
  let intervalMs = options.intervalMs || 250

  /** 后端总开关 + 模型可用性。false 时整块 UI 隐藏，而不是让人点了报错。 */
  const enabled = ref(false)
  const available = ref(false)
  /** 正在检测的通道键集合（设备编码/通道编码）。 */
  const runningKeys = ref(new Set())
  /** 通道键 → 最新检测结果。 */
  const results = reactive({})
  /** 通道键 → 是否已过期（连续多个周期没拿到新结果）。 */
  const stale = reactive({})
  /** 每个通道最后一次拿到结果的时间戳。 */
  const lastAt = reactive({})

  /** 当前正在轮询的通道键（只轮「上屏且已开启检测」的通道，避免无谓请求）。 */
  let watched = []
  let timer = null
  /** 开关请求进行中，防止连点。 */
  const pending = ref(new Set())

  /**
   * 过期阈值：连续 3 个周期没更新就认为断了。
   *
   * 为什么不用后端返回的 at 判断：设备推流会断，断了后端就不再有新结果，
   * 但接口照样返回上一次的值。前端不自己判过期，框会「冻」在画面上，
   * 用户会以为检测还在工作 —— 这比不显示更糟。
   *
   * <p>这里判的是「多久没拿到<b>新</b>结果」，即 at 没变。
   * 不能用「请求失败」判：请求成功但 at 不变（检测卡住）同样要算过期。
   *
   * <p>用函数而不是常量：intervalMs 会被后端 fps 覆盖，常量会锁死旧值。
   */
  const staleAfterMs = () => intervalMs * 3

  const isOn = (key) => !!key && runningKeys.value.has(key)

  const boxesOf = (key) => {
    const r = results[key]
    if (!r || stale[key]) return []
    return r.detections || []
  }

  const isStale = (key) => !!stale[key]

  const summaryOf = (key) => {
    const r = results[key]
    return r && r.counts ? r.counts : {}
  }

  /** 拉一次总览，同步「哪些通道在检测」。 */
  async function refreshStatus() {
    try {
      const s = await visionApi.status()
      enabled.value = !!s.enabled
      available.value = !!s.available
      const set = new Set()
      for (const r of s.running || []) {
        if (r && r.channel) set.add(r.channel)
      }
      runningKeys.value = set
      syncIntervalToBackend(s && s.fps)
    } catch {
      /*
       * 状态查询失败不弹错：这个接口每秒可能被调，网络抖动时弹窗会刷屏。
       * 静默降级成「不可用」，UI 上按钮变灰即可。
       */
      enabled.value = false
      available.value = false
    }
  }

  /**
   * 把轮询间隔对齐到后端的推理帧率。
   *
   * <h3>为什么必须对齐</h3>
   * 后端 fps 是配置项（{@code vision.fps}），前端如果写死一个值，
   * 两边一旦不一致就白付延迟：
   * <ul>
   *   <li>轮询比推理<b>快</b> —— 同一帧被反复取回，白费请求</li>
   *   <li>轮询比推理<b>慢</b> —— 最新结果在服务端等着，要等下一次轮询才上屏，
   *       平均多出半个轮询周期的延迟</li>
   * </ul>
   * 所以取「后端帧间隔」作为轮询周期：每产出一帧正好取一次。
   *
   * <p>下限 150ms：再快就是纯空转了，后端不可能产出那么密的帧。
   */
  function syncIntervalToBackend(fps) {
    if (!fps || fps <= 0) return
    const next = Math.max(150, Math.round(1000 / fps))
    if (next === intervalMs) return
    intervalMs = next
    // 定时器周期是创建时固定的，改间隔必须重建
    if (timer) {
      stopTimer()
      startTimer()
    }
  }

  /** 拉一轮所有被观察通道的检测结果。 */
  async function pollDetections() {
    const keys = watched
    if (!keys.length) return
    await Promise.all(
      keys.map(async (key) => {
        try {
          const d = await visionApi.detections(key)
          /*
           * 只有 at 变了才算「拿到新结果」。
           *
           * 早先这里无条件写 lastAt，于是「请求成功但后端检测卡住、
           * at 一直是同一个值」永远不会被判过期 —— 框会静静冻在画面上。
           * 现在拿同一个 at 反复回来，lastAt 不推进，超过阈值就标 stale。
           */
          const at = d && d.at ? d.at : 0
          const prevAt = results[key] && results[key].at
          if (at !== prevAt) {
            lastAt[key] = Date.now()
          }
          results[key] = d
          stale[key] = false
        } catch {
          // 单路失败只影响这一路，不要把整轮标记成失败
        }
      })
    )
    /*
     * 统一判过期：以「最后一次拿到新结果的时刻」为基准。
     * 请求本身失败的情况（catch 分支）不推进 lastAt，同样会被判过期 ——
     * 这正是想要的：连不上后端时框不该继续留在画面上。
     */
    const now = Date.now()
    for (const key of keys) {
      const at = lastAt[key] || 0
      stale[key] = at > 0 && now - at > staleAfterMs()
    }
  }

  function stopTimer() {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
  }

  /**
   * 启动轮询。
   *
   * <h3>为什么用「跑完再排下一次」而不是 setInterval</h3>
   * setInterval 不管上一次请求有没有回来就发下一次。网络稍慢时请求会叠加，
   * 而它们的<b>响应顺序不保证</b> —— 先发的后到，框会往回跳一帧。
   * 更糟的是叠加的请求全部打到后端，反而让本来就紧张的后端更慢。
   *
   * <p>改成「一次完成后再排下一次」：天然不会重叠，且间隔从<b>响应回来之后</b>
   * 才算起，等效周期是「请求耗时 + intervalMs」。因为请求本身只要几毫秒，
   * 这点偏差可以忽略，换来的是绝不乱序、绝不堆积。
   */
  function startTimer() {
    if (timer) return
    const tick = async () => {
      timer = null
      if (typeof document !== 'undefined' && document.hidden) {
        // 页面不可见时跳过：后台标签页拉流本来就没意义，还白烧服务端 CPU
        schedule()
        return
      }
      try {
        await pollDetections()
      } finally {
        schedule()
      }
    }
    const schedule = () => {
      if (timer || !watched.length) return
      timer = setTimeout(tick, intervalMs)
    }
    schedule()
  }

  /**
   * 设置要观察的通道（上屏且已开检测的）。
   *
   * 只在集合真变化时才重建定时器：每次上屏/清屏都会调，
   * 无脑重建会让轮询永远等不到 1 秒就重置，结果永远不刷新。
   */
  function setWatched(keys) {
    const next = [...new Set((keys || []).filter(Boolean))].sort()
    const same = next.length === watched.length && next.every((k, i) => k === watched[i])
    if (same) return
    watched = next
    // 清掉已不在观察列表里的残留，避免内存慢慢涨
    for (const k of Object.keys(results)) {
      if (!next.includes(k)) {
        delete results[k]
        delete stale[k]
        delete lastAt[k]
      }
    }
    if (!next.length) {
      stopTimer()
      return
    }
    /*
     * 先起定时器，再立刻拉一次。
     *
     * 顺序看起来无所谓，其实影响「开启后多久看到第一个框」：
     * startTimer 是「等 intervalMs 后才第一次请求」，所以必须紧跟着
     * 手动 pollDetections() 打头阵，否则第一帧要白等一个周期。
     *
     * 反过来（先 poll 后 startTimer）也可以，但 poll 是异步的，
     * 期间若 setWatched 被再次调用会并发两个请求 —— 先起定时器更稳。
     */
    startTimer()
    pollDetections()
  }

  /**
   * 开/关一路检测。
   *
   * @param {string} key 通道键（设备编码/通道编码）
   * @param {boolean} on 目标状态
   * @returns {Promise<boolean>} 是否成功
   */
  async function toggle(key, on) {
    if (!key) {
      ElMessage.warning('请先上屏一个通道')
      return false
    }
    if (pending.value.has(key)) return false
    pending.value.add(key)
    try {
      if (on) {
        await visionApi.start(key)
        /*
         * 先本地把状态置为「开启」，不等 refreshStatus 那一趟往返。
         *
         * 后端 start 已经返回成功，再等一次 status 请求才改按钮，
         * 用户会看到「点了按钮没反应」。这里乐观更新，失败时下面的
         * catch 会弹错，refreshStatus 也会把真实状态拉回来。
         */
        const next = new Set(runningKeys.value)
        next.add(key)
        runningKeys.value = next
        /*
         * 立刻开始轮询，别等 setWatched 被下一帧画面事件触发。
         * 首帧本来就要等 ffmpeg 起流（低延迟参数下约 0.3~0.7 秒），
         * 再让前端多等一个轮询周期没有意义。
         */
        if (watched.includes(key)) pollDetections()
        ElMessage.success('已开启 AI 检测，画面出现目标后会实时框出')
      } else {
        await visionApi.stop(key)
        // 关掉就把框清掉，否则会留在画面上让人以为还在检测
        delete results[key]
        delete stale[key]
        delete lastAt[key]
        ElMessage.success('已停止 AI 检测')
      }
      await refreshStatus()
      return true
    } catch (e) {
      ElMessage.error(e.message || (on ? '开启检测失败' : '停止检测失败'))
      return false
    } finally {
      pending.value.delete(key)
    }
  }

  const isPending = (key) => pending.value.has(key)

  /** 有任意一路在检测 —— 用来决定是否显示「AI 检测中」这类全局提示。 */
  const anyRunning = computed(() => runningKeys.value.size > 0)

  onUnmounted(() => {
    stopTimer()
  })

  // 后端总开关关掉时，不要再轮询（否则每秒一次 400 错误）
  watch(enabled, (v) => {
    if (!v) stopTimer()
    else if (watched.length) startTimer()
  })

  return {
    enabled,
    available,
    runningKeys,
    anyRunning,
    isOn,
    isStale,
    isPending,
    boxesOf,
    summaryOf,
    refreshStatus,
    setWatched,
    toggle
  }
}
