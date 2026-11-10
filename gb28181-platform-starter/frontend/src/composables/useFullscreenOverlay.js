import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

/**
 * 全屏时把叠加层搬进全屏元素。
 *
 * <h3>为什么必须搬</h3>
 * 浏览器进入某个元素的全屏后，<b>只渲染那个元素的子树</b>，它的兄弟和祖先全部不显示。
 * 叠加层是播放器根节点的<b>兄弟</b>（`.video-box` 下与 `.video-container` 并列），
 * 而播放器控制栏的全屏按钮请求的是它自己内部的 `.video-js` ——
 * 于是叠加层连同框一起消失，表现为「全屏后检测框就没了」。
 *
 * <h3>为什么要配合 position: fixed</h3>
 * 搬进去之后，父元素的定位方式由播放器决定（`.video-js` 可能有 `position: relative`，
 * 也可能没有）。用 absolute 时坐标会相对某个不确定的祖先解析，很容易算到别处。
 * 全屏元素本身占满整个视口，改用 `position: fixed` + 视口坐标，
 * 结果与嵌套层级完全无关。
 *
 * <h3>什么情况下不搬</h3>
 * 整面电视墙全屏时，全屏元素是叠加层的<b>祖先</b>，本来就在渲染子树里 ——
 * 这时搬过去反而会跑到错误位置。判据用「全屏元素是否在自己这一格里面」。
 *
 * @param {import('vue').Ref<HTMLElement|null>} layerRef 叠加层根节点
 * @returns {{ target: import('vue').Ref<HTMLElement|null>,
 *            fullscreen: import('vue').Ref<boolean> }}
 *   {@code target} 非空表示要 teleport 过去；{@code fullscreen} 为真时定位改用 fixed
 */
export function useFullscreenOverlay(layerRef) {
  const target = ref(null)
  const fullscreen = ref(false)
  /** 图层本来该待的地方（`.video-box`）。退出全屏要回到它。 */
  let host = null

  function captureHost() {
    const el = layerRef.value
    if (!el) {
      return
    }
    const parent = el.parentElement
    if (!parent) {
      return
    }
    // 已经搬走了：此时的 parent 是搬迁目标，不是自然宿主
    if (target.value && parent === target.value) {
      return
    }
    host = parent
  }

  function setTarget(el) {
    if (target.value === el) {
      return
    }
    target.value = el
    fullscreen.value = !!el
  }

  function resolve() {
    captureHost()
    const fs = typeof document === 'undefined' ? null : document.fullscreenElement
    if (!fs || !host) {
      setTarget(null)
      return
    }
    /*
     * host !== fs：全屏的就是这一格自己（或它的祖先），本来就在渲染子树里，不用搬。
     * host.contains(fs)：全屏元素在这一格内部（播放器自己全屏），必须搬进去。
     * fs.querySelector('video')：没有画面元素的全屏与我们无关，别乱搬。
     */
    if (host !== fs && host.contains(fs) && fs.querySelector('video')) {
      setTarget(fs)
    } else {
      setTarget(null)
    }
  }

  /*
   * 事件不是唯一入口：实测（headless Chromium / 部分内嵌浏览器）
   * `document.fullscreenElement` 会变，但 `fullscreenchange` 事件不一定派发 ——
   * 只靠事件时表现为「全屏后框还是消失」。
   * 所以同时对外暴露 sync()，调用方在任何会重排的时机（measure / resize）捎带调一次。
   * document.fullscreenElement 只是属性读取，代价可忽略，且 setTarget 幂等。
   */
  onMounted(() => {
    // webkit 前缀：Safari 15 之前只有带前缀的事件
    document.addEventListener('fullscreenchange', resolve)
    document.addEventListener('webkitfullscreenchange', resolve)
    resolve()
  })

  // 图层是 v-if 渲染的：挂载时机不固定，节点出现时补一次
  watch(layerRef, (el) => {
    if (el) {
      resolve()
    }
  })

  onBeforeUnmount(() => {
    document.removeEventListener('fullscreenchange', resolve)
    document.removeEventListener('webkitfullscreenchange', resolve)
  })

  return { target, fullscreen, sync: resolve }
}
