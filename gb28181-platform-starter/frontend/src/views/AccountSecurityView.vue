<script setup>
/**
 * 账号安全。
 *
 * <p>从「系统设置」里拆出来的：改密码和 API 密钥都是**当前登录账号**的事，
 * 和平台级的运行时配置、接入控制、HTTPS 放在一个页面里，既容易误操作
 * （在平台设置里找自己的密码），也让那一页的分区越来越长。
 *
 * <p>入口放在右上角头像下拉 —— 用户找「我的账号」时第一反应是点自己名字。
 * 页面路由在 `router/modules/home.ts` 里静态注册且 `showLink: false`，
 * 所以它不出现在侧栏，但地址可以直接打开/收藏。
 */
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { confirmAction } from '@biz/utils/confirm'
import { CopyDocument, Key, Lock } from '@element-plus/icons-vue'
import { systemApi, userApi } from '@biz/api'
import { useAuthStore } from '@biz/stores/auth'
import HelpTip from '@biz/components/HelpTip.vue'

const router = useRouter()
const auth = useAuthStore()

/* ---------------- 修改密码 ---------------- */

const pwd = reactive({ oldpassword: '', newpassword: '', confirm: '' })
const saving = ref(false)

async function changePassword() {
  if (!pwd.oldpassword || !pwd.newpassword) {
    ElMessage.warning('请填写完整')
    return
  }
  if (pwd.newpassword !== pwd.confirm) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  saving.value = true
  try {
    await systemApi.modifyPassword({
      oldpassword: pwd.oldpassword,
      newpassword: pwd.newpassword
    })
    ElMessage.success('密码已修改，请重新登录')
    // 改密后旧 token 立即失效，主动登出比等下一次请求 401 更干净
    await auth.logout()
    router.push('/login')
  } catch (e) {
    ElMessage.error(e.message || '修改失败')
  } finally {
    saving.value = false
  }
}

/* ---------------- API 密钥 ---------------- */

/** 当前密钥状态（enabled / hint / createdAt），不含明文。 */
const apiKeyState = reactive({ enabled: false, hint: '', createdAt: '' })
/** 刚生成的明文，仅本次操作后显示。 */
const newApiKey = ref('')
const apiKeyBusy = ref(false)

async function loadApiKey() {
  try {
    const d = await userApi.apiKey()
    apiKeyState.enabled = !!d?.enabled
    apiKeyState.hint = d?.hint || ''
    apiKeyState.createdAt = d?.createdAt || ''
  } catch (e) {
    // 密钥状态取不到不该影响改密码：静默即可
  }
}

async function generateApiKey() {
  const tip = apiKeyState.enabled
    ? '重新生成会让当前密钥立即失效，正在使用它的对接程序会立刻无法登录。继续？'
    : '生成后请立刻复制保存，页面不会再次显示明文。继续？'
  if (!(await confirmAction(tip, 'API 密钥', { confirmButtonText: '生成' }))) {
    return
  }
  apiKeyBusy.value = true
  try {
    const d = await userApi.generateApiKey()
    newApiKey.value = d?.key || ''
    apiKeyState.enabled = !!d?.enabled
    apiKeyState.hint = d?.hint || ''
    apiKeyState.createdAt = d?.createdAt || ''
    ElMessage.success('已生成，请立刻复制')
  } catch (e) {
    ElMessage.error(e.message || '生成失败')
  } finally {
    apiKeyBusy.value = false
  }
}

async function revokeApiKey() {
  if (
    !(await confirmAction('撤销后使用该密钥的对接程序将立即无法登录。继续？', '撤销 API 密钥', {
      confirmButtonText: '撤销'
    }))
  ) {
    return
  }
  apiKeyBusy.value = true
  try {
    await userApi.revokeApiKey()
    newApiKey.value = ''
    apiKeyState.enabled = false
    apiKeyState.hint = ''
    apiKeyState.createdAt = ''
    ElMessage.success('已撤销')
  } catch (e) {
    ElMessage.error(e.message || '撤销失败')
  } finally {
    apiKeyBusy.value = false
  }
}

/** 复制密钥：优先 clipboard，非安全上下文（http + 内网 IP）下回落 execCommand。 */
async function copyApiKey() {
  const text = newApiKey.value
  if (!text) return
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      ElMessage.success('已复制')
      return
    }
  } catch (e) {
    // 落到下面的兜底
  }
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.cssText = 'position:fixed;top:0;left:0;width:1px;height:1px;opacity:0'
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    ta.remove()
    ElMessage.success('已复制')
  } catch (e) {
    ElMessage.error('复制失败，请手动选中复制')
  }
}

onMounted(loadApiKey)
</script>

