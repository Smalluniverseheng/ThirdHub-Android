# 第三方科技 · ThirdHub-Android

ThirdHub 安卓客户端（v1.8）：WebView 壳承载完整网页版，**UI 与网页端 1:1 一致**，数据与网页版完全互通。

- 网页版：https://thirdhub.pages.dev
- 管理后台：https://thirdhub.pages.dev/admin
- 网页版仓库：https://github.com/Smalluniverseheng/ThirdHub

## 架构（v1.8）

- `MainActivity`：WebView + WebViewAssetLoader 加载内置网页版（`app/src/main/assets/web`）
- `SplashActivity`：品牌开屏（呼吸 Logo + 渐变 slogan + 版本号）
- `util/UpdateChecker`：自动更新（检查清单 → 弹公告 → DownloadManager 后台下载 → 完成后提示安装）
- 原生桥 `ThirdHubNative`：网页端「检查更新 / 自动检查更新」委托给原生更新器

## 数据互通

两端共用同一个 Supabase 项目（`th_` 前缀数据表）：账号 / 会员 / 书架 / 阅读进度 / 历史 / 收藏 / 设置 / 设备 / 密钥保险库 / 订单。
