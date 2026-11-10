import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { visionApi } from '@/api'

/**
 * AI 检测的开关与结果获取。
 *
 * <h3>传输方式：WebSocket 优先，轮询降级</h3>
 * 主路径是 {@code /api/v1/vision/ws} 推送：帧一产生就发，不用对齐轮询周期，
 * 且 16 分屏时只有一条连接（轮询是每秒 16 个 HTTP 请求，每个都要过鉴权拦截器）。
 *
 * <p>但 WebSocket 在三种情况下不可用，必须能自动退回轮询：
 * 反向代理没配 {@code Upgrade} 头、企业网络拦非 HTTP 流量、服务端推送器异常。
 * 所以这里<b>同时保留两条路径</b>：连不上或断开超过一定时间就启用轮询，
 * 连上就停掉轮询。用户不需要知道走的是哪条。
 *
 * <h3>为什么一个 composable 而不是写在页面里</h3>
 * 直播页已经 1100 多行。检测涉及「开关状态 + 连接管理 + 过期判定 + 错误降噪」，
 * 塞进页面会让人不敢改。这里把状态和生命周期封起来，页面只拿
 * `toggle / boxesOf / isOn`。
 *
 * @param {object} options
 * @param {number} options.intervalMs 降级轮询的间隔（默认 250ms）
 * @param {boolean} options.useWebSocket 是否尝试 WebSocket（默认 true）
 * @returns 检测状态与操作方法
 */
