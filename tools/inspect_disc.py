"""Read-only PSX ISO9660 identification; supports cooked and raw sectors."""
import argparse
import hashlib
import json
from pathlib import Path


class Disc:
    MAX_ENTRY_BYTES = 16 * 1024 * 1024

    def __init__(self, path):
        self.file = open(path, 'rb')
        try:
            self.file.seek(0, 2)
            self.size = self.file.tell()
            self.sector_size = self.payload = None
            for size, offset in ((2048, 0), (2352, 24), (2352, 16), (2336, 8)):
                self.file.seek(size * 16 + offset)
                if self.file.read(7) == b'\x01CD001\x01':
                    self.sector_size, self.payload = size, offset
                    break
            if self.sector_size is None:
                raise ValueError('No ISO9660 primary volume descriptor found')
            pvd = self.read(16, 2048)
            self.volume = pvd[40:72].decode('ascii', 'replace').strip()
            self.root = self.record(pvd[156:])
        except Exception:
            self.file.close()
            raise

    def read(self, lba, length):
        if lba < 0 or length < 0 or length > self.MAX_ENTRY_BYTES:
            raise ValueError('ISO sector or entry size out of bounds')
        start = lba * self.sector_size + self.payload
        end = start if length == 0 else start + ((length - 1) // 2048) * self.sector_size + (length - 1) % 2048 + 1
        if start > self.size or end > self.size:
            raise ValueError('ISO entry extends past the disc')
        data = bytearray()
        while len(data) < length:
            self.file.seek(lba * self.sector_size + self.payload)
            size = min(2048, length - len(data))
            block = self.file.read(size)
            if len(block) != size:
                raise ValueError('Truncated ISO sector')
            data.extend(block)
            lba += 1
        return bytes(data)

    @staticmethod
    def record(data):
        if len(data) < 34:
            raise ValueError('Truncated ISO record')
        length, name_length = data[0], data[32]
        if length < 34 or length > len(data) or name_length < 1 or name_length > length - 33:
            raise ValueError('Invalid ISO record or name length')
        return {
            'lba': int.from_bytes(data[2:6], 'little'),
            'size': int.from_bytes(data[10:14], 'little'),
            'directory': bool(data[25] & 2),
            'name': data[33:33 + name_length].decode('ascii', 'replace'),
        }

    def entries(self, directory=None):
        directory = directory or self.root
        if not directory['directory']:
            raise ValueError('Entry is not a directory')
        data = self.read(directory['lba'], directory['size'])
        pos = 0
        while pos < len(data):
            length = data[pos]
            if not length:
                pos = (pos // 2048 + 1) * 2048
                continue
            if length > len(data) - pos:
                raise ValueError('Truncated directory record')
            entry = self.record(data[pos:pos + length])
            if entry['name'] not in ('\x00', '\x01'):
                yield entry
            pos += length

    def close(self):
        self.file.close()


def sha256(path):
    with open(path, 'rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def identify(path, bios=None):
    disc = Disc(path)
    try:
        entries = list(disc.entries())
        system = next((e for e in entries if e['name'].upper().split(';')[0] == 'SYSTEM.CNF'), None)
        config = disc.read(system['lba'], system['size']).decode('ascii', 'replace') if system else None
        executable = next((e for e in entries if e['name'].upper().startswith(('SLPS_', 'SLUS_', 'SLES_', 'SCPS_', 'SCUS_', 'SCES_'))), None)
        result = {
            'file': Path(path).name, 'bytes': disc.size,
            'sha256': sha256(path), 'sectorSize': disc.sector_size,
            'payloadOffset': disc.payload, 'volume': disc.volume,
            'systemCnf': config, 'rootEntries': entries,
            'executable': executable,
        }
        if executable:
            binary = disc.read(executable['lba'], executable['size'])
            result['executableSha256'] = hashlib.sha256(binary).hexdigest()
            if binary.startswith(b'PS-X EXE'):
                result['loadAddress'] = hex(int.from_bytes(binary[24:28], 'little'))
                result['loadBytes'] = int.from_bytes(binary[28:32], 'little')
        if bios:
            result['bios'] = {'file': Path(bios).name, 'bytes': Path(bios).stat().st_size, 'sha256': sha256(bios)}
        return result
    finally:
        disc.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('disc')
    parser.add_argument('--bios')
    parser.add_argument('--output')
    args = parser.parse_args()
    result = identify(args.disc, args.bios)
    output = json.dumps(result, indent=2, ensure_ascii=False)
    if args.output:
        Path(args.output).write_text(output + '\n', encoding='utf-8')
    print(output)
