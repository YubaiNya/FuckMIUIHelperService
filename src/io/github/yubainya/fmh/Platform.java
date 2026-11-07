// SPDX-License-Identifier: GPL-3.0-only
package io.github.yubainya.fmh;

import android.app.ActivityManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.IRemoteCallback;
import java.lang.reflect.*;
import java.util.*;

/**
 * Root-only, package-scoped Android operations. No firewall, UID kills, file hiding or deletion.
 */
final class Platform {
  final Object pm, am, um;
  final Method application,
      enabled,
      setEnabled,
      setStopped,
      forceStop,
      running,
      users,
      register,
      unregister;

  Platform() throws Exception {
    try {
      Class<?> vm = Class.forName("dalvik.system.VMRuntime");
      Object runtime = vm.getMethod("getRuntime").invoke(null);
      vm.getMethod("setHiddenApiExemptions", String[].class)
          .invoke(runtime, (Object) new String[] {"Landroid/"});
    } catch (ReflectiveOperationException ignored) {
    }
    pm = service("package", "android.content.pm.IPackageManager");
    um = service("user", "android.os.IUserManager");
    am = service("activity", "android.app.IActivityManager");
    Class<?> p = Class.forName("android.content.pm.IPackageManager"),
        u = Class.forName("android.os.IUserManager"),
        a = Class.forName("android.app.IActivityManager");
    application = p.getMethod("getApplicationInfo", String.class, long.class, int.class);
    enabled = p.getMethod("getApplicationEnabledSetting", String.class, int.class);
    setEnabled =
        p.getMethod(
            "setApplicationEnabledSetting",
            String.class,
            int.class,
            int.class,
            int.class,
            String.class);
    setStopped = p.getMethod("setPackageStoppedState", String.class, boolean.class, int.class);
    forceStop = a.getMethod("forceStopPackage", String.class, int.class);
    running = a.getMethod("getRunningAppProcesses");
    users = u.getMethod("getUsers", boolean.class, boolean.class, boolean.class);
    register = p.getMethod("registerPackageMonitorCallback", IRemoteCallback.class, int.class);
    unregister = p.getMethod("unregisterPackageMonitorCallback", IRemoteCallback.class);
  }

  private static Object service(String name, String iface) throws Exception {
    // Native Binder service registration wait: no timer or recurring boot polling.
    Class<?> sm = Class.forName("android.os.ServiceManager");
    IBinder binder = (IBinder) sm.getMethod("waitForService", String.class).invoke(null, name);
    if (binder == null) throw new IllegalStateException("System service unavailable: " + name);
    return Class.forName(iface + "$Stub")
        .getMethod("asInterface", IBinder.class)
        .invoke(null, binder);
  }

  static Object call(Method method, Object owner, Object... args) throws Exception {
    try {
      return method.invoke(owner, args);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) throw (Exception) cause;
      throw e;
    }
  }

  static final class User {
    final int id, serial;

    User(int id, int serial) {
      this.id = id;
      this.serial = serial;
    }
  }

  List<User> userList() throws Exception {
    List<?> list = (List<?>) call(users, um, true, true, true);
    ArrayList<User> result = new ArrayList<>();
    for (Object user : list) {
      Class<?> c = user.getClass();
      int id = c.getField("id").getInt(user), serial = c.getField("serialNumber").getInt(user);
      if (id >= 0 && serial >= 0) result.add(new User(id, serial));
    }
    if (result.isEmpty()) throw new IllegalStateException("No usable Android users");
    return result;
  }

  ApplicationInfo info(String pkg, int user) throws Exception {
    return (ApplicationInfo) call(application, pm, pkg, 0L, user);
  }

  int state(String pkg, int user) throws Exception {
    return (Integer) call(enabled, pm, pkg, user);
  }

  void state(String pkg, int user, int value) throws Exception {
    // Avoid an implicit process kill before shared-process inspection.
    call(setEnabled, pm, pkg, value, PackageManager.DONT_KILL_APP, user, "fmh-safe-root");
  }

  void stopped(String pkg, int user, boolean value) throws Exception {
    call(setStopped, pm, pkg, value, user);
  }

  List<ActivityManager.RunningAppProcessInfo> processes() throws Exception {
    @SuppressWarnings("unchecked")
    List<ActivityManager.RunningAppProcessInfo> list =
        (List<ActivityManager.RunningAppProcessInfo>) call(running, am);
    if (list == null) throw new IllegalStateException("Cannot inspect running app processes");
    return list;
  }

  boolean hasForeignHost(String pkg, int user) throws Exception {
    for (ActivityManager.RunningAppProcessInfo process : processes()) {
      if (process.uid / 100000 != user
          || process.pkgList == null
          || !Arrays.asList(process.pkgList).contains(pkg)) continue;
      if (process.pid == 1
          || "system".equals(process.processName)
          || "system_server".equals(process.processName)) return true;
      for (String hosted : process.pkgList) if (!Policy.isTarget(hosted)) return true;
    }
    return false;
  }

  boolean hasProcess(String pkg, int user) throws Exception {
    for (ActivityManager.RunningAppProcessInfo process : processes())
      if (process.uid / 100000 == user
          && process.pkgList != null
          && Arrays.asList(process.pkgList).contains(pkg)) return true;
    return false;
  }

  void stopExclusive(String pkg, int user) throws Exception {
    if (hasForeignHost(pkg, user))
      throw new IllegalStateException("目标与非目标应用共用进程；已禁止新启动，保留现有共享进程，需重启完成隔离");
    if (hasProcess(pkg, user)) call(forceStop, am, pkg, user);
  }

  void register(IRemoteCallback callback) throws Exception {
    // AOSP filters per-package callback allowlists for UID 0, but explicitly exempts
    // SYSTEM_UID. Register this root process's callback with effective UID 1000;
    // restore root immediately. No app UID, permission, or global setting changes.
    android.system.Os.seteuid(1000);
    try {
      call(register, pm, callback, -1);
    } finally {
      android.system.Os.seteuid(0);
    }
  }

  void unregister(IRemoteCallback callback) throws Exception {
    call(unregister, pm, callback);
  }
}