<template>
  <div class="page acct-page">
    <header class="acct-head">
      <h2 class="acct-title"><el-icon><Lock /></el-icon> 账号安全</h2>
      <p class="acct-sub">
        与当前登录账号绑定的两项设置：登录密码、给程序用的 API 密钥。
        平台级的运行配置在「系统设置」里。
      </p>
    </header>

    <div class="acct-grid">
      <!-- 修改密码 -->
      <section class="panel">
        <div class="panel-head">
          <span class="panel-title">
            修改密码
            <HelpTip
              text="密码以 BCrypt(MD5(明文)) 存储。修改成功后当前登录态失效，需用新密码重新登录。"
            />
          </span>
        </div>
        <el-form :model="pwd" label-width="92px" class="acct-form" @submit.prevent>
          <el-form-item label="当前密码">
            <el-input
              v-model="pwd.oldpassword"
              type="password"
              show-password
              autocomplete="off"
            />
          </el-form-item>
          <el-form-item label="新密码">
            <el-input
              v-model="pwd.newpassword"
              type="password"
              show-password
              autocomplete="new-password"
            />
          </el-form-item>
          <el-form-item label="确认新密码">
            <el-input
              v-model="pwd.confirm"
              type="password"
              show-password
              autocomplete="new-password"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="saving" @click="changePassword">
              保存
            </el-button>
            <span class="form-hint">修改后需重新登录</span>
          </el-form-item>
        </el-form>
      </section>

      <!--
        API 密钥：给业务平台 / 脚本 / Postman 用。
        浏览器登录要拖滑块，机器做不了，所以给每个账号一把可撤销的密钥。
        明文只在生成时显示一次 —— 库里只存 SHA-256，服务端也拿不回来。
      -->
      <section class="panel">
        <div class="panel-head">
          <span class="panel-title">
            <el-icon><Key /></el-icon>
            API 密钥
            <HelpTip
              text="供业务平台 / 脚本调用。登录时把密钥放进 X-Api-Key 请求头即可，不需要密码与验证码。生成后请立刻复制保存，页面不会再次显示明文。"
            />
          </span>
        </div>

        <div class="acct-body">
          <template v-if="!apiKeyState.enabled">
            <p class="acct-tip">尚未生成。生成后可用于接口对接，随时可撤销。</p>
          </template>
          <template v-else>
            <div class="acct-row">
              <span class="acct-label">当前密钥</span>
              <span class="mono">{{ apiKeyState.hint }}••••••••••••••••</span>
            </div>
            <div class="acct-row">
              <span class="acct-label">生成时间</span>
              <span>{{ apiKeyState.createdAt || '—' }}</span>
            </div>
          </template>

          <!-- 明文只在这一块出现，刷新/关闭后不再可见 -->
          <div v-if="newApiKey" class="acct-new">
            <div class="acct-new-head">请立即复制，关闭后无法再次查看</div>
            <div class="acct-new-body">
              <input
                class="acct-new-input mono"
                :value="newApiKey"
                readonly
                @focus="$event.target.select()"
              />
              <el-button size="small" :icon="CopyDocument" @click="copyApiKey">
                复制
              </el-button>
            </div>
          </div>

          <div class="acct-actions">
            <el-button
              :type="apiKeyState.enabled ? 'default' : 'primary'"
              :loading="apiKeyBusy"
              @click="generateApiKey"
            >
              {{ apiKeyState.enabled ? '重新生成' : '生成密钥' }}
            </el-button>
            <el-button v-if="apiKeyState.enabled" :loading="apiKeyBusy" @click="revokeApiKey">
              撤销
            </el-button>
            <span class="form-hint">重新生成或撤销后，旧密钥立即失效</span>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.acct-head {
  padding-bottom: var(--sp-3);
  border-bottom: 1px solid var(--line-soft);
}

.acct-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: var(--fs-lg);
  font-weight: 600;
  letter-spacing: -0.2px;
  color: var(--text-primary);
}

.acct-sub {
  margin: 4px 0 0;
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--ink-500);
}

/*
 * 两块并排：宽屏左右各一，窄屏自动堆叠。
 * 不用固定 460px 限宽 —— 账号页只有两件事，让它们横向铺开比竖着堆一列更好扫。
 */
.acct-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
  gap: var(--sp-4);
  align-items: start;
  max-width: 980px;
}

.acct-form {
  padding: var(--sp-4) var(--sp-4) var(--sp-2);
}

.acct-body {
  padding: var(--sp-4);
}

.acct-tip {
  margin: 0 0 var(--sp-3);
  font-size: var(--fs-sm);
  color: var(--ink-500);
}

.acct-row {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  margin-bottom: var(--sp-2);
  font-size: var(--fs-sm);
}

.acct-label {
  flex: none;
  width: 72px;
  color: var(--ink-500);
}

/* 明文区块用醒目底色：它只出现这一次，用户很容易点完「生成」就直接关页面 */
.acct-new {
  margin: var(--sp-3) 0;
  padding: var(--sp-3);
  background: var(--bg-sunken);
  border: 1px solid var(--brand-300, var(--line-soft));
  border-radius: var(--r-md, 6px);
}

.acct-new-head {
  margin-bottom: var(--sp-2);
  font-size: var(--fs-xs);
  font-weight: 600;
  color: var(--warning-500);
}

.acct-new-body {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.acct-new-input {
  flex: 1;
  min-width: 0;
  height: 30px;
  padding: 0 8px;
  font-size: var(--fs-xs);
  color: var(--text-primary);
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
}

.acct-actions {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-3);
}
</style>
