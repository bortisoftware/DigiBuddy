"""Extract only verified archives, rejecting unsafe paths and archive links."""
import argparse
import json
import shutil
import stat
import zipfile
from pathlib import Path, PurePosixPath
from fetch_dependencies import verify, ROOT, CACHE


def extract(archive_path, destination, include=lambda name: True):
    destination.mkdir(parents=True, exist_ok=True)
    root = destination.resolve()
    with zipfile.ZipFile(archive_path) as archive:
        if sum(member.file_size for member in archive.infolist()) > 16 * 1024**3:
            raise ValueError("Archive is too large")
        for member in archive.infolist():
            if not include(member.filename):
                continue
            raw_name = member.orig_filename
            if chr(92) in raw_name or chr(0) in raw_name:
                raise ValueError("Unsafe original archive path")
            path = PurePosixPath(member.filename)
            if path.is_absolute() or ".." in path.parts or chr(92) in member.filename or ":" in member.filename:
                raise ValueError("Unsafe archive path")
            if stat.S_ISLNK(member.external_attr >> 16):
                raise ValueError("Archive links are not allowed")
            target = root.joinpath(*path.parts).resolve()
            if not target.is_relative_to(root):
                raise ValueError("Archive path escapes destination")
            if member.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(member) as source, target.open("wb") as out:
                    shutil.copyfileobj(source, out, 1024 * 1024)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--development-cores", action="store_true")
    parser.add_argument("--sources", action="store_true")
    args = parser.parse_args()
    manifest = json.loads((ROOT / "tools/dependencies.json").read_text())
    dependencies = {d["file"]: d for entries in manifest.values() for d in entries}
    targets = {"jdk.zip": "java", "platform.zip": "sdk/platforms", "build-tools.zip": "sdk/build-tools",
               "platform-tools.zip": "sdk", "ndk.zip": "ndk"}
    if args.sources:
        targets.update({d["file"]: "sources" for d in manifest["coreSources"]})
    for name, destination in targets.items():
        dependency = dependencies[name]
        archive = CACHE / name
        if not verify(archive, dependency):
            raise ValueError("Missing or unverified archive: " + name)
        target = CACHE / destination
        marker = target / (name + ".sha256")
        if marker.exists() and marker.read_text() == dependency["sha256"]:
            continue
        print("Extracting " + name, flush=True)
        extract(archive, target)
        marker.write_text(dependency["sha256"])
    if args.development_cores:
        native = ROOT / "app/src/main/jniLibs/arm64-v8a"
        native.mkdir(parents=True, exist_ok=True)
        for name, output in [("swan-core.zip", "libswanstation.so"), ("pcsx-core.zip", "libpcsx_rearmed.so")]:
            if not verify(CACHE / name, dependencies[name]):
                raise ValueError("Missing or unverified core archive")
            with zipfile.ZipFile(CACHE / name) as archive:
                members = [m for m in archive.infolist() if not m.is_dir() and m.filename.endswith(".so")]
                if len(members) != 1 or members[0].file_size > 64 * 1024**2:
                    raise ValueError("Unexpected core archive")
                (native / output).write_bytes(archive.read(members[0]))
    print("Build dependencies ready")


if __name__ == "__main__":
    main()
