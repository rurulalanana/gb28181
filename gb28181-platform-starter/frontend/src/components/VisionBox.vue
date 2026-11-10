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
  -->
  <div v-if="visible" class="vision-layer" :class="{ 'is-stale': stale }">
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
import { computed } from 'vue'

const props = defineProps({
  /**
   * 检测结果数组，元素形如
   * `{ label: 'person', confidence: 0.49, box: { x, y, w, h } }`，
   * 其中 box 是**归一化**坐标（0~1，左上角 + 宽高）。
   */
  detections: { type: Array, default: () => [] },
  /** 视频原始宽高（归一化坐标乘它得到像素，仅用于展示文案） */
  videoWidth: { type: Number, default: 0 },
  videoHeight: { type: Number, default: 0 },
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

const visible = computed(() => !props.stale && props.detections.length > 0)

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
  inset: 0;
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
