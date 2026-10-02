# 音匣 (Yinxia)

一个极简的本地音乐播放器：扫描手机里的音频、列表播放、通知栏/锁屏控制、后台播放。
没有网络权限，代码里没有任何联网逻辑，所有数据都在你手机上。

> **先说实话：这份代码我没有编译过。**
> 我这边的执行环境 shell 是坏的（`0xC0000142`，DLL 初始化失败），装不了 JDK/Android SDK，也没法跑 Gradle。
> 我已经做了能做的人工核对（版本兼容矩阵、资源引用、跨文件调用、图标路径），但**第一次构建仍可能需要 1–2 轮修错**。
> 报错日志贴给我，我来改。

---

## 一、它有哪些功能

| 功能 | 说明 |
|---|---|
| 音乐库列表 | 封面、标题、歌手 · 专辑、时长；正在播的那一行高亮 |
| 搜索 | 按标题 / 歌手 / 专辑过滤 |
| 迷你播放条 | 底部常驻，带进度条，点一下展开全屏播放页 |
| 全屏播放页 | 大封面、可拖动进度条、上一首 / 播放暂停 / 下一首 / 随机 / 循环 |
| 循环三态 | 关 → 列表循环 → 单曲循环 |
| 通知栏 & 锁屏控制 | 走 Media3 的 MediaSession，系统媒体卡片可用 |
| 蓝牙 / 耳机按键 | 上一首下一首、播放暂停由系统接管 |
| 拔耳机自动暂停 | 不会突然外放 |
| 后台播放 | 前台服务 + `mediaPlayback` 类型，划掉最近任务也不会断 |
| 深色 / 浅色模式 | 跟随系统；主色是自定的紫罗兰，也可一键改成壁纸取色 |
| 零外部 UI 依赖 | 图标全是自己画的矢量图，没有引 `material-icons-extended`、没有引图片加载库 |

**目前没做**：播放列表/收藏、文件夹浏览、均衡器、歌词、桌面小部件、按专辑/歌手分组、独立播放队列（队列就是整个音乐库）。

---

## 二、怎么变成手机上的 APK

### 路线 A：云端编译（推荐，不需要装 Android Studio）

1. 注册 / 登录 GitHub，新建一个仓库（public 或 private 都行，名字随便，比如 `yinxia`）。
2. 把 `Yinxia/` 目录里的**全部内容**上传上去。
   - 网页操作：仓库页 → `Add file` → `Upload files` → 把 `Yinxia` 里的文件和文件夹全拖进去。
   - ⚠️ 注意 `.github` 这个目录是隐藏的，**必须一起上传**，云端编译的配置就在里面。
3. 上传完会自动触发编译：仓库页 → **Actions** → 点最新那次运行。
4. 等 3–5 分钟，成功后页面底部 **Artifacts** 里下载 `yinxia-debug-apk`（是个 zip，解压出 `app-debug.apk`）。
5. 把 apk 传到手机安装。

免费账号在公开仓库上的构建时长是无限的；这个构建大概用 3–5 分钟。

### 路线 B：本地用 Android Studio

1. 装 Android Studio（最新版），首次启动时让它装 **SDK Platform 37** 和 **Build-Tools 37**
   （compileSdk 是 37，所以平台要 37；targetSdk 仍是 36）。
2. `File` → `Open`，选中 `Yinxia` 目录。
3. 首次打开会同步 Gradle（要下载依赖，视网络几分钟到十几分钟）。
4. 连上手机并打开 USB 调试，点绿色 ▶ 直接装；或者 `Build` → `Build APK(s)` 拿 `app-debug.apk`。

> ⚠️ **仓库里没有 `gradle-wrapper.jar`**（二进制文件我没法生成）。
> - 云端路线不受影响：workflow 用 `gradle/actions/setup-gradle` 直接安装 Gradle 9.6.0，不依赖 wrapper。
> - 本地路线：Android Studio 打开时会提示"创建 Gradle wrapper"，同意即可；或者用装了 Gradle 9.6.0 的机器跑一次 `gradle wrapper`。
>   国内网络慢的话，建议配国内 Maven 镜像加速依赖下载。

