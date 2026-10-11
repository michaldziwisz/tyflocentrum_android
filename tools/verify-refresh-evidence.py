#!/usr/bin/env python3
"""Odbiór rzeczywistych plików emulatora po adb pull, nie samych deklaracji."""
import hashlib
import json
from pathlib import Path
import re
import sys


def verify(directory, log_path):
    directory = Path(directory)
    pattern = r'REFRESH_EVIDENCE (/data/local/tmp/tyflo-refresh/([a-zA-Z0-9_-]+)) bytes=(\d+) sha256=([0-9a-f]{64}) readback=true'
    log = Path(log_path).read_text(errors='replace')
    claims = re.findall(pattern, log)
    if not claims:
        raise ValueError('Brak potwierdzonych zapisów')
    names = set()
    for remote, name, size, expected in claims:
        if name in names:
            raise ValueError(f'Powtórzona ścieżka dowodu: {name}')
        names.add(name)
        path = directory / name
        if not path.is_file():
            raise ValueError(f'Nieodebrany plik: {remote}')
        payload = path.read_bytes()
        if len(payload) != int(size) or hashlib.sha256(payload).hexdigest() != expected:
            raise ValueError(f'Niezgodne bajty: {name}')
    files = {p.name for p in directory.iterdir() if p.is_file()}
    if files != names:
        raise ValueError(f'Nierozliczone pliki: {sorted(files ^ names)}')
    for suffix in ('-ax', '-merged', '-unmerged'):
        if not any(name.endswith(suffix) for name in names):
            raise ValueError(f'Brak drzewa {suffix}')
    for name in names:
        if name.endswith('-ax'):
            data = json.loads((directory / name).read_text())
            if not isinstance(data.get('pid'), int):
                raise ValueError(f'Brak PID: {name}')
    for label in ('large-utf8', 'empty'):
        if not any(name.startswith('transport-control-') and name.endswith('-' + label) for name in names):
            raise ValueError(f'Brak kontrolki transportu {label}')
    return {'files': len(files), 'verified': True, 'ax': sum(n.endswith('-ax') for n in names)}


if __name__ == '__main__':
    print(json.dumps(verify(sys.argv[1], sys.argv[2]), ensure_ascii=False))
