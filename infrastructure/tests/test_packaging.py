from pathlib import Path
import subprocess
import sys
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]


class PackagingTests(unittest.TestCase):
    def test_zip_is_repeatable_and_has_correct_handler_at_root(self):
        subprocess.run([sys.executable, str(ROOT / 'scripts/build_authorizer.py')], check=True, capture_output=True)
        first = (ROOT / 'build/authorizer.zip').read_bytes()
        subprocess.run([sys.executable, str(ROOT / 'scripts/build_authorizer.py')], check=True, capture_output=True)
        self.assertEqual(first, (ROOT / 'build/authorizer.zip').read_bytes())
        with zipfile.ZipFile(ROOT / 'build/authorizer.zip') as z:
            self.assertEqual(['authorizer.py'], z.namelist())
            self.assertEqual(zipfile.ZIP_STORED, z.getinfo('authorizer.py').compress_type)
            self.assertEqual(z.read('authorizer.py'), (ROOT / 'lambda/authorizer/authorizer.py').read_bytes())
            compile(z.read('authorizer.py'), 'authorizer.py', 'exec')


if __name__ == '__main__': unittest.main()