---

## 三、装到小米 15（HyperOS 3 / Android 16）之后要做的设置

**必须做第 2 条，否则锁屏后播几分钟就被系统杀掉**——这不是代码 bug，所有第三方播放器在小米上都要这么设：

1. **安装时**：会提示"未经安全检测"，选择继续安装 / 允许来自此来源。
2. **后台保活（关键）**：
   `设置` → `应用设置` → `音匣` → `省电策略` → 改成 **无限制**；
   同一页里把 **自启动** 打开。
3. **权限**：首次启动会请求"音乐和音频"权限；拒绝的话界面会给你一个按钮直接跳系统设置。
4. **通知栏控制**：播放后下拉通知栏就有媒体卡片；锁屏上也能控制。
   如果通知栏没有卡片，检查 `设置` → `通知管理` → `音匣` 是否被关掉。

---

## 四、代码结构

```
Yinxia/
├─ .github/workflows/build-debug-apk.yml   云端编译 APK 的流水线
├─ gradle/libs.versions.toml               所有依赖版本的唯一来源
├─ settings.gradle.kts / build.gradle.kts  Gradle 顶层配置
├─ gradle.properties                       JVM 内存、AndroidX 开关
└─ app/
   ├─ build.gradle.kts                     模块配置：compileSdk 37 / targetSdk 36 / minSdk 26
   └─ src/main/
      ├─ AndroidManifest.xml               权限、Activity、播放服务声明
      ├─ java/com/yinxia/music/
      │  ├─ MainActivity.kt                唯一 Activity；权限状态放这里，
      │  │                                 靠 onResume 处理"去设置里开权限再回来"
      │  ├─ data/
      │  │  ├─ Song.kt                     一首歌的数据
      │  │  ├─ MusicRepository.kt          用 MediaStore 扫描本地音频
      │  │  └─ ArtworkLoader.kt            封面解码 + 内存 LRU 缓存
      │  ├─ player/
      │  │  ├─ PlaybackService.kt          MediaSessionService：后台播放 + 系统媒体控制
      │  │  └─ PlaybackConnection.kt       界面进程 ↔ 播放服务的桥梁
      │  ├─ ui/
      │  │  ├─ PlayerViewModel.kt         界面状态；播放本身在服务里，所以转屏不断播
      │  │  ├─ App.kt                      权限门 / 列表 / 迷你条 / 弹出播放页 的组装
      │  │  ├─ LibraryScreen.kt            歌曲列表
      │  │  ├─ MiniPlayer.kt               底部迷你播放条
      │  │  ├─ NowPlaying.kt               全屏播放页
      │  │  ├─ Artwork.kt                  封面组件（取不到封面就按歌曲 id 用渐变兜底）
      │  │  └─ theme/                      配色、字体、Material 3 主题
      │  └─ util/Format.kt                 毫秒 → 3:07 / 1:02:45
      └─ res/
         ├─ drawable/ic_*.xml              自绘矢量图标（播放、暂停、上一首…）
         ├─ mipmap-anydpi-v26/             自适应启动图标
         └─ values/                        文案、颜色、主题
```

### 几个设计上的取舍

- **不用文件系统遍历，走 MediaStore。** Android 10 以后分区存储不允许随便读目录；MediaStore 有索引，几万首歌也快。
- **播放放在 `MediaSessionService` 而不是 Activity 里。** 这是锁屏/通知栏控制、蓝牙按键、后台不被回收的前提。
- **进度用 500ms 轮询，不是插值。** 切歌、拖动、暂停都会改变位置，轮询的代价很小，但状态永远和播放器一致，不会出现进度条和实际播放对不上。
- **拖动时用本地状态。** 如果每帧都把拖动位置写回播放器，会和播放进度互相打架，表现为"拖不动"。
- **封面走了个弯路。** Android 10+ 直接对歌曲的 content URI 调 `loadThumbnail`（系统返回内嵌封面）；Android 9 及以下退回老的 `albumart` 表——那张表按**专辑 id** 索引，不是歌曲 id，所以 `Song` 里专门留了 `albumId` 字段。

