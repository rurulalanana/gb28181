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
 * @param {import('vue').Ref<number>} options.intervalMs 轮询间隔（默认 1000ms，与 fps 对齐）
 * @returns 检测状态与操作方法
 */
export function useVision(options = {}) {
  const intervalMs = options.intervalMs || 1000

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
   */
  const STALE_AFTER_MS = intervalMs * 3

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
    } catch {
      /*
       * 状态查询失败不弹错：这个接口每秒可能被调，网络抖动时弹窗会刷屏。
       * 静默降级成「不可用」，UI 上按钮变灰即可。
       */
      enabled.value = false
      available.value = false
    }
  }

  /** 拉一轮所有被观察通道的检测结果。 */
  async function pollDetections() {
    const keys = watched
    if (!keys.length) return
    const now = Date.now()
    await Promise.all(
      keys.map(async (key) => {
        try {
          const d = await visionApi.detections(key)
          results[key] = d
          lastAt[key] = now
          stale[key] = false
        } catch {
          // 单路失败只影响这一路，不要把整轮标记成失败
        }
      })
    )
    // 统一判过期：本轮没更新的通道，超过阈值就标 stale
    for (const key of keys) {
      const at = lastAt[key] || 0
      stale[key] = at > 0 && now - at > STALE_AFTER_MS
    }
  }

  function stopTimer() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  function startTimer() {
    if (timer) return
    timer = setInterval(() => {
      // 页面不可见时跳过：后台标签页拉流本来就没意义，还白烧服务端 CPU
      if (typeof document !== 'undefined' && document.hidden) return
      pollDetections()
    }, intervalMs)
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
    pollDetections()
    startTimer()
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
