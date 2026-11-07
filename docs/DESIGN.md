# 实现与边界

## 执行链路

`service.sh` → `run.sh resume` → Root `app_process` → `Main`。

1. 验证 Android 版本、反射方法签名。
2. 取得独占观察器文件锁，注册 PackageManager 回调，监听模块控制文件。
3. 在独占策略锁内，记录当前全部目标用户的原状态并原子落盘。
4. 使用 `setApplicationEnabledSetting(DISABLED_USER, DONT_KILL_APP)` 按包禁用，设置 stopped。
5. 检查 AMS 返回的 `pkgList`。发现目标和非目标共用进程则不 force-stop，并报告部分隔离；否则只调用针对目标包的 `forceStopPackage`。
6. 每次相关事件重新查询实际状态；相同状态不重复写入。

它没有修改包的 UID，也没有调用 UID 级网络或进程控制。`forceStopPackage` 仍是 Android 的包级停止语义，系统可能处理依赖关系；模拟器验证了同 UID 独立进程和混合共享进程两种情形，不能代替厂商机全场景验证。

## 为什么使用 PackageManager 回调

Android 15 的 `IPackageManager.registerPackageMonitorCallback(IRemoteCallback, userId)` 支持直接注册 Binder 接收端。普通 `app_process` 不是 AMS 管理的应用进程，不能依赖常规动态广播注册；监听 `package-restrictions.xml` 则可能受到系统延迟落盘影响。

AOSP 回调分发的包可见性 allowlist **明确豁免 SYSTEM_UID，但不会统一豁免 root UID**。因此，仅在注册调用期间将控制器自身有效 UID 切换到 1000，并在 `finally` 立即恢复 0。真实 UID 仍为 root；不修改目标应用 UID、权限或系统全局配置。初次 root-only 注册的实测没有收到重新启用事件，改为此方式后通过功能测试。此行为若被厂商限制，不能使用轮询或破坏性操作伪装兼容。

代码只接收 system/root 发来的回调，按精确包名筛选。事件执行采用单工作线程、合并待处理标志；不创建定时线程。`probe` 只注册/注销并查询接口，不改变目标包状态；它不等价于真机完整防护测试。

## 数据与恢复

`baseline.json` 保存原始事实：目标包名、用户 ID、用户序列号、原 enabled 枚举值、原 stopped 值。它不是对用户数据内容的备份。对同一用户/目标包重复隔离不覆盖初始状态；一次完整恢复后重新启用防护会重新记录当前状态。新安装于另一用户的目标单独记录。用户已删除或 ID 被其它序列号复用时不恢复到新用户。

暂停标记阻止排队中的隔离任务继续操作；隔离和恢复共用文件锁。文件损坏时退出并保留现场，不猜测“默认启用”。状态保存失败时不开始对应修改。恢复失败保留原记录；恢复成功也保留记录供核对。

`uninstall.sh` 只在私有状态目录写入暂停标记并启动恢复。独立的 `service.d` 脚本每次开机只判断一次：模块不存在或被停用时，从保留的 DEX 执行恢复。等待系统服务使用 Binder 的阻塞式服务注册等待，不是重复 sleep。

## 已知边界

- 整包禁用覆盖组件正常入口；它不卸载 APK、不撤销加载到其它进程的代码，也不拦截其它包实施的行为。
- 同 UID 与同进程不是同一概念。后者无法同时做到“立即杀死目标代码”和“绝不影响同进程其它应用”。本分支保留进程并明确提示重启复查。
- 不是抗 Root 对抗工具。其它特权程序可再启用目标、停止观察器或更改原始记录。
- 系统服务死亡时观察器退出，已写入禁用保留；没有用定时重启守护掩盖事件机制失效。
- 本分支保留原项目三个目标，不扩展到所有小米应用或所有统计 SDK。

## 主要实现参考

- [AOSP Android 15 IPackageManager.aidl](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/content/pm/IPackageManager.aidl)
- [PackageMonitorCallbackHelper：回调分发与 UID allowlist](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/services/core/java/com/android/server/pm/PackageMonitorCallbackHelper.java)
- [ProcessList：包级停止的进程选择](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/services/core/java/com/android/server/am/ProcessList.java)
- [KernelSU 模块脚本约定](https://kernelsu.org/guide/module.html)
- `META-INF/com/google/android/update-binary` 来源于 [Magisk 官方安装入口](https://github.com/topjohnwu/Magisk/blob/master/scripts/module_installer.sh)，保留原始内容，项目同为 GPL-3.0。它只负责调用 Root 管理器的 `install_module`。
