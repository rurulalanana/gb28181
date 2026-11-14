import { getPluginsList } from "./build/plugins";
import AutoImport from "unplugin-auto-import/vite";
import Components from "unplugin-vue-components/vite";
import { ElementPlusResolver } from "unplugin-vue-components/resolvers";
import { include, exclude } from "./build/optimize";
import { type UserConfigExport, type ConfigEnv, loadEnv } from "vite";
import {
  root,
  alias,
  wrapperEnv,
  pathResolve,
  __APP_INFO__
} from "./build/utils";

export default ({ mode }: ConfigEnv): UserConfigExport => {
  const { VITE_CDN, VITE_PORT, VITE_COMPRESSION, VITE_PUBLIC_PATH } =
    wrapperEnv(loadEnv(mode, root));
  return {
    base: VITE_PUBLIC_PATH,
    root,
    resolve: {
      alias
    },
    // 服务端渲染
    server: {
      // 端口号
      port: VITE_PORT,
      host: "0.0.0.0",
      // 本地跨域代理：后端 REST 与 ZLM 媒体流，沿用现有前端的两条目标地址
      proxy: {
        "/api": {
          target: process.env.VITE_API_TARGET || "http://localhost:48088",
          changeOrigin: true
        },
        "/rtp": {
          target: process.env.VITE_MEDIA_TARGET || "http://localhost:8081",
          changeOrigin: true
        }
      },
      // 预热文件以提前转换和缓存结果，降低启动期间的初始页面加载时长并防止转换瀑布
      warmup: {
        clientFiles: ["./index.html", "./src/{views,components}/*"]
      }
    },
    plugins: [
      ...getPluginsList(VITE_CDN, VITE_COMPRESSION),
      /*
       * 业务 22 个页面里有 385 处裸用 ElMessage / ElMessageBox / ElNotification，
       * 原前端靠这两个插件按需注入；迁移后必须保留，否则一进页面就 ReferenceError。
       * 同时补齐 <el-*> 标签的按需注册（业务 main 里仍全量注册 ElementPlus，二者不冲突）。
       */
      AutoImport({
        resolvers: [ElementPlusResolver()],
        imports: ["vue", "vue-router", "pinia"],
        dts: "types/auto-imports.d.ts"
      }),
      Components({
        resolvers: [ElementPlusResolver()],
        dts: "types/components.d.ts"
      })
    ],
    // https://cn.vitejs.dev/config/dep-optimization-options.html#dep-optimization-options
    optimizeDeps: {
      include,
      exclude
    },
    build: {
      // https://cn.vitejs.dev/guide/build.html#browser-compatibility
      target: "es2015",
      sourcemap: false,
      // 消除打包大小超过500kb警告
      chunkSizeWarningLimit: 4000,
      // 产物直接落进 Spring Boot 静态资源目录，单 jar 部署（与旧前端构建口径一致）
      outDir: "../src/main/resources/static",
      emptyOutDir: true,
      rollupOptions: {
        input: {
          index: pathResolve("./index.html", import.meta.url)
        },
        /*
         * 静态资源分类打包。
         *
         * 路径前缀必须是 assets/ 而不是骨架默认的 static/：后端
         * SpaWebConfiguration 的 SPA 兜底白名单只有
         * api|assets|record|error|jessibuca|js|webjars|swagger-ui|v3，
         * 没有 static；/static/js/x.js 会被 /{path}/** 兜底吞掉返回 index.html，
         * 浏览器按 JS 解析直接语法错误、整站白屏。assets/ 同时已有专门的
         * 静态映射（immutable 长缓存），改这里即可，无需动后端。
         */
        output: {
          chunkFileNames: "assets/js/[name]-[hash].js",
          entryFileNames: "assets/js/[name]-[hash].js",
          assetFileNames: "assets/[ext]/[name]-[hash].[ext]"
        }
      }
    },
    define: {
      __INTLIFY_PROD_DEVTOOLS__: false,
      __APP_INFO__: JSON.stringify(__APP_INFO__)
    }
  };
};
