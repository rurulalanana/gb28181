<script setup lang="ts">
import { useI18n } from "vue-i18n";
import Motion from "./utils/motion";
import { useRouter, useRoute } from "vue-router";
import { message } from "@/utils/message";
import { ref, reactive, toRaw } from "vue";
import { debounce } from "@pureadmin/utils";
import { useNav } from "@/layout/hooks/useNav";
import { useEventListener } from "@vueuse/core";
import type { FormInstance, FormRules } from "element-plus";
import { $t, transformI18n } from "@/plugins/i18n";
import { useLayout } from "@/layout/hooks/useLayout";
import { useUserStoreHook } from "@/store/modules/user";
import { initRouter, getTopMenu } from "@/router/utils";
import { bg, avatar, illustration } from "./utils/static";
import { useRenderIcon } from "@/components/ReIcon/src/hooks";
import { useTranslationLang } from "@/layout/hooks/useTranslationLang";
import { useDataThemeChange } from "@/layout/hooks/useDataThemeChange";
import SlideCaptcha from "@biz/components/SlideCaptcha.vue";
import { captchaApi } from "@biz/api";

import dayIcon from "@/assets/svg/day.svg?component";
import darkIcon from "@/assets/svg/dark.svg?component";
import globalization from "@/assets/svg/globalization.svg?component";
import Lock from "~icons/ri/lock-fill";
import Check from "~icons/ep/check";
import User from "~icons/ri/user-3-fill";

defineOptions({
  name: "Login"
});

const router = useRouter();
const route = useRoute();
const loading = ref(false);
const disabled = ref(false);
const ruleFormRef = ref<FormInstance>();

const { initStorage } = useLayout();
initStorage();

const { t } = useI18n();
const { dataTheme, overallStyle, dataThemeChange } = useDataThemeChange();
dataThemeChange(overallStyle.value);
const { title, getDropdownItemStyle, getDropdownItemClass } = useNav();
const { locale, translationCh, translationEn } = useTranslationLang();

const ruleForm = reactive({
  username: "admin",
  password: ""
});

/**
 * 只校验「非空」。
 *
 * <p>没有沿用框架示例里「8-18 位、两种字符组合」的正则：本平台的密码策略在服务端
 * （`gb28181.password.*`），前端再拦一道只会出现「密码明明是对的却输不进去」，
 * 而且默认账号是 `admin` —— 5 位纯字母，照那个正则连第一次登录都过不了。
 */
const loginRules: FormRules = {
  password: [
    {
      required: true,
      message: transformI18n($t("login.purePassWordReg")),
      trigger: "blur"
    }
  ]
};

/**
 * 本次部署是否需要滑块验证码。
 *
 * <p>由后端 `gb28181.login.captcha-enabled` 决定，前端不写死：本地调试或验证码组件
 * 故障时会被关掉，此时接口根本不校验 captchaId，前端若仍无条件弹滑块，
 * 用户就会卡在「拖了也登不进去」。查询失败时按「要验证码」处理，宁可多一步。
 */
const captchaRequired = ref(true);
const captchaVisible = ref(false);
/** 服务端下发的一次性验证凭证，用完即弃。 */
let captchaId = "";

captchaApi
  .required()
  .then(r => {
    captchaRequired.value = (r as any)?.required !== false;
  })
  .catch(() => {
    captchaRequired.value = true;
  });

/** 滑块通过：暂存凭证并自动提交。 */
const onCaptchaSuccess = (id: string) => {
  captchaId = id || "";
  if (captchaId) submit();
};

const submit = () => {
  if (loading.value) return;
  loading.value = true;
  useUserStoreHook()
    .loginByUsername({
      username: ruleForm.username,
      password: ruleForm.password,
      captchaId
    })
    .then(() => {
      // 登录成功后才有权限点，动态路由必须在这之后装配
      return initRouter().then(() => {
        disabled.value = true;
        const target =
          (route.query.redirect as string) || getTopMenu(true)?.path;
        router
          .push(target || "/")
          .then(() => {
            message(t("login.pureLoginSuccess"), { type: "success" });
          })
          .finally(() => (disabled.value = false));
      });
    })
    .catch(error => {
      // 凭证是一次性的，后端已消费；不清掉会让下次点击直接被判失效
      captchaId = "";
      message(error?.message || t("login.pureLoginFail"), { type: "error" });
    })
    .finally(() => (loading.value = false));
};

