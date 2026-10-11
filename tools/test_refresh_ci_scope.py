"""Kontrola zakresu poleceń CI; nie zastępuje wykonania testów Androida."""
from pathlib import Path
import shlex
import unittest

ROOT = Path(__file__).resolve().parents[1]
READER_CLASSES = {
    'net.tyflopodcast.tyflocentrum.ui.common.ReaderFocusControlTest',
    'net.tyflopodcast.tyflocentrum.ui.common.ReaderDynamicLabelControlTest',
}


def gradle_calls():
    script = (ROOT / 'tools/verify-refresh-emulator.sh').read_text()
    return [shlex.split(line) for line in script.replace('\\\n', '').splitlines()
            if line.startswith('./gradlew ')]


class CiScopeTests(unittest.TestCase):
    def test_emulator_excludes_only_real_reader_controls(self):
        calls = gradle_calls()
        self.assertEqual(len(calls), 2, 'Najpierw kontrolki, potem pełny zakres emulatora')
        control, full = calls
        self.assertIn('connectedDebugAndroidTest', control)
        self.assertIn('-Pandroid.testInstrumentationRunnerArguments.class=' +
                      'net.tyflopodcast.tyflocentrum.ui.common.RefreshAccessibilityControlTest', control)
        self.assertIn('connectedDebugAndroidTest', full)
        prefix = '-Pandroid.testInstrumentationRunnerArguments.notClass='
        exclusions = [arg[len(prefix):] for arg in full if arg.startswith(prefix)]
        self.assertEqual(len(exclusions), 1, 'Brak jawnego oddzielenia kontrolek prawdziwego czytnika')
        self.assertEqual(set(exclusions[0].split(',')), READER_CLASSES)
        self.assertFalse(any(arg.startswith('-Pandroid.testInstrumentationRunnerArguments.class=')
                             for arg in full), 'Nie zawężaj pełnego zakresu produktu')


if __name__ == '__main__':
    unittest.main(verbosity=2)
