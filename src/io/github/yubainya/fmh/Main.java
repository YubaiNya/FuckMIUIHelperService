// SPDX-License-Identifier: GPL-3.0-only
package io.github.yubainya.fmh;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.*;
import java.io.*;
import java.nio.channels.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** No timers, wake locks, sockets, or recurring process scans. */
public final class Main {
  static final AtomicBoolean queued = new AtomicBoolean(), dirty = new AtomicBoolean();
  static final ExecutorService worker = Executors.newSingleThreadExecutor();
  static final CountDownLatch finished = new CountDownLatch(1);
  static volatile boolean closing;
  static volatile boolean failed;
  static volatile boolean acceptingEvents;
  static Policy policy;
  static String callbackKey;

  static void log(String message) {
    System.out.println(message);
    try {
      File f = new File(Policy.STATE, "observer.log");
      if (f.length() > 65536) {
        File old = new File(Policy.STATE, "observer.previous.log");
        if (old.exists()) old.delete();
        f.renameTo(old);
      }
      try (FileWriter w = new FileWriter(f, true)) {
        w.write(System.currentTimeMillis() + " " + message + "\n");
      }
    } catch (IOException ignored) {
    }
  }

  static void report(Policy.Result result, String operation) {
    if (result.changes > 0) log(operation + "：修改 " + result.changes + " 项状态");
    for (String line : result.lines) log(line);
  }

  static void request() {
    if (closing) return;
    dirty.set(true);
    if (!queued.compareAndSet(false, true)) return;
    worker.execute(
        () -> {
          try {
            while (dirty.getAndSet(false) && !closing) {
              if (policy.paused()) {
                closing = true;
                report(policy.restore(), "恢复");
                finished.countDown();
                break;
              }
              report(policy.enforce(), "隔离");
            }
          } catch (Exception e) {
            log("错误：" + e);
            failed = true;
            closing = true;
            finished.countDown();
          } finally {
            queued.set(false);
            if (dirty.get() && !closing) request();
          }
        });
  }

  static IRemoteCallback callback() {
    return new IRemoteCallback.Stub() {
      @Override
      public void sendResult(Bundle bundle) {
        if (!acceptingEvents) return; // probe is read-only; initial pass covers registration races.
        int caller = Binder.getCallingUid();
        if (caller != 1000 && caller != 0) return;
        try {
          Intent intent = bundle.getParcelable(callbackKey, Intent.class);
          if (intent != null
              && intent.getData() != null
              && Policy.isTarget(intent.getData().getSchemeSpecificPart())) request();
        } catch (RuntimeException e) {
          log("忽略无法解析的系统包事件");
        }
      }
    };
  }

  @SuppressWarnings("deprecation")
  static FileObserver observe(File path) {
    FileObserver observer =
        new FileObserver(
            path.getAbsolutePath(),
            FileObserver.CREATE
                | FileObserver.DELETE
                | FileObserver.MOVED_TO
                | FileObserver.DELETE_SELF
                | FileObserver.MOVE_SELF) {
          public void onEvent(int event, String name) {
            if (name == null
                || "disable".equals(name)
                || "remove".equals(name)
                || "paused".equals(name)
                || "paused.new".equals(name)) request();
          }
        };
    observer.startWatching();
    return observer;
  }

  static void watch(Platform platform) throws Exception {
    try (RandomAccessFile f = new RandomAccessFile(new File(Policy.STATE, "observer.lock"), "rw");
        FileLock held = f.getChannel().tryLock()) {
      if (held == null) throw new IllegalStateException("Observer already running");
      if (policy.paused()) {
        // watch honors a manual pause. service.sh explicitly resumes at the next enabled boot.
        log("模块已暂停；未启动观察器");
        return;
      }
      IRemoteCallback cb = callback();
      platform.register(cb);
      IBinder remote = ((IInterface) platform.pm).asBinder();
      IBinder.DeathRecipient death =
          () -> {
            log("PackageManager 已退出；事件防护停止，已禁用状态保留");
            failed = true;
            closing = true;
            finished.countDown();
          };
      remote.linkToDeath(death, 0);
      FileObserver module = observe(policy.module), state = observe(Policy.STATE);
      log("事件观察器已启动 pid=" + android.os.Process.myPid());
      try {
        acceptingEvents = true;
        request();
        finished.await();
      } finally {
        acceptingEvents = false;
        module.stopWatching();
        state.stopWatching();
        remote.unlinkToDeath(death, 0);
        if (remote.isBinderAlive()) platform.unregister(cb);
      }
    }
  }

  static void readable(JSONObject status) throws Exception {
    System.out.println("严格屏蔽 · 保留应用数据");
    System.out.println("事件观察器：" + (status.getBoolean("observerRunning") ? "正在运行" : "未运行"));
    System.out.println("防护开关：" + (status.getBoolean("paused") ? "已暂停" : "已启用"));
    JSONArray rows = status.getJSONArray("packages");
    if (rows.length() == 0) System.out.println("当前用户下没有安装目标组件");
    for (int i = 0; i < rows.length(); i++) {
      JSONObject r = rows.getJSONObject(i);
      String pkg = r.getString("package");
      String name =
          pkg.equals(Policy.TARGETS[0])
              ? "系统助手"
              : pkg.equals(Policy.TARGETS[1]) ? "安全服务 GuardProvider" : "设备信任服务 MiTrust";
      boolean blocked =
          r.getInt("enabled") == 3 && r.getBoolean("stopped") && !r.getBoolean("running");
      System.out.println(
          name + " · 用户 " + r.getInt("user") + "：" + (blocked ? "已禁止启动，进程已停止" : "尚未完全隔离"));
      if (r.getBoolean("foreignSharedProcess")) System.out.println("  与其它应用共用进程；没有强杀，请重启完成隔离。");
    }
  }

  public static void main(String[] args) {
    int code = 0;
    try {
      if (android.os.Process.myUid() != 0) throw new SecurityException("Must run as root");
      if (Build.VERSION.SDK_INT < 35)
        throw new IllegalStateException("Requires Android 15 / API 35 or newer");
      String mode = args.length > 0 ? args[0] : "status";
      File module =
          new File(args.length > 1 ? args[1] : "/data/adb/modules/fuckmiuihelperservice_safe");
      Platform platform = new Platform();
      callbackKey =
          (String) PackageManager.class.getField("EXTRA_PACKAGE_MONITOR_CALLBACK_RESULT").get(null);
      if ("probe".equals(mode)) {
        IRemoteCallback cb = callback();
        platform.register(cb);
        platform.unregister(cb);
        platform.userList();
        platform.processes();
        System.out.println("OK: root package monitor API available");
      } else {
        policy = new Policy(platform, module);
        switch (mode) {
          case "watch":
            watch(platform);
            break;
          case "apply":
            {
              Policy.Result r = policy.enforce();
              report(r, "隔离");
              if (!r.ok) code = 2;
              break;
            }
          case "restore":
            {
              Policy.Result r = policy.restore();
              report(r, "恢复");
              if (!r.ok) code = 2;
              break;
            }
          case "resume":
            policy.resume();
            watch(platform);
            break;
          case "status-json":
            System.out.println(policy.status().toString());
            break;
          case "status":
            readable(policy.status());
            break;
          default:
            throw new IllegalArgumentException("Unknown command: " + mode);
        }
      }
    } catch (Exception e) {
      System.err.println("FMH Safe failed: " + e);
      code = 1;
    } finally {
      worker.shutdownNow();
    }
    System.exit(failed ? 1 : code);
  }
}