const onLogin = async (formEl: FormInstance | undefined) => {
  if (!formEl) return;
  await formEl.validate(valid => {
    if (!valid) return;
    // 需要验证码时先过滑块：用户还没填完就先弹拼图只会打断节奏，所以放在提交这一步
    if (captchaRequired.value && !captchaId) {
      captchaVisible.value = true;
      return;
    }
    submit();
  });
};

const immediateDebounce: any = debounce(
  formRef => onLogin(formRef),
  1000,
  true
);

useEventListener(document, "keydown", ({ code }) => {
  if (
    ["Enter", "NumpadEnter"].includes(code) &&
    !disabled.value &&
    !loading.value
  )
    immediateDebounce(ruleFormRef.value);
});
</script>

<template>
  <div class="select-none">
    <img :src="bg" class="wave" />
    <div class="flex-c absolute right-5 top-3">
      <!-- 主题 -->
      <el-switch
        v-model="dataTheme"
        inline-prompt
        :active-icon="dayIcon"
        :inactive-icon="darkIcon"
        @change="dataThemeChange"
      />
      <!-- 国际化 -->
      <el-dropdown trigger="click">
        <globalization
          class="hover:text-primary hover:bg-[transparent]! w-[20px] h-[20px] ml-1.5 cursor-pointer outline-hidden duration-300"
        />
        <template #dropdown>
          <el-dropdown-menu class="translation">
            <el-dropdown-item
              :style="getDropdownItemStyle(locale, 'zh')"
              :class="['dark:text-white!', getDropdownItemClass(locale, 'zh')]"
              @click="translationCh"
            >
              <IconifyIconOffline
                v-show="locale === 'zh'"
                class="check-zh"
                :icon="Check"
              />
              简体中文
            </el-dropdown-item>
            <el-dropdown-item
              :style="getDropdownItemStyle(locale, 'en')"
              :class="['dark:text-white!', getDropdownItemClass(locale, 'en')]"
              @click="translationEn"
            >
              <span v-show="locale === 'en'" class="check-en">
                <IconifyIconOffline :icon="Check" />
              </span>
              English
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
    <div class="login-container">
      <div class="img">
        <component :is="toRaw(illustration)" />
      </div>
      <div class="login-box">
        <div class="login-form">
          <avatar class="avatar" />
          <Motion>
            <h2 class="outline-hidden">{{ title }}</h2>
          </Motion>

          <el-form
            ref="ruleFormRef"
            :model="ruleForm"
            :rules="loginRules"
            size="large"
          >
            <Motion :delay="100">
              <el-form-item
                :rules="[
                  {
                    required: true,
                    message: transformI18n($t('login.pureUsernameReg')),
                    trigger: 'blur'
                  }
                ]"
                prop="username"
              >
                <el-input
                  v-model="ruleForm.username"
                  clearable
                  :placeholder="t('login.pureUsername')"
                  :prefix-icon="useRenderIcon(User)"
                />
              </el-form-item>
            </Motion>

            <Motion :delay="150">
              <el-form-item prop="password">
                <el-input
                  v-model="ruleForm.password"
                  clearable
                  show-password
                  :placeholder="t('login.purePassword')"
                  :prefix-icon="useRenderIcon(Lock)"
                />
              </el-form-item>
            </Motion>

            <Motion :delay="250">
              <el-button
                class="w-full mt-4!"
                size="default"
                type="primary"
                :loading="loading"
                :disabled="disabled"
                @click="onLogin(ruleFormRef)"
              >
                {{ t("login.pureLogin") }}
              </el-button>
            </Motion>
          </el-form>
        </div>
      </div>
    </div>

    <!-- 滑动验证码：每次登录都要过一次，凭证一次性、失败需重验 -->
    <SlideCaptcha v-model="captchaVisible" @success="onCaptchaSuccess" />
  </div>
</template>

<style scoped>
@import url("@/style/login.css");
</style>

<style lang="scss" scoped>
:deep(.el-input-group__append, .el-input-group__prepend) {
  padding: 0;
}

.translation {
  ::v-deep(.el-dropdown-menu__item) {
    padding: 5px 40px;
  }

  .check-zh {
    position: absolute;
    left: 20px;
  }

  .check-en {
    position: absolute;
    left: 20px;
  }
}
</style>
