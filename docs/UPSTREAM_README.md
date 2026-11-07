# FuckMIUIHelperService!

一个面向小米 HyperOS / MIUI 的 Magisk / KernelSU / SukiSU 通用模块，用于彻底移除并持续压制以下预置组件：

| 显示名 | 包名 |
|---|---|
| SystemHelper | `com.mobiletools.systemhelper` |
| MIUIGuardProvider | `com.miui.guardprovider` |
| MiTrustService | `com.xiaomi.trustservice` |

---

## 目标组件详解：它们做什么，模块为什么干掉它们

### 1. SystemHelper（`com.mobiletools.systemhelper`）

**它做什么**

- 位于 `/system/priv-app/SystemHelper` 与 `/product/pangu/system/priv-app/SystemHelper`，是系统特权预装应用
- 权限：精确定位、后台定位、`READ_PHONE_STATE`、`READ_PRIVILEGED_PHONE_STATE`、`PACKAGE_USAGE_STATS`、`QUERY_ALL_PACKAGES`、WiFi 状态、开机自启
- 集成**百度定位 SDK 9.5.2**（`liblocSDK8b.so`、`libindoor.so`）
- 代码中调用 `getImei` / `getDeviceId` / `getSubscriberId` / `getSimSerialNumber`、WiFi BSSID 与扫描结果、基站信息、已安装应用列表
- 网络域名：`loc.map.baidu.com`、`api.map.baidu.com`、`ofloc.map.baidu.com`、`itsdata.map.baidu.com`

**为什么干掉它**

包名不符合小米常规命名，却拥有系统特权与定位/电话状态权限，持续把位置、基站/WiFi 指纹、设备标识上报到百度 LBS；普通用户无法卸载，也不在系统设置的应用列表里正常展示。

**模块对它执行的操作**

- 开机扫描前用空目录 bind-mount 覆盖上述两个 APK 目录，PackageManager 直接看不到包
- 如果被应用商店/系统恢复装回 `/data/app`：10 秒内 `force-stop` → `pm suspend` → `pm disable-user` → `pm uninstall` → 删除 `/data/app`、`/data/data`、`/data/user*`
- 对其 UID 插入 `iptables` / `ip6tables` 的 OUTPUT/INPUT `DROP`，TCP/UDP/QUIC/DNS/HTTP/HTTPS 全部阻断；包消失 60 秒后自动回收规则

---

### 2. MIUIGuardProvider（`com.miui.guardprovider`）

**它做什么**

- 位于 `/product/app/MIUIGuardProvider`，是小米安全组件（病毒查杀、网址检测）
- 权限：`MANAGE_EXTERNAL_STORAGE`、`RECORD_AUDIO`、`CAPTURE_AUDIO_OUTPUT`、`CAPTURE_VIDEO_OUTPUT`、`MANAGE_MEDIA_PROJECTION`、`WRITE_SECURE_SETTINGS`、`DUMP`、`INTERACT_ACROSS_USERS`、`READ_PRIVILEGED_PHONE_STATE`、`QUERY_ALL_PACKAGES`
- 调用 `getImei` / `getSubscriberId` / `getAndroidId`、已安装/运行应用列表、WiFi BSSID、`Settings.Secure`
- 集成 **小米 OneTrack 统计**（`com.miui.analytics.onetrack`），域名 `tracking.miui.com`、`tracking.intl.miui.com`
- 云查接口：`api.sec.miui.com`、`virus-engine.sec.miui.com`、`flash.sec.miui.com`
- 还带有腾讯 TMSDK 与 AI 视觉 SDK

**为什么干掉它**

它会持续上报统计与云查数据，采集设备标识、已安装应用列表、WiFi 信息；同时权限远超普通杀毒引擎（能录音、录屏、读全部文件），且无法正常卸载/禁用（商店会更新、系统会恢复）。

**模块对它执行的操作**

