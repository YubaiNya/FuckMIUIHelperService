#!/usr/bin/env python3
"""Destructive ONLY to throwaway test fixtures on an explicitly named root emulator."""
import argparse, pathlib, subprocess, os, sys, json, time, hashlib, zipfile, importlib.util
ROOT=pathlib.Path(__file__).resolve().parents[1]
SDK=pathlib.Path(os.environ['ANDROID_HOME'])
JDK=pathlib.Path(os.environ['JAVA_HOME'])
BT=pathlib.Path(os.environ.get('ANDROID_BUILD_TOOLS',str(SDK/'build-tools/35.0.0')))
parser=argparse.ArgumentParser();parser.add_argument('--serial',required=True);args=parser.parse_args()
if not args.serial.startswith('emulator-'):sys.exit('Only an explicitly named disposable emulator is accepted')
ADB=[str(SDK/'platform-tools/adb'),'-s',args.serial]
def run(cmd,check=True):return subprocess.run([str(x) for x in cmd],check=check,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True).stdout.strip()
def adb(*cmd):return run(ADB+list(cmd))
def sh(cmd):return adb('shell',cmd)
if sh('id -u')!='0' or sh('getprop ro.kernel.qemu')!='1':sys.exit('Requires a root disposable emulator')
BUILD=ROOT/'build/fixtures';BUILD.mkdir(exist_ok=True)
key=ROOT/'.tools/fixture.keystore'
if not key.exists():run([JDK/'bin/keytool','-genkeypair','-keystore',key,'-storepass','fixture-only','-keypass','fixture-only','-alias','fixture','-keyalg','RSA','-validity','3650','-dname','CN=Disposable FMH Emulator Fixture'])
java=BUILD/'Activity.java';java.write_text('''package io.fmh.fixture;
public class Activity extends android.app.Activity {
 public void onCreate(android.os.Bundle b){super.onCreate(b);try{java.io.File f=new java.io.File(getFilesDir(),"preserved.txt");if(!f.exists()){try(java.io.FileOutputStream o=new java.io.FileOutputStream(f)){o.write(("PRESERVE:"+getPackageName()).getBytes());}}}catch(Exception e){throw new RuntimeException(e);}android.widget.TextView t=new android.widget.TextView(this);t.setText(getPackageName());setContentView(t);}
}''')
classes=BUILD/'classes';classes.mkdir(exist_ok=True);dex=BUILD/'dex';dex.mkdir(exist_ok=True)
android=SDK/'platforms/android-35/android.jar'
run([JDK/'bin/javac','--release','11','-cp',android,'-d',classes,java])
run([JDK/'bin/java','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--min-api','28','--lib',android,'--output',dex,*classes.rglob('*.class')])
def apk(pkg,shared=False,process=None,version=1):
    manifest=BUILD/'AndroidManifest.xml'
    manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{pkg}" android:versionCode="{version}" android:versionName="{version}" {'android:sharedUserId="io.fmh.shared"' if shared else ''}><uses-sdk android:minSdkVersion="28" android:targetSdkVersion="28"/><application android:label="FMH test fixture" android:debuggable="true" {'android:process="'+process+'"' if process else ''}><activity android:name="io.fmh.fixture.Activity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>''')
    raw=BUILD/'raw.apk';aligned=BUILD/'aligned.apk';out=BUILD/f'{pkg}-{version}.apk'
    run([BT/'aapt2','link','-o',raw,'--manifest',manifest,'-I',android])
    with zipfile.ZipFile(raw,'a') as z:z.write(dex/'classes.dex','classes.dex')
    run([BT/'zipalign','-f','4',raw,aligned])
    run([JDK/'bin/java','-jar',BT/'lib/apksigner.jar','sign','--ks',key,'--ks-pass','pass:fixture-only','--ks-key-alias','fixture','--out',out,aligned])
    return out
T=['com.mobiletools.systemhelper','com.miui.guardprovider','com.xiaomi.trustservice'];S='io.fmh.sibling';MOD='/data/adb/modules/fuckmiuihelperservice_safe';STATE='/data/adb/fmh-safe'
def cli(mode):return sh(f'CLASSPATH={MOD}/controller.dex app_process /system/bin io.github.yubainya.fmh.Main {mode} {MOD}')
def status():return json.loads(cli('status-json'))
def start(pkg):return sh(f'am start -W -n {pkg}/io.fmh.fixture.Activity')
def pid(pkg):return sh('pidof '+pkg)
def wait(check,timeout=8):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        value=check()
        if value:return value
        time.sleep(.15)
    raise AssertionError('Timed out waiting for event')
def record(name,detail=''): print('PASS',name,detail,flush=True)
sh(f'mkdir -p {MOD} {STATE}; chmod 700 {STATE}')
adb('push',str(ROOT/'controller.dex'),MOD+'/controller.dex')
print(cli('probe'),flush=True)
# On reruns, restore previous fixture state and stop prior observer via its own pause flag.
if sh(f'test -f {STATE}/baseline.json && echo yes; true')=='yes':cli('restore');wait(lambda:not status()['observerRunning'])
sh(f'rm -f {STATE}/paused {MOD}/disable {MOD}/remove')
for pkg in T+[S]:
    print(adb('install','--no-incremental','-r','-d',str(apk(pkg,pkg in [T[2],S]))),flush=True)
    sh(f'pm default-state --user 0 {pkg}');start(pkg)
# Explicit enabled-state input must be restored as 1, not guessed default=0.
sh(f'pm enable --user 0 {T[1]}')
before=status()['packages']
markers={p:sh(f'cat /data/user/0/{p}/files/preserved.txt') for p in T+[S]}
apks={p:sh(f'sha256sum $(pm path {p} | head -n 1 | cut -d: -f2)') for p in T}
uidt=sh(f'cmd package list packages -U {T[2]}');uids=sh(f'cmd package list packages -U {S}')
import re
assert re.search(r'uid:(\d+)',uidt)[1]==re.search(r'uid:(\d+)',uids)[1]
sibling=pid(S);assert sibling
def firewall_rules():
    # iptables-save includes timestamps and changing policy packet counters.
    return '\n'.join(re.sub(r'\[\d+:\d+\]','[COUNTERS]',line) for line in sh('iptables-save; ip6tables-save').splitlines() if not line.startswith('#'))
firewall=firewall_rules()
watch=subprocess.Popen(ADB+['shell',f'CLASSPATH={MOD}/controller.dex app_process /system/bin io.github.yubainya.fmh.Main watch {MOD}'],stdout=(BUILD/'watch.log').open('w'),stderr=subprocess.STDOUT)
wait(lambda:status()['observerRunning'])
wait(lambda:all(r['enabled']==3 and r['stopped'] and not r['running'] for r in status()['packages']))
assert pid(S)==sibling,(sibling,pid(S))
assert firewall==firewall_rules()
record('shared UID sibling survives; firewall unchanged')
assert all(sh(f'cat /data/user/0/{p}/files/preserved.txt')==markers[p] for p in T+[S])
assert all(sh(f'sha256sum $(pm path {p} | head -n 1 | cut -d: -f2)')==apks[p] for p in T)
record('all target APKs and private marker files retained')
baseline=sh(f'cat {STATE}/baseline.json')
t0=time.monotonic();sh(f'pm enable --user 0 {T[1]}');wait(lambda:all(r['enabled']==3 for r in status()['packages']));latency=time.monotonic()-t0
assert sh(f'cat {STATE}/baseline.json')==baseline
record('package enable event automatically blocked',f'{latency:.3f}s including CLI startup')
# Idle measurement uses /proc CPU counters; no claim about physical-device energy.
observer=int(re.search(r'pid=(\d+)',(BUILD/'watch.log').read_text())[1])
def ticks():
    fields=sh(f'cat /proc/{observer}/stat').split(') ',1)[1].split();return int(fields[11])+int(fields[12])
ticks0=ticks();print('Measuring idle CPU for 30 seconds...',flush=True);time.sleep(30);ticks1=ticks()
record('idle CPU sample',f'30s delta={ticks1-ticks0} jiffies; CLK_TCK={sh("getconf CLK_TCK")}')
print(sh(f'dumpsys meminfo {observer} | head -n 30'),flush=True)
sh(f'touch {MOD}/disable');wait(lambda:not status()['observerRunning'])
after=status()['packages'];assert [(r['package'],r['enabled'],r['stopped']) for r in after]==[(r['package'],r['enabled'],r['stopped']) for r in before],(before,after)
assert pid(S)==sibling
record('module disable restores exact enabled/stopped values; sibling unaffected')
assert all(sh(f'cat /data/user/0/{p}/files/preserved.txt')==markers[p] for p in T+[S])
print('CORE INTEGRATION PASS',flush=True)

def launch():
    sh(f'rm -f {MOD}/disable {MOD}/remove')
    process=subprocess.Popen(ADB+['shell',f'CLASSPATH={MOD}/controller.dex app_process /system/bin io.github.yubainya.fmh.Main resume {MOD}'],stdout=(BUILD/'extended-watch.log').open('a'),stderr=subprocess.STDOUT)
    wait(lambda:status()['observerRunning']);return process

watch=launch()
wait(lambda:all(r['enabled']==3 for r in status()['packages']))
print(adb('install','--no-incremental','-r',str(apk(T[1],version=2))),flush=True)
wait(lambda:all(r['enabled']==3 and r['stopped'] for r in status()['packages']))
assert sh(f'cat /data/user/0/{T[1]}/files/preserved.txt')==markers[T[1]]
record('target APK update stays disabled and retains private data')
created=sh('pm create-user FMHDisposableFixture');user=int(re.search(r'created user id (\d+)',created)[1])
sh(f'cmd package install-existing --user {user} {T[0]}')
wait(lambda:any(r['user']==user and r['package']==T[0] and r['enabled']==3 and r['stopped'] for r in status()['packages']))
record('new Android user target installation automatically isolated',f'user={user}')
cli('restore');wait(lambda:not status()['observerRunning'])
row=next(r for r in status()['packages'] if r['user']==user and r['package']==T[0]);assert row['enabled']==0
sh(f'pm remove-user {user}')
record('new user original state restored; disposable user removed')

# A shared UID is allowed; a live shared process must never be killed wholesale.
print(adb('install','--no-incremental','-r',str(apk(T[2],shared=True,process=S,version=2))),flush=True)
start(S);start(T[2]);shared_pid=pid(S)
row=next(r for r in status()['packages'] if r['package']==T[2] and r['user']==0)
assert row['foreignSharedProcess'],row
watch=launch()
wait(lambda:all(r['enabled']==3 for r in status()['packages']))
row=next(r for r in status()['packages'] if r['package']==T[2] and r['user']==0)
assert pid(S)==shared_pid and row['running'] and row['foreignSharedProcess'],row
assert '尚未完全隔离' in cli('status')
record('shared process kept alive; status reports incomplete isolation')
cli('restore');wait(lambda:not status()['observerRunning'])

# Corrupt restoration facts must not be replaced by a new guess.
baseline_path=BUILD/'baseline-valid.json';adb('pull',STATE+'/baseline.json',str(baseline_path))
invalid=BUILD/'baseline-invalid.json';invalid.write_text('{"format":"invalid","entries":[]}')
adb('push',str(invalid),STATE+'/baseline.json');sh(f'rm -f {STATE}/paused')
original=status()['packages']
bad=subprocess.run(ADB+['shell',f'CLASSPATH={MOD}/controller.dex app_process /system/bin io.github.yubainya.fmh.Main apply {MOD}'],capture_output=True,text=True)
assert bad.returncode!=0
assert status()['packages']==original
adb('push',str(baseline_path),STATE+'/baseline.json')
record('corrupt baseline rejected without changing any package')

# Recovery still works after the module directory is gone (offline removal case).
watch=launch();wait(lambda:all(r['enabled']==3 for r in status()['packages']))
adb('push',str(ROOT/'controller.dex'),STATE+'/controller.dex')
adb('push',str(ROOT/'restore-on-boot.sh'),STATE+'/restore-on-boot-test.sh')
sh(f'mv {MOD} {MOD}.test-removed')
sh(f'sh {STATE}/restore-on-boot-test.sh')
wait(lambda:json.loads(sh(f'cat {STATE}/baseline.json'))['restored'])
sh(f'mv {MOD}.test-removed {MOD}')
wait(lambda:not status()['observerRunning'])
assert status()['packages']==original
record('retained controller restores states when module directory is missing')
print('ALL INTEGRATION CHECKS PASS',flush=True)
