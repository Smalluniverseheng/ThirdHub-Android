# 第三方科技 · ThirdHub-Android

ThirdHub 原生安卓客户端（Kotlin + Material3），与网页版 **数据库完全互通**。

- 网页版：https://smalluniverseheng.github.io/ThirdHub/
- 管理后台：https://smalluniverseheng.github.io/ThirdHub-Admin/
- 网页版仓库：https://github.com/Smalluniverseheng/ThirdHub

## 数据互通

两端共用同一个 Supabase 项目（`th_` 前缀数据表）：

| 能力 | 互通内容 |
|---|---|
| 账号 | Supabase Auth 邮箱登录，同一账号两端通用 |
| 会员 | `th_profiles` 等级 / 存储容量实时共享 |
| 卡密 | 同一 RPC `th_redeem_card`（TP- 开头 50 位） |
| 书架 | `th_bookshelf`（user_id / id / data / updated_at） |
| 阅读进度 | `th_reading_progress`（章节号 + 滚动偏移） |
| 历史 / 收藏 | `th_history` / `th_favorites` |

## 功能（v1.2）

- **发现**：本地书籍（TXT / EPUB）导入与管理；零内置源政策说明
- **AI**：7 个 OpenAI 兼容厂商预设 + 自定义接口，SSE 流式输出，在线拉取模型列表，密钥仅存本机
- **书架**：云端书架（网页版加入的书实时可见）+ 本地书籍，长按移除
- **分类**：同步状态统计、连接器说明、本地书籍管理、缓存清理
- **我的**：登录 / 注册、会员等级与存储空间、卡密激活、检查更新、主题切换
- **阅读器**：章节切换 / 目录 / 字号 / 三种阅读主题，进度本地 + 云端双写

> 合规说明：本应用不内置任何内容源。在线内容的 JS 连接器在网页版 Web Worker 沙箱中运行；安卓版当前支持本地书籍与 AI 对话。

## 构建

推送代码后 GitHub Actions 自动构建 APK（见 Actions → build-apk → Artifacts）。

本地构建：

```bash
gradle wrapper --gradle-version 8.9
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

签名使用仓库内共享的 debug keystore（`app/debug.keystore`，密码 android），保证每次构建签名一致、可直接覆盖升级。正式发布请替换为自己的签名密钥。

## 许可

MIT License
