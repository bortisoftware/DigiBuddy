"""ADB development helper scoped to DigiBuddy's package and the explicitly selected device."""
import argparse
import hashlib
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADB = ROOT / '.tools/sdk/platform-tools/adb.exe'
PACKAGE = 'es.digimap.thor'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['seed','launch','capture','logs','info','configure','get','put','input'])
    parser.add_argument('--serial',required=True)
    parser.add_argument('--rom',type=Path)
    parser.add_argument('--bios',type=Path)
    parser.add_argument('--key')
    parser.add_argument('--value')
    parser.add_argument('--file')
    parser.add_argument('--remote')
    parser.add_argument('--display',help='Physical display ID from adb shell dumpsys SurfaceFlinger --display-id')
    args = parser.parse_args()
    adb = [str(ADB), '-s', args.serial]
    def validate_remote(remote):
        if not remote or not remote.startswith(('files/','shared_prefs/')) or '..' in remote or not re.fullmatch(r'[A-Za-z0-9_./-]+',remote):
            raise ValueError('Only safe app-private paths are allowed')


    def run(*command, **kwargs):
        return subprocess.run(adb + list(command), check=True, **kwargs)

    def shell(command, **kwargs):
        return run('shell', command, **kwargs)

    def put_bytes(remote, data=None, local=None):
        validate_remote(remote)
        shell(f"run-as {PACKAGE} mkdir -p {Path(remote).parent.as_posix()}")
        if local:
            with open(local,'rb') as stream:
                run('exec-in',f"run-as {PACKAGE} sh -c 'cat > {remote}.tmp'",stdin=stream)
        else:
            run('exec-in',f"run-as {PACKAGE} sh -c 'cat > {remote}.tmp'",input=data)
        shell(f"run-as {PACKAGE} mv {remote}.tmp {remote}")

    if args.action in ('seed','configure'):
        shell(f'am force-stop {PACKAGE}')
        result = subprocess.run(adb+['exec-out',f'run-as {PACKAGE} cat shared_prefs/digimap.xml'],capture_output=True)
        preferences = ET.fromstring(result.stdout) if result.returncode==0 and result.stdout.strip().startswith(b'<?xml') else ET.Element('map')
        values = {}
        if args.action=='seed':
            if args.rom is None or args.bios is None: raise ValueError('seed requires --rom and --bios')
            ROM,BIOS=args.rom,args.bios
            if BIOS.stat().st_size!=524288: raise ValueError('BIOS must be exactly 512 KiB')
            with ROM.open('rb') as stream: digest = hashlib.file_digest(stream,'sha256').hexdigest()
            remote = f'files/games/{digest}/disc.bin'
            check = subprocess.run(adb+['shell',f'run-as {PACKAGE} stat -c %s {remote}'],capture_output=True)
            if check.returncode!=0 or check.stdout.strip()!=str(ROM.stat().st_size).encode():
                print('Transferring reference disc to app-private storage…',flush=True)
                put_bytes(remote,local=ROM)
            put_bytes('files/system/scph1001.bin',local=BIOS)
            cue = b'FILE "disc.bin" BINARY\n  TRACK 01 MODE2/2352\n    INDEX 01 00:00:00\n'
            put_bytes(f'files/games/{digest}/disc.cue',data=cue)
            actual = shell(f'run-as {PACKAGE} sha256sum {remote}',capture_output=True).stdout.decode().split()[0]
            if actual!=digest:raise RuntimeError('Transferred disc hash differs')
            prefix=f'/data/user/0/{PACKAGE}/'
            values={'bios':('string',prefix+'files/system/scph1001.bin'),'game':('string',prefix+f'files/games/{digest}/disc.cue'),'discHash':('string',digest),'discName':('string',ROM.name),'serial':('string','SLPS-01797')}
        else:
            if not args.key or args.value is None:raise ValueError('--key and --value required')
            if args.key in ('pgxp','pgxpTexture','trueColor','smoothScaling','stretch','gameOnSecondary'):
                values[args.key]=('boolean',args.value)
            elif args.key=='selectedDisplay':values[args.key]=('int',args.value)
            else:values[args.key]=('string',args.value)
        for key,(kind,value) in values.items():
            for entry in list(preferences):
                if entry.get('name')==key:preferences.remove(entry)
            entry=ET.SubElement(preferences,kind,{'name':key})
            if kind=='string':entry.text=value
            else:entry.set('value',value)
        put_bytes('shared_prefs/digimap.xml',data=ET.tostring(preferences,encoding='utf-8',xml_declaration=True))
        print('DigiBuddy preferences and private files ready',flush=True)
    elif args.action=='launch':
        shell(f'am start -n {PACKAGE}/.MainActivity' + (' --ez devStart true' if args.value=='play' else ''))
    elif args.action=='capture':
        path=Path(args.file or ROOT/'private/device-screen.png');path.parent.mkdir(parents=True,exist_ok=True)
        with path.open('wb') as stream:
            run('exec-out','screencap','-p',*(['-d',args.display] if args.display else []),stdout=stream)
        print(path)
    elif args.action=='logs':
        run('logcat','-d','-t','400','DigiMapCore:V','DigiMap:V','AndroidRuntime:E','DEBUG:E','*:S')
    elif args.action=='info':
        result=shell('dumpsys window windows',capture_output=True,text=True)
        blocks=re.split(r'(?=  Window #[0-9]+ )',result.stdout)
        print('\n'.join(block[:7000] for block in blocks if PACKAGE in block and block.startswith('  Window #')))
    elif args.action=='get':
        validate_remote(args.remote)
        path=Path(args.file);path.parent.mkdir(parents=True,exist_ok=True)
        with path.open('wb') as stream:run('exec-out',f'run-as {PACKAGE} cat {args.remote}',stdout=stream)
    elif args.action=='put':put_bytes(args.remote,local=args.file)
    elif args.action=='input':shell(f'input keyevent {int(args.value)}')


if __name__=='__main__':main()
