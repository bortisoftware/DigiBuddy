"""Build ARM64 APKs with workspace-local Android tools (Python 3.11+, Windows)."""
import argparse
import hashlib
import json
import os
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
OUT = ROOT / 'build'
ANDROID = '{http://schemas.android.com/apk/res/android}'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--mode', choices=['local', 'debug', 'release'], default='local',
                        help='local disables debugging but uses a development signing key')
    args = parser.parse_args()
    java = next((TOOLS / 'java').glob('*/bin/java.exe')).parent
    build = next((TOOLS / 'sdk/build-tools').glob('*/aapt.exe')).parent
    platform = next((TOOLS / 'sdk/platforms').glob('*/android.jar'))
    llvm = next((TOOLS / 'ndk').glob('*/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe')).parent
    env = dict(os.environ)
    env['JAVA_HOME'] = str(java.parent)
    env['PATH'] = str(java) + os.pathsep + env['PATH']

    if args.mode == 'release':
        key = Path(env.get('DIGIMAP_KEYSTORE', ''))
        alias = env.get('DIGIMAP_KEY_ALIAS', '')
        if not key.is_file() or not alias or not env.get('DIGIMAP_STORE_PASSWORD') or not env.get('DIGIMAP_KEY_PASSWORD'):
            raise SystemExit('Release requires DIGIMAP_KEYSTORE, DIGIMAP_KEY_ALIAS, DIGIMAP_STORE_PASSWORD and DIGIMAP_KEY_PASSWORD. No secrets are printed.')
        store_pass = 'env:DIGIMAP_STORE_PASSWORD'
        key_pass = 'env:DIGIMAP_KEY_PASSWORD'
    else:
        key = TOOLS / 'development.keystore'
        alias = 'digimap'
        store_pass = key_pass = 'pass:android'

    def run(*arguments):
        print('Running ' + Path(str(arguments[0])).name, flush=True)
        subprocess.run([str(arg) for arg in arguments], cwd=ROOT, env=env, check=True)

    native = ROOT / 'app/src/main/jniLibs/arm64-v8a'
    libraries = ['libdigimap.so', 'libswanstation.so', 'libpcsx_rearmed.so']
    for name in libraries[1:]:
        if not (native / name).is_file():
            raise SystemExit('Missing core: ' + name + '. See README.md.')
    native.mkdir(parents=True, exist_ok=True)
    run(llvm / 'clang++.exe', '--target=aarch64-linux-android26', '-std=c++17', '-O2',
        '-Wall', '-Wextra', '-Werror', '-Wformat=2', '-fstack-protector-strong',
        '-fPIC', '-shared', '-static-libstdc++', '-Wl,-z,relro,-z,now,-z,noexecstack',
        '-Wl,-z,max-page-size=16384',
        ROOT / 'app/src/main/cpp/bridge.cpp', '-o', native / 'libdigimap.so',
        '-landroid', '-llog', '-ldl', '-lEGL', '-lGLESv3')
    OUT.mkdir(exist_ok=True)
    # Fresh classes/dex/resource directories prevent deleted code surviving in later APKs.
    with tempfile.TemporaryDirectory(prefix='apk-', dir=OUT) as work:
        work = Path(work)
        manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml')
        manifest.getroot().find('application').set(ANDROID + 'debuggable', str(args.mode == 'debug').lower())
        manifest_file = work / 'AndroidManifest.xml'
        manifest.write(manifest_file, encoding='utf-8', xml_declaration=True)
        classes = work / 'classes'
        classes.mkdir()
        sources = sorted((ROOT / 'app/src/main/java').rglob('*.java'))
        run(java / 'javac.exe', '-encoding', 'UTF-8', '--release', '8',
            '-classpath', platform, '-d', classes, *sources)
        jar = work / 'classes.jar'
        run(java / 'jar.exe', 'cf', jar, '-C', classes, '.')
        dex = work / 'dex'
        dex.mkdir()
        run(java / 'java.exe', '-cp', build / 'lib/d8.jar', 'com.android.tools.r8.D8',
            '--min-api', '26', '--lib', platform, '--output', dex, jar)
        unsigned = work / 'unsigned.apk'
        run(build / 'aapt.exe', 'package', '-f', '-M', manifest_file, '-I', platform,
            '-S', ROOT / 'app/src/main/res', '-A', ROOT / 'app/src/main/assets', '-F', unsigned)
        with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED) as apk:
            apk.write(dex / 'classes.dex', 'classes.dex')
            for name in libraries:
                apk.write(native / name, 'lib/arm64-v8a/' + name, compress_type=zipfile.ZIP_STORED)
        aligned = work / 'aligned.apk'
        run(build / 'zipalign.exe', '-f', '-P', '16', '4', unsigned, aligned)
        if args.mode != 'release' and not key.exists():
            run(java / 'keytool.exe', '-genkeypair', '-keystore', key, '-storepass', 'android',
                '-keypass', 'android', '-alias', alias, '-keyalg', 'RSA', '-keysize', '2048',
                '-validity', '10000', '-dname', 'CN=DigiMap Local Development')
        dist = ROOT / 'dist'
        dist.mkdir(exist_ok=True)
        suffix = '' if args.mode == 'release' else '-' + args.mode
        version = manifest.getroot().get(ANDROID + 'versionName')
        if not version or not all(c.isdigit() or c == '.' for c in version):
            raise SystemExit('Invalid app version')
        apk = dist / ('DigiBuddy-' + version + suffix + '.apk')
        run(java / 'java.exe', '-jar', build / 'lib/apksigner.jar', 'sign', '--ks', key,
            '--ks-key-alias', alias, '--ks-pass', store_pass, '--key-pass', key_pass,
            '--out', apk, aligned)
        run(java / 'java.exe', '-jar', build / 'lib/apksigner.jar', 'verify', '--verbose', apk)
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        metadata = {
            'apk': apk.name, 'mode': args.mode, 'debuggable': args.mode == 'debug',
            'signing': 'release' if args.mode == 'release' else 'development',
            'bytes': apk.stat().st_size, 'sha256': digest,
            'coreSha256': {name: hashlib.sha256((native / name).read_bytes()).hexdigest() for name in libraries},
        }
        apk.with_suffix('.apk.sha256').write_text(digest + '  ' + apk.name + '\n', encoding='utf-8')
        apk.with_suffix('.build-info.json').write_text(json.dumps(metadata, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(metadata, indent=2))


if __name__ == '__main__':
    main()
