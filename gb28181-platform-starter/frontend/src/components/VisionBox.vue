<template>
  <!--
    AI 检测框叠加层。叠在播放器上，只负责画框，完全不接管指针事件。

    为什么用绝对定位的 div 而不是 canvas：
    框就是矩形 + 一个标签，div 用 CSS 就能做圆角、阴影、半透明底色；
    canvas 要实现同样效果得手写绘制且要处理 devicePixelRatio 缩放。
    检测框数量是个位数（人形检测通常 0~5 个），DOM 开销可以忽略。

    为什么 pointer-events: none：
    播放器自身要接管单击/双击全屏、右键菜单。叠加层一旦吃指针事件，
    用户就会「点画面没反应」。这里只做视觉呈现。

    ⚠️ 图层必须对齐「画面内容」而不是容器：video 用 object-fit: contain 渲染，
    容器与画面之间有黑边（见 measure）。不对齐的话，框会整体偏移，
    黑边越宽偏得越多 —— 表现为「框画进黑边里」。
  -->
  <div v-if="visible" ref="layerRef" class="vision-layer" :class="{ 'is-stale': stale }" :style="layerStyle">
    <div
      v-for="(d, i) in boxes"
      :key="i"
      class="vision-box"
      :style="d.style"
    >
      <span class="vision-label" :class="{ 'is-inside': d.inTop }">{{ d.text }}</span>
    </div>

    <!-- 顶部汇总：一眼看到「现在画面里有几个人」 -->
    <div v-if="summary" class="vision-summary">
      <span class="vision-dot" :class="stale ? 'is-stale' : 'is-live'" />
      <span>{{ summary }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  /**
   * 检测结果数组，元素形如
   * `{ label: 'person', confidence: 0.49, box: { x, y, w, h } }`，
   * 其中 box 是**归一化**坐标（0~1，左上角 + 宽高）。
   */
  detections: { type: Array, default: () => [] },
  /** 结果是否已过期（超过若干个轮询周期没更新）。过期时整体变灰，避免用户以为还在实时检测。 */
  stale: { type: Boolean, default: false },
  /** 中文标签映射，未命中时直接显示英文类名 */
  labelMap: { type: Object, default: () => ({}) }
})

/** 目标类别 → 中文名。只覆盖常用几类，其余显示原文（COCO 80 类没必要全翻）。 */
const DEFAULT_LABELS = {
  person: '人',
  car: '车',
  truck: '卡车',
  bus: '公交车',
  bicycle: '自行车',
  motorcycle: '摩托车',
  dog: '狗',
  cat: '猫'
}

const layerRef = ref(null)

/**
 * 画面内容相对父容器的矩形（像素）。
 *
 * <p>归一化坐标是相对「视频画面」的，而父容器（.video-box）比画面大（有黑边），
 * 所以必须把图层收缩到画面矩形上，框才不会整体偏移。
 */
const rect = ref({ left: 0, top: 0, width: 0, height: 0 })

const layerStyle = computed(() => {
  const r = rect.value
  if (!r.width || !r.height) {
    return {}
  }
  return {
    left: `${r.left}px`,
    top: `${r.top}px`,
    width: `${r.width}px`,
    height: `${r.height}px`
  }
})

const visible = computed(() => !props.stale && props.detections.length > 0)

/**
 * 量出「画面内容」的矩形。
 *
 * <p>video 元素用 {@code object-fit: contain} 撑满容器，画面在元素内部居中并
 * 按长宽比缩放 —— 元素的四条边不等于画面的四条边。所以：
 * <pre>
 *   scale  = min(元素宽 / 视频宽, 元素高 / 视频高)
 *   画面宽 = 视频宽 * scale；画面高 = 视频高 * scale
 *   画面左上角 = 元素左上角 + (元素尺寸 - 画面尺寸) / 2
 * </pre>
 * 与 {@code TrackBox.measureVideo} 同一套算法（框选跟踪也靠它区分黑边）。
 */
function measure() {
  const layer = layerRef.value
  if (!layer || !layer.parentElement) {
    return
  }
  const parent = layer.parentElement
  const video = parent.querySelector('video')
  const pr = parent.getBoundingClientRect()
  if (!pr.width || !pr.height) {
    return
  }
  if (!video) {
    // 画面还没起来：退回容器矩形，至少不会算出 NaN
    rect.value = { left: 0, top: 0, width: pr.width, height: pr.height }
    return
  }
  const vr = video.getBoundingClientRect()
  const nw = video.videoWidth || 0
  const nh = video.videoHeight || 0
  if (!vr.width || !vr.height || !nw || !nh) {
    rect.value = { left: 0, top: 0, width: pr.width, height: pr.height }
    return
  }
  const scale = Math.min(vr.width / nw, vr.height / nh)
  const dispW = nw * scale
  const dispH = nh * scale
  rect.value = {
    left: vr.left - pr.left + (vr.width - dispW) / 2,
    top: vr.top - pr.top + (vr.height - dispH) / 2,
    width: dispW,
    height: dispH
  }
}

/**
 * 节流版 measure。
 *
 * <p>播放器会持续改控制栏 DOM（timeupdate 每秒数次），子树观察会因此被高频触发；
 * {@code getBoundingClientRect} 会强制回流，不节流会在多格同屏时变成性能问题。
 * 100ms 足够跟住「画面尺寸变化」这类慢变量。
 */
