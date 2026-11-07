#!/usr/bin/env python3
"""Build only first-party classes; hidden API shapes are compile-only."""
import os, pathlib, subprocess, zipfile, hashlib, shutil
ROOT=pathlib.Path(__file__).resolve().parents[1]
JDK=pathlib.Path(os.environ['JAVA_HOME'])
SDK=pathlib.Path(os.environ['ANDROID_HOME'])
BT=pathlib.Path(os.environ.get('ANDROID_BUILD_TOOLS',str(SDK/'build-tools/35.0.0')))
BUILD=ROOT/'build'; BUILD.mkdir(exist_ok=True)
def run(*cmd): subprocess.run([str(x) for x in cmd],check=True)
for folder in ('stubs','classes','dex'):
    path=BUILD/folder
    if path.exists(): shutil.rmtree(path)
    path.mkdir()
android=SDK/'platforms/android-35/android.jar'
run(JDK/'bin/javac','--release','11','-cp',android,'-d',BUILD/'stubs',*sorted((ROOT/'compile-stubs').rglob('*.java')))
run(JDK/'bin/javac','--release','11','-cp',f'{android}:{BUILD/"stubs"}','-d',BUILD/'classes',*sorted((ROOT/'src').rglob('*.java')))
run(JDK/'bin/java','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--min-api','28','--lib',android,'--classpath',BUILD/'stubs','--output',BUILD/'dex',*sorted((BUILD/'classes').rglob('*.class')))
shutil.copyfile(BUILD/'dex/classes.dex',ROOT/'controller.dex')
print('Built controller.dex',hashlib.sha256((ROOT/'controller.dex').read_bytes()).hexdigest())

# Reproducible archive metadata; no private diagnostics, fixture APKs, or build tools.
version=next(line.split('=',1)[1] for line in (ROOT/'module.prop').read_text().splitlines() if line.startswith('version='))
files=['module.prop','controller.dex','post-fs-data.sh','service.sh','run.sh','action.sh','customize.sh','uninstall.sh','restore-on-boot.sh','README.md','LICENSE','docs/DESIGN.md','docs/VALIDATION.md','META-INF/com/google/android/update-binary','META-INF/com/google/android/updater-script']
output=ROOT/'dist'/f'FuckMIUIHelperService-SafeStrict-{version}.zip';output.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as archive:
    for name in sorted(files):
        info=zipfile.ZipInfo(name,(2000,1,1,0,0,0));info.create_system=3
        info.external_attr=(0o100755 if name.endswith('.sh') or name.endswith('update-binary') else 0o100644)<<16
        info.compress_type=zipfile.ZIP_DEFLATED
        archive.writestr(info,(ROOT/name).read_bytes())
digest=hashlib.sha256(output.read_bytes()).hexdigest()
output.with_suffix('.zip.sha256').write_text(f'{digest}  {output.name}\n')
print(output.name,digest)