- 覆盖 `/product/app/MIUIGuardProvider`
- 商店更新版本（`/data/app`）出现时 10 秒内卸载并断网
- 清理其 `/data/data`、`/data/user*`、`/data/app` 残留
- 对其 UID 做全协议 DROP，规则 60 秒后自动回收

---

### 3. MiTrustService（`com.xiaomi.trustservice`）

**它做什么**

- 位于 `/product/app/MiTrustService`，是小米互信/互联服务（多设备协同、查找设备等）
- 权限：`INTERNET`、`BLUETOOTH`、`MANAGE_BIOMETRIC`、`USE_BIOMETRIC`、`USE_FINGERPRINT`、`SYSTEM_ALERT_WINDOW`、`WAKE_LOCK`
- 调用 `getDeviceId`、`getRunningAppProcesses`、`Settings.Secure`
- 网络域名：`deviceapi.micloud.xiaomi.net`、`find.api.micloud.xiaomi.net`
- 与 `com.xiaomi.mi_connect_service`、`com.xiaomi.continuity`、`xms` 等服务联动

**为什么干掉它**

它持有 `INTERNET` 与设备标识读取能力，会向小米云端设备接口上报设备信息；普通用户几乎用不到互联/互信功能，却要承担后台常驻与数据上报的代价。

**模块对它执行的操作**

- 覆盖 `/product/app/MiTrustService`
- 该类包为 **platform 签名 + `android.uid.system` 共享 UID**，普通方式无法重新安装；若通过 OTA 写入系统分区，开机时仍会被 bind-mount 抹除
- 若以其他方式出现，同样执行卸载、清数据、UID 断网

---

## 通用处置逻辑

无论哪个组件，模块都会执行同一套动作：

1. **开机扫描前隐藏**：bind-mount 空目录，系统看不到 APK
2. **装回即清**：10 秒看门狗检测 `/data/app`，命中后强杀、挂起、禁用、卸载、清数据
3. **UID 断网**：`iptables` / `ip6tables` 对目标 UID 的 OUTPUT/INPUT 全协议 DROP
4. **自动收尾**：包消失 60 秒后删规则；日志超过 64 KB 自动截断

---

## 为什么小米商城 / 互联仍能正常打开

本模块是**精确点名清除**，不做全局封杀，因此不会波及其他小米应用：

- **作用域极小**：只处理 SystemHelper、MIUIGuardProvider、MiTrustService 三个包，隐藏它们自己的 APK 目录，并对它们自己的 UID 断网；不修改 `hosts`、不禁用 `com.miui.securitycenter`、不动系统分区
- **不影响依赖方**：`com.xiaomi.market`（小米商城）、`com.miui.mishare.connectivity`（互传）、`com.xiaomi.mi_connect_service`（互联服务）、`com.xiaomi.continuity.sdkapp` 均原样保留
- **"隐藏"而不是"禁用"**：包被 bind-mount 抹除后，应用查询时得到的是“未安装”，会走降级分支；而 `pm disable-user` 只禁用不删除，调用时更容易抛 `SecurityException` / `ComponentNotFound` 导致崩溃
- **无全局域名封锁**：不会屏蔽 `tracking.miui.com`、`api.sec.miui.com`、`micloud.xiaomi.net`，商城的登录、互联的握手链路保持可用

代价是部分能力会降级（GuardProvider 云查、MiTrust 互信校验），但不会让应用打不开。其他模块之所以导致商城/互联无法启动，通常是因为它们同时禁用/删除了安全中心、全局屏蔽了小米域名，或直接改写系统分区。

---

## 工作原理

模块采用四层机制，不依赖“冻结/禁用”（MIUI 会通过系统应用恢复、应用商店更新、OTA 重新启用）：

### 1. 开机扫描前抹除（post-fs-data）

在 PackageManager 扫描应用之前，把目标 APK 所在目录 bind-mount 成一个空目录：