---

## 五、想改哪里

| 想改什么 | 改哪 |
|---|---|
| 主色调 | `ui/theme/Color.kt` |
| 用壁纸取色（Android 12+） | `ui/theme/Theme.kt` 里把 `dynamicColor` 默认值改成 `true` |
| 过滤掉更短的音频（默认 15 秒） | `MusicRepository.MIN_DURATION_MS` |
| 进度刷新频率（默认 500ms） | `PlayerViewModel.PROGRESS_INTERVAL_MS` |
| 封面缓存数量（默认 80 张） | `ArtworkLoader.CACHE_SIZE` |
| 应用名 / 包名 | `res/values/strings.xml` 的 `app_name`；包名要同步改 `build.gradle.kts` 的 `namespace`、`applicationId` 和 Kotlin 的 package |
| 支持更老的手机 | 改 `minSdk`。但要注意启动图标只做了 API 26+ 的自适应图标（纯矢量），降到 26 以下得补位图图标 |

---

## 六、版本矩阵（以及为什么这么锁）

| 组件 | 版本 | 说明 |
|---|---|---|
| AGP | 9.4.1 | AGP 9 是大版本变更（见下方"两个坑"），写法与 AGP 8 不同 |
| Gradle | 9.6.0 | AGP 9.4 要求 ≥ 9.6.0；CI 里由 action 安装，不依赖 wrapper 文件 |
| Kotlin | **2.2.10（不要改）** | AGP 9 内置 Kotlin，版本由 AGP 自带；AGP 9.4.1 的 POM 依赖 KGP 2.2.10。Compose 编译器插件必须与之**完全一致**，所以写 2.2.10 而不是更新的 2.4.x |
| JDK | 17 | AGP 9.x 的最低和默认 JDK 都是 17；CI 里也钉的 17，不是 21 |
| Compose BOM | 2026.09.00 | 统一决定 compose-ui(1.12.1) / material3 / foundation 版本，各库不写版本号 |
| Media3 | 1.11.1 | ExoPlayer + MediaSession |
| lifecycle-viewmodel | 2.11.0 | 注意不要用 `lifecycle-viewmodel-ktx`——2.8 起它已经是空壳 |
| kotlinx-coroutines | 不声明 | 由 compose-runtime 与 lifecycle-viewmodel 传递引入（1.9.0）。少一个版本号少一处坑 |
| compileSdk | **37** | Compose 1.12 起**强制要求** compileSdk 37 + AGP 9，写 36 会直接构建失败 |
| targetSdk | 36 | Android 16，对应你这台 HyperOS 3。compileSdk 和 targetSdk 不是一回事 |
| minSdk | 26 | Android 8.0 |

### AGP 9 的两个坑（AGP 8 时代的知识在这里是错的）

1. **Kotlin 支持是内置的。** AGP 9 起不能再应用 `org.jetbrains.kotlin.android`，否则报
   *"The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0"*。
   所以 `build.gradle.kts` 的 plugins 里只有 `com.android.application` 和 `org.jetbrains.kotlin.plugin.compose`。
2. **`kotlinOptions {}` 没了。** 内置 Kotlin 下 Kotlin 的 `jvmTarget` 默认跟随
   `android.compileOptions.targetCompatibility`，所以只要设一次 `compileOptions` 为 Java 17 就够了，
   不需要（也不该）再写 `kotlin { compilerOptions { … } }`。

另外 AGP 9 还有一些会影响老写法的变更：`applicationVariants`/`variantFilter` 被移除（改用 `androidComponents.onVariants`）、
`getDefaultProguardFile()` 只接受 `proguard-android-optimize.txt`、kapt 与内置 Kotlin 不兼容。
本工程都没用到这些，所以不受影响。

改版本时请连着看：**AGP ↔ 内置 Kotlin ↔ Compose 编译器插件** 是一组，单独动一个最容易炸。

