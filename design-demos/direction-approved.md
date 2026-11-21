# 设计方向确认

日期：2026-10-31

## 展示的三版初稿

| 方向 | 原型 | 截图 | 逻辑 |
| --- | --- | --- | --- |
| A 轻简卡片 | `A-轻简卡片.html` | `A.png` | 现实参照：沿现有品牌语言进化 |
| B 编辑排版 | `B-编辑排版.html` | `B.png` | 最佳设计师：纸面 / 宋体 / 克制 |
| C 底部导航 | `C-底部导航.html` | `C.png` | 结构实验：三页签 + 主控圆盘 |

## 用户选择

用户选择：**A 轻简卡片**（AskUserQuestion 返回「A 轻简卡片 (Recommended)」）。

## 落地记录

- 版本：1.0.38 / versionCode 50
- 主页：状态卡（运行状态 / 当前界面 / 无障碍）、控制卡（浮窗 + 悬浮球开关）、主控启停按钮、底部版本号
- 设置抽屉：学习时长 − / 数值 / + 步进，修改即存
- 真机验证：MI 8（1080×2248），`device-main.png`、`device-drawer.png`、`device-run.png`

## 迭代记录（不重新过三方向门）

- 2026-11-01：新增「自动检测更新」设置开关 + 发现新版本弹窗，属于已选定方向 A（轻简卡片）的延展：
  设置抽屉沿用 `#F7F8FA` 浅卡片 + 46dp 胶囊开关；弹窗沿用白色 24dp 圆角卡片、主色 `#3D7AFD` 胶囊按钮（44dp 高 / 22dp 圆角）、无阴影。
  版本：1.0.40 / versionCode 52。
  真机验证（MI 8，1080×2248）：`device-settings-update.png`、`device-update-dialog.png`、`device-update-manual.png`、`device-installer.png`。
  应用内下载：隐藏 WebView 抓 `.down-btn` 的 apk 直链 → DownloadManager 下载 → ApkProvider + 系统安装器。
  APK 体积：clean 构建后 138KB（v1.0.39 为 126KB），增量为更新功能本身约 12KB。
