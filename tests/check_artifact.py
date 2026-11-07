#!/usr/bin/env python3
"""Verify delivered ZIP boundaries and compile-only class exclusion."""
import pathlib, zipfile, struct, subprocess, hashlib, re
root=pathlib.Path(__file__).resolve().parents[1]
version=next(x.split('=',1)[1] for x in (root/'module.prop').read_text().splitlines() if x.startswith('version='))
path=root/'dist'/f'FuckMIUIHelperService-SafeStrict-{version}.zip'
expected={'module.prop','controller.dex','post-fs-data.sh','service.sh','run.sh','action.sh','customize.sh','uninstall.sh','restore-on-boot.sh','README.md','LICENSE','docs/DESIGN.md','docs/VALIDATION.md','META-INF/com/google/android/update-binary','META-INF/com/google/android/updater-script'}
with zipfile.ZipFile(path) as z:
    assert set(z.namelist())==expected
    assert z.testzip() is None
    for name in expected:
        assert z.read(name)==(root/name).read_bytes(),name
        if name.endswith('.sh') or name.endswith('update-binary'):
            subprocess.run(['sh','-n',str(root/name)],check=True)
            assert (z.getinfo(name).external_attr>>16)&0o111,name
    dex=z.read('controller.dex')
# Parse DEX class definitions, not string references to platform interfaces.
def u32(offset):return struct.unpack_from('<I',dex,offset)[0]
strings=[]
for i in range(u32(56)):
    p=u32(u32(60)+4*i)
    while dex[p]&128:p+=1
    p+=1
    strings.append(dex[p:dex.index(b'\0',p)].decode('utf-8',errors='replace'))
types=[strings[u32(u32(68)+4*i)] for i in range(u32(64))]
classes=[types[u32(u32(100)+32*i)] for i in range(u32(96))]
assert classes and all(x.startswith('Lio/github/yubainya/fmh/') for x in classes),classes
sources='\n'.join(p.read_text() for p in (root/'src').rglob('*.java'))+'\n'+'\n'.join(p.read_text() for p in root.glob('*.sh'))
for forbidden in ['iptables','ip6tables','pm uninstall','pm clear','pkill','killall','Thread.sleep','ScheduledExecutor','acquireWakeLock','WakeLock','rm -rf','mount --bind']:
    assert forbidden not in sources,forbidden
checksum=path.with_suffix('.zip.sha256').read_text().split()[0]
assert checksum==hashlib.sha256(path.read_bytes()).hexdigest()
print('PASS ZIP whitelist, exact packaged content, shell syntax/modes, DEX class ownership, unsafe-operation scan, SHA-256')
print(f'{len(classes)} own DEX classes; {path.stat().st_size} ZIP bytes')
