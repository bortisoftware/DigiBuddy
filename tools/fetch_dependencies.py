"""Download fixed, checksum-verified development dependencies into the private cache."""
import argparse
import concurrent.futures
import hashlib
import json
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / ".tools"


def verify(path, dependency):
    if not path.is_file() or path.stat().st_size != dependency["bytes"]:
        return False
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest() == dependency["sha256"]


def download(dependency):
    name = dependency["file"]
    if Path(name).name != name or not name.endswith(".zip"):
        raise ValueError("Unsafe dependency filename")
    target = CACHE / name
    if target.exists():
        if not verify(target, dependency):
            raise ValueError(name + ": cached checksum mismatch")
        print("Verified " + name, flush=True)
        return
    temporary = CACHE / (name + ".part")
    try:
        request = urllib.request.Request(dependency["url"], headers={"User-Agent": "DigiBuddy-development"})
        total = 0
        with urllib.request.urlopen(request, timeout=60) as response, temporary.open("wb") as out:
            while block := response.read(1024 * 1024):
                total += len(block)
                if total > dependency["bytes"]:
                    raise ValueError(name + ": download exceeds recorded size")
                out.write(block)
        if not verify(temporary, dependency):
            raise ValueError(name + ": download checksum mismatch; refusing changed upstream content")
        temporary.replace(target)
        print("Downloaded and verified " + name, flush=True)
    finally:
        if temporary.exists():
            temporary.unlink()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--development-cores", action="store_true", help="Fetch the exact tested buildbot binaries; a changed latest URL fails closed")
    parser.add_argument("--sources", action="store_true", help="Fetch matching upstream source revisions")
    args = parser.parse_args()
    manifest = json.loads((ROOT / "tools/dependencies.json").read_text())
    dependencies = list(manifest["buildTools"])
    if args.development_cores:
        dependencies += manifest["developmentCores"]
    if args.sources:
        dependencies += manifest["coreSources"]
    CACHE.mkdir(exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        results = [pool.submit(download, entry) for entry in dependencies]
        for result in results:
            result.result()


if __name__ == "__main__":
    main()
