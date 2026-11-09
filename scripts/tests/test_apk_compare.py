import importlib.util
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("apk_compare", Path(__file__).parents[1] / "apk_compare.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ApkComparisonTest(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)

    def apk(self, name, files, year=2020):
        path = Path(self.work.name) / name
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(path, "w") as apk:
                for entry, body in files:
                    apk.writestr(zipfile.ZipInfo(entry, (year, 1, 1, 0, 0, 0)), body)
        return path

    def test_zip_metadata_difference_is_not_reproducibility(self):
        a = self.apk("a.apk", [("classes.dex", b"fixture")])
        b = self.apk("b.apk", [("classes.dex", b"fixture")], year=2021)
        result = module.compare(a, b)
        self.assertTrue(result["entry_contents_identical"])
        self.assertEqual("DIFF", result["verdict"])

    def test_mail_providers_and_kotlin_metadata_are_not_signature_files(self):
        for name in ("META-INF/javamail.default.providers", "META-INF/services/provider", "META-INF/module.kotlin_module"):
            a = self.apk("a.apk", [(name, b"a")])
            b = self.apk("b.apk", [(name, b"b")])
            self.assertEqual("DIFF", module.compare(a, b, True)["verdict"])

    def test_signature_diagnostic_never_claims_byte_reproducibility(self):
        a = self.apk("a.apk", [("classes.dex", b"fixture"), ("META-INF/KEY.RSA", b"a")])
        b = self.apk("b.apk", [("classes.dex", b"fixture"), ("META-INF/KEY.RSA", b"b")])
        self.assertEqual("CONTENTS_MATCH_ONLY", module.compare(a, b, True)["verdict"])
        self.assertEqual("DIFF", module.compare(a, b)["verdict"])

    def test_duplicate_names_are_rejected_even_for_identical_files(self):
        a = self.apk("a.apk", [("classes.dex", b"a"), ("classes.dex", b"b")])
        with self.assertRaises(ValueError):
            module.compare(a, a)

    def test_identical_unsigned_archive_passes(self):
        a = self.apk("a.apk", [("classes.dex", b"fixture"), ("META-INF/services/provider", b"fixture")])
        self.assertEqual("PASS", module.compare(a, a)["verdict"])


if __name__ == "__main__":
    unittest.main()
