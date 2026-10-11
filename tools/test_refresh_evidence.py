"""Kontrolki parsera dowodów; syntetyczne dane nie są wynikami Androida."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('evidence', Path(__file__).with_name('verify-refresh-evidence.py'))
assert spec is not None and spec.loader is not None
evidence = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evidence)

class EvidenceTests(unittest.TestCase):
    def setUp(self):
        base = Path(__file__).resolve().parents[1] / 'app/build/evidence-parser-tests'
        base.mkdir(parents=True, exist_ok=True)
        self.work = tempfile.TemporaryDirectory(dir=base)
        self.addCleanup(self.work.cleanup)
        self.root = Path(self.work.name)
        self.files = self.root/'files'
        self.files.mkdir()
        fixtures = {'sample-ax': json.dumps({'pid': 1}), 'sample-merged': 'syntetyczne merged',
                    'sample-unmerged': 'syntetyczne unmerged', 'transport-control-0-large-utf8': 'żółć',
                    'transport-control-1-empty': ''}
        lines = []
        for name, text in fixtures.items():
            payload = text.encode()
            (self.files/name).write_bytes(payload)
            lines.append(f'REFRESH_EVIDENCE /data/local/tmp/tyflo-refresh/{name} bytes={len(payload)} sha256={hashlib.sha256(payload).hexdigest()} readback=true')
        self.log = self.root/'log'
        self.log.write_text('\n'.join(lines))

    def test_valid(self):
        self.assertEqual(evidence.verify(self.files, self.log)['files'], 5)

    def test_missing(self):
        (self.files/'sample-ax').unlink()
        with self.assertRaisesRegex(ValueError, 'Nieodebrany'): evidence.verify(self.files, self.log)

    def test_corruption(self):
        (self.files/'sample-merged').write_text('zmienione')
        with self.assertRaisesRegex(ValueError, 'Niezgodne'): evidence.verify(self.files, self.log)

    def test_no_receipt(self):
        self.log.write_text('REFRESH_EVIDENCE path bytes=54')
        with self.assertRaisesRegex(ValueError, 'Brak potwierdzonych'): evidence.verify(self.files, self.log)

    def test_directory_instead_of_empty_file(self):
        path = self.files/'transport-control-1-empty'
        path.unlink()
        path.mkdir()
        with self.assertRaisesRegex(ValueError, 'Nieodebrany'): evidence.verify(self.files, self.log)

    def test_duplicate(self):
        self.log.write_text(self.log.read_text()+'\n'+self.log.read_text().splitlines()[0])
        with self.assertRaisesRegex(ValueError, 'Powtórzona'): evidence.verify(self.files, self.log)

    def test_unclaimed_file(self):
        (self.files/'extra').write_text('dane')
        with self.assertRaisesRegex(ValueError, 'Nierozliczone'): evidence.verify(self.files, self.log)

if __name__ == '__main__':
    unittest.main(verbosity=2)