```
/system/priv-app/SystemHelper
/product/app/MIUIGuardProvider
/product/pangu/system/priv-app/SystemHelper
/product/app/MiTrustService
```

同时动态扫描 `/system`、`/system_ext`、`/product`、`/vendor`、`/odm` 下所有匹配目录，一并覆盖。系统启动后**根本看不到这些包**。

### 2. 低开销看门狗（service）

每 10 秒做一次纯文件系统检查（`/data/app/*/*` 目录名匹配），不经过 PackageManager，CPU 占用接近 0。

一旦发现目标包被任何途径装回（应用商店更新、系统恢复、手动安装），立即执行：

```
force-stop → appops 拒绝后台/前台 → pm suspend → pm disable-user
→ pm uninstall → 删除 /data/app、/data/data、/data/user* 残留
→ 重新 bind-mount
```

### 3. netfilter 全协议封禁

拿到目标包 UID 后，在 `iptables` / `ip6tables` 的 OUTPUT / INPUT 链插入：

```
-m owner --uid-owner <uid> -j DROP
```

TCP / UDP / QUIC / DNS / HTTP / HTTPS 全部阻断，进程连域名都解析不了。

### 4. 自动回收与自我保护

- 包被清除 60 秒后，自动删除对应 UID 的 netfilter 规则，避免 UID 复用误伤新应用
- 所有系统调用带 `timeout 5`，查不到 UID / 包体时直接跳过，主循环永不卡死
- 日志超过 64 KB 自动截断，只保留最近 100 行
- 检测日志 60 秒去重，不会刷屏

---

## 安装

1. 下载 Release 中的 `FuckMIUIHelperService-*.zip`
2. 在 Magisk / KernelSU / SukiSU 管理器中「从本地安装」
3. 重启设备

重启后可在模块列表确认模块已启用。

## 卸载

删除模块并重启即可。`uninstall.sh` 会顺带清理 netfilter 规则；即使残留，重启后也会全部消失。

## 日志

```
/data/local/tmp/FuckMIUIHelperService.log
```

正常只记录开机、检测、清理三类事件，体积封顶 64 KB。

## 兼容性

- Android 9 ~ 17（依赖动态分区 + root 方案）
- Magisk / KernelSU / SukiSU（LKM 或 GKI 均可）
- 需要 root 与可用的 `iptables` / `ip6tables`

## 已知影响

- 移除 `MIUIGuardProvider` 后，小米安全中心的部分云查/支付保护功能不可用
- 移除 `MiTrustService` 后，小米互联/多设备协同、查找设备等依赖互信服务的功能可能不可用
- 系统 OTA 升级后模块仍然生效，但若 OTA 改动了分区路径，可能需要更新目录规则

---

## 免责协议 / Disclaimer

**使用本模块即表示你已阅读并同意以下全部内容：**

1. 本模块仅用于**个人自有设备**的系统精简与隐私防护，不得用于任何商业用途或非法目的。
2. 修改系统分区、隐藏/卸载系统应用、修改 netfilter 规则均属于**高风险操作**，可能导致系统功能异常、应用崩溃、无法开机或数据丢失。
3. 使用前请自行备份重要数据与 boot / init_boot 镜像。因使用本模块造成的任何直接或间接损失，**作者与贡献者不承担任何责任**。
4. 本模块按“现状”提供，不附带任何明示或暗示的担保，包括但不限于适销性、特定用途适用性及不侵权担保。
5. 本模块与小米、Redmi、POCO、Google 及其关联公司**没有任何隶属或合作关系**，所有商标归其各自所有者。
6. 若你所在地区法律法规禁止此类系统修改行为，请立即停止使用。
7. 你可以自由修改与分发本模块，但需保留 GPL-3.0 许可证，并自行承担分发带来的全部责任。

**如果你不同意上述任何条款，请立即删除本模块并恢复系统。**

---

## 许可证

[GPL-3.0](LICENSE)
