# FuckMIUIHelperService · Safe Strict

基于 [Baka-Sky/FuckMIUIHelperService](https://github.com/Baka-Sky/FuckMIUIHelperService) 的保留数据版本。整包禁止以下三个组件启动，并在安装、更新、重新启用等系统包事件到达后重新检查：

| 组件 | 包名 |
| --- | --- |
| SystemHelper 系统助手 | `com.mobiletools.systemhelper` |
| GuardProvider 安全服务 | `com.miui.guardprovider` |
| MiTrust 设备信任服务 | `com.xiaomi.trustservice` |

**当前是 `2.0.0-preview.1`。已通过 Android 15 AOSP 模拟器集成测试，尚未在 HyperOS 真机及真实 Root 管理器中验证。** 模块不分析上传内容，也不据此证明这些应用实际上传了什么。

## 这版改了什么

- 保留整包禁用：禁用目标包、设置停止状态，停止属于目标包的进程。
- 删除按 UID 封网的全部逻辑。同 UID 的非目标应用不会被加入防火墙黑名单。
- 如果目标与非目标应用已经共用一个进程，先禁用目标包，**保留共享进程**，明确显示“尚未完全隔离”，需重启后复查。
- 删除每 10 秒循环。接收 Android PackageManager 的包事件；FileObserver 仅监听模块停用、删除和暂停标记。
- 不卸载目标、不清理其数据、不删安装包、不隐藏系统 APK、不改 AppOps、不清防火墙、不做全 UID 杀进程。
- 修改前按 Android 用户 ID＋用户序列号保存原启用状态与停止状态；停用、卸载时恢复这两项原状态。

“严格”指三个组件整体禁用。它们承载的正常安全检测、设备信任等功能也可能不可用。此模块不能阻止系统或其它 Root 程序修改状态；包事件到处理完成存在短暂窗口，不是内核级强制访问控制。已经运行的共享进程在重启前仍可能执行目标代码。

## 安装与查看

要求 Android 15 / API 35 或更新版本、Root，且系统保留所用的 PackageManager 接口。模块形式面向 Magisk / KernelSU / SukiSU；接口不符合时安装预检会拒绝，不能据此承诺所有厂商版本兼容。

1. 如果装过原版，先在 Root 管理器停用原版并重启，结束原版的循环与临时挂载。安装器会拒绝与运行中的原版共存。
2. 安装构建出的 `FuckMIUIHelperService-SafeStrict-2.0.0-preview.1.zip`，重启后生效。新模块 ID 为 `fuckmiuihelperservice_safe`。
3. 在管理器中点击模块“操作”查看中文状态，确认三个组件的实际状态和事件观察器是否运行。

原版已经删除的数据、安装包、修改的权限等无法由本模块凭空还原。本模块只恢复**本模块开始控制之前记录的启用状态、停止状态**，不会主动启动恢复后的应用。

Root 终端也可执行：

```sh
# 查看状态
sh /data/adb/modules/fuckmiuihelperservice_safe/run.sh status

# 立即恢复原状态，并暂停到下次手动恢复防护或下次启用状态下的重启
sh /data/adb/modules/fuckmiuihelperservice_safe/run.sh restore

# 手动重新启用事件防护；后台运行
sh /data/adb/modules/fuckmiuihelperservice_safe/run.sh resume >/dev/null 2>&1 &
```

正常停用或卸载优先走管理器。观察器看到停用标记后立即尝试恢复；如果观察器不在运行，开机恢复脚本会在下一次启动补做。若系统拒绝恢复，原始状态文件会保留，不能把“模块已卸载”等同于“恢复成功”。

恢复文件保存在 `/data/adb/fmh-safe/`（仅 Root 可读写）；独立开机脚本位于 `/data/adb/service.d/fmh-safe-restore.sh`。这些小文件故意保留，让模块目录删除后仍能恢复。恢复控制器的手动调用方式：

```sh
CLASSPATH=/data/adb/fmh-safe/controller.dex app_process /system/bin \
  io.github.yubainya.fmh.Main restore /data/adb/modules/fuckmiuihelperservice_safe
```

## 耗电与限制

- 无定时扫描、无 WakeLock、无网络请求、无常驻 VPN。
- 待机靠 Binder / 文件事件阻塞等待。只有目标包变更或模块控制事件才复核；重复事件合并处理，不反复写相同包状态。
- 保留一个 Java 后台进程，具有内存成本。模拟器采样结果与完整测试范围见 [测试记录](docs/VALIDATION.md)。**没有手机每天耗电百分比的实测数据。**
- 杀掉观察器后，已写入的禁用状态仍保留，但自动复核停止；系统服务重启也会终止观察器。可通过状态页发现，再重启模块/手机。
- 依赖 Android 内部 API；Android 版本号相同也不保证厂商实现一致。安装预检验证接口可用性，最终仍须在目标机确认事件触发和实际隔离状态。

## 从源码构建

依赖 Python 3.9+、JDK 17+、Android SDK Platform 35 和 Build Tools 35.0.0。构建只使用本机工具，不下载依赖。

```sh
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/android-sdk
# 如果工具位于自定义目录：
# export ANDROID_BUILD_TOOLS=/path/to/build-tools/35.0.0
python3 scripts/build.py
python3 tests/check_artifact.py
```

生成的 ZIP 与 SHA-256 在 `dist/`。`compile-stubs/` 只提供隐藏接口的编译形状，不进入 DEX；运行时由 Android 提供真正的 Binder 实现。

集成测试会安装、更新测试 APK、创建测试用户，**只允许在一次性、已取得 adb root 的模拟器运行**。它不会连接手机，设备序列号必须显式指定：

```sh
python3 tests/integration.py --serial emulator-XXXX
```

[实现说明](docs/DESIGN.md) · [原版 README 归档](docs/UPSTREAM_README.md)

保留上游 [GPL-3.0 LICENSE](LICENSE)。本分支源码与修改继续使用 GPL-3.0；原版 README 仅用于保留项目来源和历史说明，不代表本分支当前行为。
