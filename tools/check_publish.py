"""Audit staged source and APK contents. Does not upload, stage, commit or read credentials."""
import argparse, hashlib, re, subprocess, sys, zipfile
from pathlib import Path, PurePosixPath
ROOT=Path(__file__).resolve().parents[1]
DOCS={".gitignore",".gitattributes","LICENSE","README.md","README.en.md","DESIGN.md","CORE_PROVENANCE.md",
      "THIRD_PARTY_NOTICES.md","ICON.md","SECURITY.md","REVIEW.md"}
ICONS={"app/src/main/res/drawable-nodpi/jijimon_foreground.png",
       "app/src/main/res/drawable-nodpi/map_icon.png",
       "docs/banner.png", "docs/video-preview.png", "docs/screenshots/game.png", "docs/screenshots/companion.png", "docs/screenshots/startup.png"}
TEXT_SUFFIX={".java",".cpp",".h",".py",".xml",".json",".txt",".md",""}
FORBIDDEN={".iso",".bin",".cue",".chd",".img",".ccd",".sub",".mcr",".state",".sav",".srm",
           ".keystore",".jks",".p12",".key",".pem",".apk",".aab",".so",".dll",".exe",".zip",".7z",".rar"}
SECRETS=[re.compile(rb"gh[pousr]_[A-Za-z0-9]{30,}"),re.compile(rb"github_pat_[A-Za-z0-9_]{30,}"),
         re.compile(rb"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
         re.compile(rb"sk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{32,}"),
         re.compile(rb"AIza[A-Za-z0-9_-]{35}"),
         re.compile(rb"(?:AKIA|ASIA)[A-Z0-9]{16}"),
         re.compile(rb"xox[baprs]-[A-Za-z0-9-]{20,}")]
def allowed_source(name):
    p=PurePosixPath(name)
    if p.is_absolute() or ".." in p.parts or p.suffix.lower() in FORBIDDEN:return False
    if name in DOCS or name in ICONS:return True
    if name.startswith("tools/"):return p.suffix in (".py",".json")
    if name=="app/src/main/AndroidManifest.xml":return True
    if name.startswith("app/src/main/java/"):return p.suffix==".java"
    if name.startswith("app/src/main/cpp/"):return p.suffix in (".cpp",".h")
    if name=="app/src/main/assets/profiles.json":return True
    if name.startswith("app/src/main/assets/licenses/"):return True
    return name.startswith("app/src/main/res/") and p.suffix==".xml"
def staged(all_files=False):
    command = ["git", "ls-files", "-z"] if all_files else ["git", "diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z"]
    names=subprocess.check_output(command,cwd=ROOT).decode("utf-8").split("\0")
    names=[n for n in names if n]
    if not names:raise ValueError("No staged files to inspect")
    errors=[]
    for name in names:
        if not allowed_source(name):errors.append(name+": outside source allowlist");continue
        data=subprocess.check_output(["git","show",":"+name],cwd=ROOT)
        max_size = 3*1024**2 if name == "docs/banner.png" else 2*1024**2
        if len(data)>max_size:errors.append(name+": unexpectedly large");continue
        if name in ICONS:
            if not data.startswith(b"\x89PNG\r\n\x1a\n"):errors.append(name+": not PNG")
            continue
        try:data.decode("utf-8")
        except UnicodeDecodeError:errors.append(name+": non-text content");continue
        if b"\0" in data:errors.append(name+": binary content")
        if any(pattern.search(data) for pattern in SECRETS):errors.append(name+": possible secret")
        if re.search(rb"[A-Za-z]:[\\/]+Users[\\/]+[^\\/\r\n\"']+",data):errors.append(name+": personal absolute path")
    if errors:raise ValueError("\n".join(errors))
    print("PASS: %d staged files; source allowlist, text/size and secret checks"%len(names))
def apk(path):
    expected_libs={"lib/arm64-v8a/libdigimap.so","lib/arm64-v8a/libswanstation.so","lib/arm64-v8a/libpcsx_rearmed.so"}
    with zipfile.ZipFile(path) as archive:
        names=archive.namelist()
        if len(names)!=len(set(names)):raise ValueError("Duplicate APK entries")
        if {n for n in names if n.startswith("lib/")}!=expected_libs:raise ValueError("Unexpected native libraries")
        for n in names:
            p=PurePosixPath(n)
            if p.is_absolute() or ".." in p.parts:raise ValueError("Unsafe APK path")
            if n in expected_libs or n in ("AndroidManifest.xml","resources.arsc","classes.dex","assets/profiles.json"):continue
            if n.startswith("assets/licenses/"):
                archive.read(n).decode("utf-8");continue
            if n.startswith("res/") and p.suffix in (".xml",".png"):continue
            if n.startswith("META-INF/"):continue
            raise ValueError("Unexpected APK entry: "+n)
        for n in names:
            if PurePosixPath(n).suffix.lower() in FORBIDDEN- {".so"}:raise ValueError("Private/disc format in APK: "+n)
        size=sum(i.file_size for i in archive.infolist())
        if size>64*1024**2:raise ValueError("Unexpected unpacked APK size")
    print("PASS: APK allowlist; three emulator libraries, app resources and text licenses only")
    print("SHA256 "+hashlib.sha256(path.read_bytes()).hexdigest())
def main():
    p=argparse.ArgumentParser();p.add_argument("--staged",action="store_true");p.add_argument("--all",action="store_true",help="Inspect the complete Git index");p.add_argument("--apk",type=Path)
    args=p.parse_args()
    if not args.staged and not args.all and not args.apk:p.error("Choose --staged, --all and/or --apk")
    if args.staged or args.all:staged(all_files=args.all)
    if args.apk:apk(args.apk)
if __name__=="__main__":
    try:main()
    except (ValueError,subprocess.CalledProcessError) as e:print("FAIL: "+str(e),file=sys.stderr);sys.exit(1)
