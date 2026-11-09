import importlib.util
import io
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location('generate_notices', Path(__file__).parents[1]/'generate_notices.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class NoticeGenerationTest(unittest.TestCase):
    def test_preserves_nested_aar_notice_and_mit_copyright(self):
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, 'w') as z:
            z.writestr('META-INF/NOTICE.txt', 'Copyright fixture\nAttribution required')
            z.writestr('META-INF/LICENSE', 'MIT license fixture with copyright')
        aar = io.BytesIO()
        with zipfile.ZipFile(aar, 'w') as z:
            z.writestr('classes.jar', nested.getvalue())
            z.writestr('res/values/strings.xml', '<resources/>')
        found = module.archive_notices(aar.getvalue())
        self.assertIn(('classes.jar!/META-INF/NOTICE.txt', 'Copyright fixture\nAttribution required'), found)
        self.assertEqual(2, len(found))

    def test_new_component_without_declared_license_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp)
            pom = cache/'example'/'new-library'/'1'/'hash'/'library.pom'
            pom.parent.mkdir(parents=True)
            pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"/>')
            with self.assertRaisesRegex(ValueError, 'No declared license'):
                module.pom_licenses(cache, 'example:new-library:1')

    def test_notice_only_is_not_a_full_non_apache_license(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name in ('openpgp/LICENSE', 'LICENSE', 'designsystem/FONT_LICENSE-Inter.txt'):
                p = root/name
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text('fixture license')
            pom = root/'cache/example/library/1/hash/library.pom'
            pom.parent.mkdir(parents=True)
            pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><licenses><license><name>MIT</name></license></licenses></project>')
            artifact = root/'library.jar'
            with zipfile.ZipFile(artifact, 'w') as z:
                z.writestr('META-INF/NOTICE.txt', 'Attribution only; missing full terms')
            inventory = {'components': ['example:library:1'], 'artifacts': [{'component': 'example:library:1', 'path': str(artifact)}]}
            with self.assertRaisesRegex(ValueError, 'full license/copyright text'):
                module.generate(inventory, root/'cache', root)

    def test_missing_transitive_pom_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(ValueError, 'Expected one cached POM'):
                module.pom_licenses(Path(tmp), 'example:unreviewed:1')


if __name__ == '__main__':
    unittest.main()
