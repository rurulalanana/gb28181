<template>
  <div class="page">
    <el-card shadow="never" class="vision-card">
      <!-- 顶部状态条：把「能不能用」一次说清，避免用户点了才报错 -->
      <div class="vision-head">
        <div class="vision-head-main">
          <div class="vision-title">
            <el-icon :size="18"><Aim /></el-icon>
            <span>AI 检测</span>
            <!--
              三个状态标签必须带不同的 key。
              没有 key 时 Vue 会把三个 v-if 分支当成同一个元素来复用，
              而 el-tag 内部有 transition —— 切分支时旧标签进入离场动画却
              因为节点被复用而永远播不完，结果「未启用」和模型名同时挂在页面上
              （实测两个标签的 class 一直停在 leave-active）。

              ready 判断：首次加载完成前不显示任何状态标签。否则会先闪一下
              「未启用」（enabled 的初始值），数据回来才变 —— 用户会以为真没启用。
            -->
            <el-tag v-if="!ready" key="loading" size="small" type="info" effect="plain">
              加载中
            </el-tag>
            <el-tag v-else-if="!enabled" key="off" size="small" type="info" effect="plain">未启用</el-tag>
            <el-tag v-else-if="!available" key="nomodel" size="small" type="warning" effect="plain">
              无可用模型
            </el-tag>
            <el-tag v-else key="model" size="small" type="success" effect="plain">
              {{ modelLabel }}
            </el-tag>
          </div>
          <div class="vision-meta">
            <span v-if="meta.fps">帧率 <b>{{ meta.fps }}</b> fps</span>
            <span v-if="meta.confidenceThreshold">置信度阈值 <b>{{ meta.confidenceThreshold }}</b></span>
            <span>
              已配置 <b>{{ rows.length }}</b> 路 · 运行中 <b class="is-running">{{ runningCount }}</b> 路
            </span>
          </div>
        </div>
        <div class="vision-head-actions">
          <el-button size="small" :loading="loading" @click="load">
            <el-icon><Refresh /></el-icon> 刷新
          </el-button>
          <el-button
            size="small"
            type="primary"
            :disabled="!canWrite || !enabled || !available"
            @click="openPicker"
          >
            <el-icon><Plus /></el-icon> 添加通道
          </el-button>
        </div>
      </div>

      <!-- 关闭态整块提示，而不是让表格空着 -->
      <el-alert
        v-if="!enabled"
        type="info"
        :closable="false"
        show-icon
        title="AI 检测未启用"
        description="需要在服务器配置中打开 vision.enabled 并放置模型文件。启用前下方配置不会生效。"
        class="vision-alert"
      />

      <el-table v-loading="loading" :data="rows" stripe class="vision-table">
        <el-table-column label="通道" min-width="260">
          <template #default="{ row }">
            <div class="ch-cell">
              <span class="ch-name">{{ row.name || row.channelId }}</span>
              <span class="ch-code mono">{{ row.channelId }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="所属设备" min-width="180">
          <template #default="{ row }">
            <span class="mono text-muted">{{ row.deviceId || '—' }}</span>
          </template>
        </el-table-column>

        <!--
          状态列是这个页面的核心。三种状态必须能一眼区分：
          检测中(绿) / 已配置但未跑起来(黄) / 已启动但没出帧(红)。
          只显示「开/关」会让人以为黄和红是同一个问题。
        -->
        <el-table-column label="状态" width="150" align="center">
          <template #default="{ row }">
            <span class="state" :class="stateOf(row).cls">
              <i class="state-dot" />
              {{ stateOf(row).text }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="当前画面" width="150" align="center">
          <template #default="{ row }">
            <span v-if="summaryOf(row)" class="counts">{{ summaryOf(row) }}</span>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="检测能力" min-width="180">
          <template #default="{ row }">
            <!--
              场景（能力）列。这是多模型功能的核心呈现：
              显示「人形检测」「车辆检测」这种能力名，而不是模型文件名 ——
              运维关心的是「这一路在检测什么」，不是「用的哪个 .onnx」。
              下拉可直接切换，切换会重启这一路（场景决定模型，必须重新装配）。
            -->
            <el-select
              :model-value="row.scenarioId || ''"
              size="small"
              :disabled="!canWrite"
              placeholder="未设置"
              style="width: 100%"
              @change="(v) => changeScenario(row, v)"
            >
              <el-option
                v-for="s in scenarios"
                :key="s.id"
                :value="s.id"
                :label="s.name"
                :disabled="!s.available"
              >
                <el-tooltip
                  :content="scenarioTip(s)"
                  placement="right"
                  :show-after="400"
                  popper-class="scenario-tip-popper"
                >
                  <div class="scenario-opt">
                    <span class="scenario-opt-name">{{ s.name }}</span>
                    <span class="scenario-opt-meta">{{ scenarioMeta(s) }}</span>
                  </div>
                </el-tooltip>
              </el-option>
            </el-select>
          </template>
        </el-table-column>

        <el-table-column label="累计推理" width="110" align="center">
          <template #default="{ row }">
            <span class="mono">{{ row.inferredFrames || 0 }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ 'text-muted': !row.remark }">{{ row.remark || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goPreview(row)">
              <el-icon><VideoCamera /></el-icon> 预览
            </el-button>
            <el-button link type="danger" :disabled="!canWrite" @click="remove(row)">
              <el-icon><Delete /></el-icon> 移除
            </el-button>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty :image-size="76" :description="emptyText" />
        </template>
      </el-table>

      <div class="vision-foot">
        <span class="text-muted">
          提示：这里配置的是<b>长期开启</b>检测的通道。只想临时看一眼某个通道，
          直接在「通道管理 → 播放」或「实时预览」的弹窗里点 AI 检测即可，不会写进本列表。
        </span>
      </div>
    </el-card>

    <!--
      添加通道：可搜索的多选表格。
      不用穿梭框（transfer）：通道多时左右两栏都要滚，且看不到「已选哪些」的全貌。
      表格 + 复选 + 顶部搜索框，能一次看清状态并批量勾选。
    -->
    <el-dialog v-model="pickerDialog" title="添加检测通道" width="880px" top="7vh">
      <div class="picker-bar">
        <el-input
          v-model="pickerQuery"
          placeholder="搜索通道名称或编码"
          clearable
          style="width: 260px"
          @keyup.enter="() => {}"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-checkbox
          v-model="onlyOnline"
          label="只看在线"
          size="small"
        />
        <span class="picker-bar-spacer" />
        <!--
          场景选择放在这里而不是每行一个：批量添加时通常是「把这几路都配成人形检测」，
          逐行选太啰嗦。要单独改某一路，添加后在主表里改即可。
        -->
        <span class="picker-scenario-label">检测能力</span>
        <el-select v-model="pickScenario" size="small" style="width: 210px">
          <el-option
            v-for="s in scenarios"
            :key="s.id"
            :value="s.id"
            :label="s.name"
            :disabled="!s.available"
          >
            <el-tooltip
              :content="scenarioTip(s)"
              placement="right"
              :show-after="400"
              popper-class="scenario-tip-popper"
            >
              <div class="scenario-opt">
                <span class="scenario-opt-name">{{ s.name }}</span>
                <span class="scenario-opt-meta">{{ scenarioMeta(s) }}</span>
              </div>
            </el-tooltip>
          </el-option>
        </el-select>
      </div>

      <el-table
        ref="pickerTableRef"
        v-loading="pickerLoading"
        :data="pickerRows"
        max-height="380"
        stripe
        @selection-change="onPickChange"
      >
        <el-table-column type="selection" width="46" :selectable="isSelectable" />
        <el-table-column label="通道" min-width="230">
          <template #default="{ row }">
            <div class="ch-cell">
              <span class="ch-name">{{ row.name || row.channelId }}</span>
              <span class="ch-code mono">{{ row.channelId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="所属设备" min-width="170">
          <template #default="{ row }">
            <span class="mono text-muted">{{ row.deviceId || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="在线" width="80" align="center">
          <template #default="{ row }">
            <span class="state" :class="row.online ? 'is-ok' : 'is-off'">
              <i class="state-dot" />{{ row.online ? '在线' : '离线' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.added" size="small" type="success" effect="plain">已添加</el-tag>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :image-size="66" description="没有匹配的通道" />
        </template>
      </el-table>

      <div class="pick-hint">
        已添加的通道不能再选（避免重复起 ffmpeg）。离线的通道也可以添加，
        设备上线后会自动开始检测。
      </div>

      <template #footer>
        <el-button @click="pickerDialog = false">取消</el-button>
        <el-button
          type="primary"
          :loading="saving"
          :disabled="!canWrite || !picked.length"
          @click="savePicked"
        >
          添加所选（{{ picked.length }}）
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { confirmAction } from '@/utils/confirm'
import { deviceApi, visionApi } from '@/api'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

/**
 * 写权限用 system:config。
 *
 * <p>不用 channel:write：这个页面改的是「平台的检测配置」，不是通道本身的属性。
 * 两者权限模型不同 —— 有通道写权限的人可能只是维护通道名称，
 * 不该顺带能开一堆 CPU 密集的检测任务。
 */
const canWrite = computed(() => auth.can('system:config'))

const rows = ref([])
const loading = ref(false)
/** 首次加载完成标志：用来避免「未启用」状态标签在数据到达前先闪一下。 */
const ready = ref(false)
/** 可用场景（检测能力）列表。 */
const scenarios = ref([])
const meta = reactive({ fps: null, confidenceThreshold: null, defaultModel: '', defaultScenario: '', enabled: false, available: false })
const enabled = computed(() => meta.enabled)
const available = computed(() => meta.available)
const runningCount = computed(() => rows.value.filter((r) => r.running).length)
const modelLabel = computed(() => meta.defaultModel || '已就绪')

const emptyText = computed(() => {
  if (!ready.value) return '加载中'
  if (!enabled.value) return 'AI 检测未启用'
  if (!available.value) return '没有可用的模型'
  return '还没有配置任何通道，点右上角「添加通道」'
})

/** 通道名称与在线状态的补充信息（来自设备通道列表，vision 接口不返回这些）。 */
const channelMeta = ref(new Map())

/** 用「设备/通道」键索引，避免只按通道编码匹配时不同设备撞车。 */
function metaKey(deviceId, channelId) {
  return `${deviceId || ''}/${channelId || ''}`
}

/**
 * 通道的展示状态。
 *
 * <h3>为什么分四种而不是「开/关」</h3>
 * 「配置了但没跑起来」和「跑起来了但一帧没出」是<b>完全不同</b>的故障：
 * 前者多半是设备离线，后者是取流/解码问题（要查 ffmpeg 日志）。
 * 合并成一个「异常」会让人从错误的方向排查。
 */
function stateOf(row) {
  if (!row.running) {
    return { text: '未运行', cls: 'is-off' }
  }
  if (!row.healthy) {
    return { text: '启动中/无帧', cls: 'is-bad' }
  }
  return { text: '检测中', cls: 'is-ok' }
}

const CN_LABELS = {
  person: '人', bicycle: '自行车', car: '车', motorcycle: '摩托车', bus: '公交车',
  truck: '卡车', boat: '船', dog: '狗', cat: '猫'
}

/** 「当前画面」摘要：只显示有数量的类别，避免 0 项占位。 */
function summaryOf(row) {
  const c = row.counts
  if (!c) return ''
  const parts = Object.entries(c)
    .filter(([, n]) => n > 0)
    .map(([k, n]) => `${CN_LABELS[k] || k} ×${n}`)
  return parts.join(' · ')
}

/**
 * 场景下拉的副标题。
 *
 * <p>显示「模型 + 单帧耗时」而不是标签列表：两个「人形检测」的标签都是 person，
 * 光看标签分不出该选哪个，而「tiny / 150ms」才是真正的取舍点。
 */
function scenarioMeta(s) {
  const parts = []
  if (s.modelId) parts.push(s.modelId)
  if (s.estimatedMsPerFrame > 0) parts.push(`${s.estimatedMsPerFrame}ms`)
  return parts.join(' · ')
}

/** 场景下拉的悬浮说明：说清它到底检出什么。 */
function scenarioTip(s) {
  const lines = []
  if (s.description) lines.push(s.description)
  const labels = s.labels && s.labels.length ? s.labels.join(', ') : '全部 COCO 类别'
  lines.push(`检出类别：${labels}`)
  return lines.join('\n')
}

async function load() {
  loading.value = true
  try {
    const d = await visionApi.channels()
    meta.enabled = !!d.enabled
    meta.available = !!d.available
    meta.defaultModel = d.defaultModel || ''
    meta.defaultScenario = d.defaultScenario || ''
    meta.fps = d.fps
    meta.confidenceThreshold = d.confidenceThreshold
    scenarios.value = d.scenarios || []
    // 添加对话框默认选默认场景
    if (!pickScenario.value && meta.defaultScenario) {
      pickScenario.value = meta.defaultScenario
    }
    const list = d.channels || []
    rows.value = list.map((r) => {
      const m = channelMeta.value.get(metaKey(r.deviceId, r.channelId))
      return { ...r, name: m ? m.name : '' }
    })
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
    ready.value = true
  }
}

/**
 * 切换某通道的检测能力（场景）。
 *
 * <p>后端会重启这一路：场景决定用哪个模型和哪套标签过滤，
 * 而这两者是在启动时装配进流水线的，改配置不会自动生效。
 */
async function changeScenario(row, scenarioId) {
  if (!scenarioId || scenarioId === row.scenarioId) return
  try {
    await visionApi.updateScenario(row.channelId, scenarioId)
    ElMessage.success('已切换检测能力，正在重启这一路')
    await load()
  } catch (e) {
    ElMessage.error(e.message || '切换失败')
    // 失败要把下拉恢复到实际值，否则界面显示的和后端不一致
    await load()
  }
}

/**
 * 载入设备通道，补上名称与在线状态。
 *
 * <p>vision 接口只返回「编码 + 运行态」，而界面上必须显示<b>人看得懂的名字</b>，
 * 否则一排 20 位数字没人分得清哪个是哪个。分开取再合并，
 * 比让 vision 接口去 join 设备表更干净（视觉模块不该依赖设备表结构）。
 */
async function loadChannelMeta() {
  try {
    const res = await deviceApi.channelList({ limit: 0 })
    const list = res.channelList || []
    const map = new Map()
    for (const c of list) {
      map.set(metaKey(c.deviceId || c.serial, c.id || c.channelId), {
        // 优先自定义名：用户改过名字后应显示改过的那个
        name: c.customName || c.name,
        /*
         * 两个字段语义不同，都必须看：
         *   · deviceOnline —— 设备是否注册在线（ChannelVo 里没有 online 字段，
         *     写成 c.online 会拿到 undefined，所有通道一律显示「离线」）；
         *   · status       —— 设备目录上报的通道状态（ON/OFF）。
         * 实测两者会不一致：设备在线但某通道 Status=OFF 的情况真实存在，
         * 这种通道加上去也拉不到流。status 缺失（设备不报）时不据此判离线。
         */
        online: !!c.deviceOnline && c.status !== 'OFF'
      })
    }
    channelMeta.value = map
  } catch {
    // 拿不到名称不影响主流程，界面上退化成显示编码
  }
}

function goPreview(row) {
  router.push({
    path: '/channels',
    query: { serial: row.deviceId || '', code: row.channelId }
  })
}

async function remove(row) {
  const ok = await confirmAction({
    title: '移除检测通道',
    message: `确定不再对「${row.name || row.channelId}」做 AI 检测吗？`,
    confirmText: '移除'
  })
  if (!ok) return
  try {
    await visionApi.removeChannel(row.channelId)
    ElMessage.success('已移除')
    await load()
  } catch (e) {
    ElMessage.error(e.message || '移除失败')
  }
}

// ==================== 添加通道 ====================

const pickerDialog = ref(false)
const pickerLoading = ref(false)
const pickerQuery = ref('')
const onlyOnline = ref(true)
const pickerTableRef = ref(null)
const picked = ref([])
const saving = ref(false)
/** 批量添加时用的场景（检测能力）。 */
const pickScenario = ref('')

/** 已添加的通道编码集合，用于在选人列表里标「已添加」并禁止重复选。 */
const addedSet = computed(() => new Set(rows.value.map((r) => r.channelId)))

/**
 * 候选通道列表。
 *
 * <p>「只看在线」默认开启：离线的通道加上去也不会立刻出框，用户容易以为没生效。
 * 但保留开关 —— 设备暂时离线而运维想先把配置做好，是真实场景。
 */
const pickerRows = computed(() => {
  const q = pickerQuery.value.trim().toLowerCase()
  const out = []
  for (const [key, m] of channelMeta.value) {
    const sep = key.indexOf('/')
    const deviceId = key.slice(0, sep)
    const channelId = key.slice(sep + 1)
    if (onlyOnline.value && !m.online) continue
    if (q) {
      const hay = `${m.name || ''} ${channelId} ${deviceId}`.toLowerCase()
      if (!hay.includes(q)) continue
    }
    out.push({
      deviceId,
      channelId,
      name: m.name,
      online: m.online,
      added: addedSet.value.has(channelId)
    })
  }
  // 在线优先、已添加沉底：用户最想选的是「在线且还没加」的那些
  out.sort((a, b) => {
    if (a.added !== b.added) return a.added ? 1 : -1
    if (a.online !== b.online) return a.online ? -1 : 1
    return (a.name || a.channelId).localeCompare(b.name || b.channelId, 'zh')
  })
  return out
})

/** 已添加的不可选。 */
function isSelectable(row) {
  return !row.added
}

function onPickChange(sel) {
  picked.value = sel
}

async function openPicker() {
  pickerDialog.value = true
  pickerQuery.value = ''
  picked.value = []
  const firstTime = !channelMeta.value.size
  pickerLoading.value = true
  try {
    /*
     * 每次打开都重取，不能只在首次加载：
     * 在线状态是易变数据，缓存会让「设备已上线」仍显示离线、或「已离线」仍可勾选。
     */
    await loadChannelMeta()
  } finally {
    pickerLoading.value = false
  }
  // 首次拿到名称后重算主表展示（之后名称没变，不必重拉 vision 列表）
  if (firstTime) await load()
}

async function savePicked() {
  if (!picked.value.length) return
  saving.value = true
  let ok = 0
  const failed = []
  for (const p of picked.value) {
    try {
      await visionApi.addChannel(p.deviceId, p.channelId, pickScenario.value, '')
      ok++
    } catch (e) {
      failed.push(`${p.name || p.channelId}: ${e.message || '失败'}`)
    }
  }
  saving.value = false
  if (ok) ElMessage.success(`已添加 ${ok} 路检测通道`)
  if (failed.length) {
    ElMessage.warning(`${failed.length} 路添加失败：${failed[0]}`)
  }
  pickerDialog.value = false
  await load()
}

onMounted(async () => {
  await loadChannelMeta()
  await load()
})
</script>

<style scoped>
.vision-card {
  border-radius: 10px;
}

.vision-head {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  margin-bottom: 14px;
}

.vision-head-main {
  flex: 1;
  min-width: 0;
}

.vision-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.vision-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.vision-meta b {
  color: var(--el-text-color-primary);
  font-weight: 600;
}

.vision-meta b.is-running {
  color: var(--el-color-success);
}

.vision-head-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.vision-alert {
  margin-bottom: 12px;
}

.ch-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.ch-name {
  font-size: 13px;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ch-code {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}

.text-muted {
  color: var(--el-text-color-secondary);
}

/*
 * 状态徽标。
 * 用「小圆点 + 文字」而不是纯色块：色块在深色主题下和背景对比度不足，
 * 而圆点在任何主题下都能定位到。
 */
.state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  white-space: nowrap;
}

.state-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
  flex-shrink: 0;
}

.state.is-ok {
  color: var(--el-color-success);
}

.state.is-bad {
  color: var(--el-color-danger);
}

.state.is-off {
  color: var(--el-text-color-disabled);
}

.state.is-ok .state-dot {
  /* 检测中的那一路要「活」起来，一眼看出哪路真在跑 */
  animation: pulse 1.6s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.35; }
}

.counts {
  font-size: 12px;
  color: var(--el-color-primary);
  font-weight: 500;
}

.vision-foot {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
  font-size: 12px;
  line-height: 1.7;
}

.picker-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.picker-bar-spacer {
  flex: 1;
}

.picker-scenario-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}

/* 场景下拉项：名称 + 模型/耗时，让人知道这个能力到底检出什么 */
.scenario-opt {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.scenario-opt-name {
  font-size: 13px;
}

.scenario-opt-meta {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}

.pick-hint {
  margin-top: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
}
</style>

<style>
/* tooltip 是 teleport 到 body 的，作用域样式盖不到，必须用全局 + popper-class */
.scenario-tip-popper {
  max-width: 320px;
  white-space: pre-line;
  line-height: 1.6;
}
</style>