export function useVision(options = {}) {
  /*
   * 降级轮询的间隔。只在 WebSocket 不可用时生效，所以取一个和后端帧率
   * 相当的值即可 —— 此时延迟由轮询周期决定，不是主要矛盾。
   *
   * 会被后端返回的 fps 覆盖（见 syncIntervalToBackend）。
   */
  let intervalMs = options.intervalMs || 250

  /** 是否尝试 WebSocket。 */
  const wantWebSocket = options.useWebSocket !== false

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

  // ==================== WebSocket 推送 ====================

  /** 当前连接。 */
  let socket = null
  /** 重连定时器。 */
  let reconnectTimer = null
  /** 连续重连失败次数：用来做指数退避，避免服务端挂掉时疯狂重连。 */
  let retryCount = 0
  /** 心跳定时器。 */
  let pingTimer = null
  /** 连接是否已就绪（握手完成）。 */
  const connected = ref(false)

  const socketReady = () => !!socket && socket.readyState === WebSocket.OPEN

  /** 拼 WebSocket 地址。token 走 query —— new WebSocket() 带不了请求头。 */
  function socketUrl() {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
    const token = localStorage.getItem('gb_token') || ''
    return `${proto}//${location.host}/api/v1/vision/ws?token=${encodeURIComponent(token)}`
  }

  /** 建立连接（幂等）。 */
  function ensureSocket() {
    if (!wantWebSocket) return
    if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
      return
    }
    let ws
    try {
      ws = new WebSocket(socketUrl())
    } catch {
      // 构造就失败（极少见）：直接降级轮询
      fallbackToPolling()
      return
    }
    socket = ws

    ws.onopen = () => {
      connected.value = true
      retryCount = 0
      // 连上了就不用轮询了
      stopTimer()
      sendWatch()
      startPing()
    }

    ws.onmessage = (ev) => {
      let msg
      try {
        msg = JSON.parse(ev.data)
      } catch {
        return
      }
      if (msg.type === 'detections' && msg.data) {
        applyPush(msg.data)
      }
      // hello / watching / pong 不需要处理
    }

    ws.onerror = () => {
      // onclose 会跟着触发，这里不重复处理
    }

    ws.onclose = () => {
      connected.value = false
      stopPing()
      socket = null
      /*
       * 断线后要恢复轮询：否则用户看到的是「框停在那里不动」，
       * 而实际上数据已经不再更新了 —— 比不显示更糟。
       */
      if (watched.length) {
        startTimer()
        scheduleReconnect()
      }
    }
  }

  /** 把推送结果写进状态。结构与 REST 完全一致，所以两条路径共用渲染逻辑。 */
  function applyPush(data) {
    const now = Date.now()
    for (const [key, d] of Object.entries(data)) {
      if (!watched.includes(key)) continue
      const prevAt = results[key] && results[key].at
      if (d.at !== prevAt) {
        lastAt[key] = now
      }
      results[key] = d
      stale[key] = false
    }
    // 收到推送说明这一路是活的，顺手刷新过期判定
    for (const key of watched) {
      const at = lastAt[key] || 0
      stale[key] = at > 0 && now - at > staleAfterMs()
    }
  }

  /** 告诉服务端要看哪些通道。 */
  function sendWatch() {
    if (!socketReady()) return
    try {
      socket.send(JSON.stringify({ action: 'watch', channels: watched }))
    } catch {
      // 发送失败由 onclose 兜底
    }
  }

  /**
   * 心跳。
   *
   * <p>浏览器的 WebSocket 不会自动发 ping 帧，而中间的 nginx / 云负载均衡
   * 通常 60 秒无数据就断开连接。前端每 20 秒发一条应用层 ping，
   * 既保活又让服务端的空闲回收不会误杀。
   */
  function startPing() {
    stopPing()
    pingTimer = setInterval(() => {
      if (!socketReady()) return
      try {
        socket.send(JSON.stringify({ action: 'ping' }))
      } catch {
        // 忽略
      }
    }, 20_000)
  }

  function stopPing() {
    if (pingTimer) {
      clearInterval(pingTimer)
      pingTimer = null
    }
  }

  /**
   * 安排重连（指数退避，封顶 10 秒）。
   *
   * <p>为什么不一断开就立刻重连：服务端重启 / 网络中断时，立即重连会
   * 变成每秒几十次握手，反而拖慢恢复。1s → 2s → 4s → 8s → 10s 封顶，
   * 既能快速恢复，又不会打爆服务端。
   */
  function scheduleReconnect() {
    if (reconnectTimer || !watched.length || !wantWebSocket) return
    const delay = Math.min(10_000, 1000 * Math.pow(2, retryCount))
    retryCount++
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      if (watched.length) ensureSocket()
    }, delay)
  }

  /** 关闭连接（页面卸载 / 没有要看的通道时）。 */
  function closeSocket() {
    stopPing()
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    retryCount = 0
    if (socket) {
      const s = socket
      socket = null
      connected.value = false
      // 先摘掉回调，避免主动关闭时触发重连
      s.onopen = null
      s.onmessage = null
      s.onerror = null
      s.onclose = null
      try {
        s.close()
      } catch {
        // 已经关了
      }
    }
  }

  /** WebSocket 不可用时退回轮询。 */
  function fallbackToPolling() {
    if (!watched.length) return
    startTimer()
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
      // 没有要看的通道：断开连接，别让服务端白推
      closeSocket()
      return
    }
    /*
     * 优先走 WebSocket：它不受轮询周期限制，帧产生即到。
     *
     * 如果连接没就绪（正在握手 / 刚断开在重连），先打一次 REST 兜底，
     * 保证「开启检测后立刻能看到框」，而不是等连接建立。
     */
    if (wantWebSocket) {
      ensureSocket()
      sendWatch()
    }
    if (socketReady()) {
      // 已连上：轮询不需要了
      stopTimer()
      // 但首帧仍要立刻取一次，否则要等下一次推送（最多 200ms）
      pollDetections()
      return
    }
    /*
     * 先起定时器，再立刻拉一次。
     *
     * 顺序看起来无所谓，其实影响「开启后多久看到第一个框」：
     * startTimer 是「等 intervalMs 后才第一次请求」，所以必须紧跟着
     * 手动 pollDetections() 打头阵，否则第一帧要白等一个周期。
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
         * 立刻取一次，别等 setWatched 被下一帧画面事件触发。
         * 首帧本来就要等 ffmpeg 起流（低延迟参数下约 0.3~0.7 秒），
         * 再让前端多等一个推送节拍没有意义。
         *
         * 这里<b>总是</b>用 REST 取一次，不管 WebSocket 是否已连上：
         * 推送有 200ms 节拍，而这一帧是「用户点了按钮」的直接反馈，
         * 用一次 HTTP 换取即时响应是值得的。后续更新交给推送。
         */
        if (watched.includes(key)) {
          ensureSocket()
          sendWatch()
          pollDetections()
        }
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
    closeSocket()
  })

  // 后端总开关关掉时，不要再轮询/推送（否则每秒一次 400 错误）
  watch(enabled, (v) => {
    if (!v) {
      stopTimer()
      closeSocket()
    } else if (watched.length) {
      if (wantWebSocket) {
        ensureSocket()
        sendWatch()
      }
      startTimer()
    }
  })

  return {
    enabled,
    available,
    runningKeys,
    anyRunning,
    /** 是否走 WebSocket 推送（false 表示降级到轮询，排障时有用）。 */
    connected,
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
