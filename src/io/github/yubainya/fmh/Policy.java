// SPDX-License-Identifier: GPL-3.0-only
package io.github.yubainya.fmh;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.AtomicFile;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Original per-user facts are committed before the first package-state mutation. */
final class Policy {
  static final String[] TARGETS = {
    "com.mobiletools.systemhelper", "com.miui.guardprovider", "com.xiaomi.trustservice"
  };
  static final File STATE = new File("/data/adb/fmh-safe");
  static final String FORMAT = "fmh-package-state-1";
  final Platform platform;
  final File module;

  Policy(Platform platform, File module) throws Exception {
    this.platform = platform;
    this.module = module;
    if (!STATE.isDirectory() && !STATE.mkdirs())
      throw new IOException("Cannot create private state directory");
  }

  static boolean isTarget(String pkg) {
    for (String target : TARGETS) if (target.equals(pkg)) return true;
    return false;
  }

  boolean paused() {
    return new File(STATE, "paused").exists()
        || !module.isDirectory()
        || new File(module, "disable").exists()
        || new File(module, "remove").exists();
  }

  void pause() throws Exception {
    atomic(new File(STATE, "paused"), "paused\n");
  }

  void resume() throws Exception {
    if (!module.isDirectory()
        || new File(module, "disable").exists()
        || new File(module, "remove").exists())
      throw new IllegalStateException("Module disabled or missing");
    File pause = new File(STATE, "paused");
    if (pause.exists() && !pause.delete()) throw new IOException("Cannot resume module");
  }

  static void atomic(File file, String value) throws Exception {
    AtomicFile a = new AtomicFile(file);
    FileOutputStream out = null;
    try {
      out = a.startWrite();
      out.write(value.getBytes(StandardCharsets.UTF_8));
      a.finishWrite(out);
    } catch (Exception e) {
      if (out != null) a.failWrite(out);
      throw e;
    }
  }

  private JSONObject load() throws Exception {
    AtomicFile file = new AtomicFile(new File(STATE, "baseline.json"));
    if (!file.getBaseFile().exists() && !new File(STATE, "baseline.json.bak").exists())
      return new JSONObject()
          .put("format", FORMAT)
          .put("restored", false)
          .put("entries", new JSONArray());
    byte[] bytes = file.readFully();
    if (bytes.length > 1048576) throw new IOException("Baseline too large");
    JSONObject saved = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    if (!FORMAT.equals(saved.getString("format")))
      throw new IOException("Unsupported baseline format");
    saved.getBoolean("restored");
    JSONArray entries = saved.getJSONArray("entries");
    HashSet<String> seen = new HashSet<>();
    for (int i = 0; i < entries.length(); i++) {
      JSONObject row = entries.getJSONObject(i);
      String pkg = row.getString("package");
      int user = row.getInt("user"), serial = row.getInt("serial"), state = row.getInt("enabled");
      row.getBoolean("stopped");
      if (!isTarget(pkg)
          || user < 0
          || serial < 0
          || state < 0
          || state > 4
          || !seen.add(user + ":" + serial + ":" + pkg))
        throw new IOException("Invalid baseline entry");
    }
    return saved;
  }