let lastMeasureAt = 0
function measureThrottled() {
  const now = Date.now()
  if (now - lastMeasureAt < 100) {
    return
  }
  lastMeasureAt = now
  measure()
}

let resizeObserver = null
let mutationObserver = null
let alignTimer = null

onMounted(() => {
  measure()
  const parent = layerRef.value && layerRef.value.parentElement
  if (parent) {
    // 容器尺寸变化（分屏切换、窗口缩放、全屏）
    if (typeof ResizeObserver !== 'undefined') {
      resizeObserver = new ResizeObserver(() => measureThrottled())
      resizeObserver.observe(parent)
    }
    /*
     * video 元素会被播放器重建（卡顿重拉时 LivePlayer 整体换节点），
     * 只监听尺寸不够 —— 新节点的 videoWidth 要等 metadata 才有值。
     * 子树变动时重测，覆盖「换节点」与「metadata 到达」两种时机。
     */
    mutationObserver = new MutationObserver(() => measureThrottled())
    mutationObserver.observe(parent, { childList: true, subtree: true })
  }
  // 起播后 2 秒内每 200ms 重测一次：metadata 到达的时刻不固定
  alignTimer = setInterval(measureThrottled, 200)
  setTimeout(() => {
    if (alignTimer) {
      clearInterval(alignTimer)
      alignTimer = null
    }
  }, 2000)
})

onBeforeUnmount(() => {
  if (resizeObserver) resizeObserver.disconnect()
  if (mutationObserver) mutationObserver.disconnect()
  if (alignTimer) clearInterval(alignTimer)
})

// 检测结果变化时（画面可能刚换）也重测一次
watch(() => props.detections, () => measureThrottled())

/**
 * 把归一化框换算成百分比定位。
 *
 * 用百分比而不是像素：叠加层尺寸随分屏格变化（1/4/9/16 分屏），
 * 用百分比就不需要在 resize 时重新计算。
 *
 * 夹取到 0~100：模型偶尔会给出略微越界的框（letterbox 反变换的浮点误差），
 * 不夹会让标签跑到画面外面。
 */
const boxes = computed(() =>
  props.detections.map((d) => {
    const b = d.box || {}
    const x = clamp01(b.x) * 100
    const y = clamp01(b.y) * 100
    const w = clamp01(b.w) * 100
    const h = clamp01(b.h) * 100
    const label = props.labelMap[d.label] || DEFAULT_LABELS[d.label] || d.label
    const conf = typeof d.confidence === 'number' ? d.confidence.toFixed(2) : ''
    return {
      text: conf ? `${label} ${conf}` : label,
      /*
       * 框贴着画面顶端时（y 很小），标签放在框上方会跑到画面外看不见。
       * 用 inTop 翻转成「贴在框内侧」—— 这是个纯视觉判断，不影响框本身位置。
       */
      inTop: y < 4,
      style: {
        left: `${x}%`,
        top: `${y}%`,
        width: `${w}%`,
        height: `${h}%`
      }
    }
  })
)

/** 顶部汇总文案：「人 ×2」这种。同一标签合并计数。 */
const summary = computed(() => {
  if (!props.detections.length) return ''
  const counts = {}
  for (const d of props.detections) {
    const label = props.labelMap[d.label] || DEFAULT_LABELS[d.label] || d.label
    counts[label] = (counts[label] || 0) + 1
  }
  return Object.entries(counts)
    .map(([k, v]) => `${k} ×${v}`)
    .join('  ')
})

function clamp01(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return 0
  return Math.min(1, Math.max(0, n))
}
</script>

<style scoped>
.vision-layer {
  position: absolute;
  /* left/top/width/height 由 JS 量出的画面矩形决定（见 measure） */
  /* 只做视觉呈现，绝不抢播放器的点击/双击全屏 */
  pointer-events: none;
  z-index: 3;
}

/* 结果过期：整体变灰，提示「这不是当前画面」 */
.vision-layer.is-stale {
  opacity: 0.35;
  filter: grayscale(1);
}

.vision-box {
  position: absolute;
  box-sizing: border-box;
  border: 2px solid #ff4d4f;
  border-radius: 3px;
  /* 深色描边 + 亮色主边：浅色背景（雪地/水面反光）上也能看清 */
  box-shadow: 0 0 0 1px rgba(0, 0, 0, 0.45), inset 0 0 0 1px rgba(255, 255, 255, 0.18);
}

.vision-label {
  position: absolute;
  left: -2px;
  top: -19px;
  padding: 1px 5px;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
  color: #fff;
  background: #ff4d4f;
  border-radius: 2px 2px 2px 0;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 框贴顶时标签翻到框内侧，否则会跑到画面外看不见 */
.vision-label.is-inside {
  top: 1px;
  border-radius: 0 2px 2px 2px;
}

.vision-summary {
  position: absolute;
  right: 8px;
  top: 8px;
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 3px 9px;
  font-size: 12px;
  color: #fff;
  background: rgba(0, 0, 0, 0.62);
  border-radius: 10px;
  backdrop-filter: blur(2px);
}

.vision-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.vision-dot.is-live {
  background: #52c41a;
  box-shadow: 0 0 0 2px rgba(82, 196, 26, 0.28);
  animation: vision-pulse 1.6s ease-in-out infinite;
}

.vision-dot.is-stale {
  background: #8c8c8c;
}

@keyframes vision-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.35; }
}
</style>