  private static JSONObject find(JSONArray rows, String pkg, Platform.User user) throws Exception {
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      if (pkg.equals(row.getString("package"))
          && user.id == row.getInt("user")
          && user.serial == row.getInt("serial")) return row;
    }
    return null;
  }

  private void save(JSONObject value) throws Exception {
    atomic(new File(STATE, "baseline.json"), value.toString(2));
  }

  static final class Result {
    final List<String> lines = new ArrayList<>();
    boolean ok = true;
    int changes;

    void error(String s) {
      ok = false;
      lines.add(s);
    }
  }

  Result enforce() throws Exception {
    try (RandomAccessFile lock = new RandomAccessFile(new File(STATE, "policy.lock"), "rw");
        FileLock held = lock.getChannel().lock()) {
      Result result = new Result();
      if (paused()) {
        result.error("防护已暂停");
        return result;
      }
      JSONObject saved = load();
      if (saved.optBoolean("restored"))
        saved =
            new JSONObject()
                .put("format", FORMAT)
                .put("restored", false)
                .put("entries", new JSONArray());
      JSONArray baseline = saved.getJSONArray("entries");
      List<Platform.User> users = platform.userList();
      boolean added = false;
      // Snapshot the whole current transaction first; a failure here makes no package changes.
      for (Platform.User user : users)
        for (String pkg : TARGETS) {
          ApplicationInfo app = platform.info(pkg, user.id);
          if (app == null) continue;
          if (find(baseline, pkg, user) == null) {
            baseline.put(
                new JSONObject()
                    .put("package", pkg)
                    .put("user", user.id)
                    .put("serial", user.serial)
                    .put("enabled", platform.state(pkg, user.id))
                    .put("stopped", (app.flags & ApplicationInfo.FLAG_STOPPED) != 0));
            added = true;
          }
        }
      if (added) save(saved);
      for (Platform.User user : users)
        for (String pkg : TARGETS) {
          if (paused()) {
            result.error("暂停请求已到达，停止进一步修改");
            return result;
          }
          try {
            ApplicationInfo before = platform.info(pkg, user.id);
            if (before == null) continue;
            if (find(baseline, pkg, user) == null) {
              // Installed during this pass: its queued package event will snapshot it next.
              result.error("新安装目标尚未保存原状态，留待下一次包事件复核：" + pkg);
              continue;
            }
            int enabled = platform.state(pkg, user.id);
            if (enabled != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
              platform.state(pkg, user.id, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER);
              result.changes++;
            }
            if ((before.flags & ApplicationInfo.FLAG_STOPPED) == 0) {
              platform.stopped(pkg, user.id, true);
              result.changes++;
            }
            platform.stopExclusive(pkg, user.id);
            ApplicationInfo after = platform.info(pkg, user.id);
            if (after == null
                || platform.state(pkg, user.id)
                    != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                || (after.flags & ApplicationInfo.FLAG_STOPPED) == 0
                || platform.hasProcess(pkg, user.id))
              result.error("尚未完全隔离：user=" + user.id + " " + pkg);
          } catch (Exception e) {
            result.error(
                "隔离未完成：user="
                    + user.id
                    + " "
                    + pkg
                    + " · "
                    + e.getClass().getSimpleName()
                    + ": "
                    + e.getMessage());
          }
        }
      return result;
    }
  }

  Result restore() throws Exception {
    pause();
    try (RandomAccessFile lock = new RandomAccessFile(new File(STATE, "policy.lock"), "rw");
        FileLock held = lock.getChannel().lock()) {
      Result result = new Result();
      JSONObject saved = load();
      if (saved.optBoolean("restored")) return result;
      JSONArray rows = saved.getJSONArray("entries");
      List<Platform.User> current = platform.userList();
      for (int i = 0; i < rows.length(); i++) {
        JSONObject row = rows.getJSONObject(i);
        String pkg = row.getString("package");
        int user = row.getInt("user"), serial = row.getInt("serial");
        boolean sameUser = false;
        for (Platform.User u : current) if (u.id == user && u.serial == serial) sameUser = true;
        if (!sameUser || platform.info(pkg, user) == null)
          continue; // Never recreate a user or reinstall an app.
        try {
          int wanted = row.getInt("enabled");
          boolean stopped = row.getBoolean("stopped");
          if (platform.state(pkg, user) != wanted) {
            platform.state(pkg, user, wanted);
            result.changes++;
          }
          ApplicationInfo info = platform.info(pkg, user);
          if (((info.flags & ApplicationInfo.FLAG_STOPPED) != 0) != stopped) {
            platform.stopped(pkg, user, stopped);
            result.changes++;
          }
          ApplicationInfo check = platform.info(pkg, user);
          if (platform.state(pkg, user) != wanted
              || ((check.flags & ApplicationInfo.FLAG_STOPPED) != 0) != stopped)
            result.error("恢复尚未确认：user=" + user + " " + pkg);
        } catch (Exception e) {
          result.error("恢复未完成：user=" + user + " " + pkg + " · " + e.getClass().getSimpleName());
        }
      }
      if (result.ok) {
        saved.put("restored", true);
        save(saved);
      }
      return result;
    }
  }

  JSONObject status() throws Exception {
    JSONObject result = new JSONObject().put("format", FORMAT).put("paused", paused());
    JSONArray rows = new JSONArray();
    for (Platform.User u : platform.userList())
      for (String pkg : TARGETS) {
        ApplicationInfo a = platform.info(pkg, u.id);
        if (a == null) continue;
        rows.put(
            new JSONObject()
                .put("package", pkg)
                .put("user", u.id)
                .put("serial", u.serial)
                .put("uid", a.uid)
                .put("enabled", platform.state(pkg, u.id))
                .put("stopped", (a.flags & ApplicationInfo.FLAG_STOPPED) != 0)
                .put("running", platform.hasProcess(pkg, u.id))
                .put("foreignSharedProcess", platform.hasForeignHost(pkg, u.id)));
      }
    boolean observer;
    try (RandomAccessFile f = new RandomAccessFile(new File(STATE, "observer.lock"), "rw")) {
      FileLock test = f.getChannel().tryLock();
      observer = test == null;
      if (test != null) test.release();
    }
    return result.put("observerRunning", observer).put("packages", rows);
  }
}
