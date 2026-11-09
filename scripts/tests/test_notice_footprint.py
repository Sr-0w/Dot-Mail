import importlib.util
import io
import sys
import unittest
import tempfile
from unittest.mock import patch
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))
spec = importlib.util.spec_from_file_location('notice_footprint', Path(__file__).parents[1]/'notice_footprint.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class NoticeFootprintTest(unittest.TestCase):
    def analyze_fixture(self, mapping_text='', usage_text='example.Crypto', resource=False):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            artifact, apk = root/'library.jar', root/'release.apk'
            with zipfile.ZipFile(artifact, 'w') as z:
                z.writestr('example/Crypto.class', b'fixture')
                if resource:
                    z.writestr('META-INF/services/example.Service', 'example.Crypto')
            with zipfile.ZipFile(apk, 'w') as z:
                z.writestr('classes.dex', b'parsed by DEX parser fixture')
                if resource:
                    z.writestr('META-INF/services/example.Service', 'example.Crypto')
            mapping, usage = root/'mapping.txt', root/'usage.txt'
            mapping.write_text(mapping_text); usage.write_text(usage_text)
            inventory = {'components': ['example:cms:1'], 'artifacts': [{'component': 'example:cms:1', 'path': str(artifact)}]}
            with patch.object(module, 'dex_types_and_constructors', return_value=({'Lapp/App;', 'La;'}, {})):
                return module.analyze(inventory, apk, mapping, usage)['example:cms:1']

    def test_removed_classes_without_resources_prove_absence(self):
        self.assertEqual('stripped', self.analyze_fixture()['status'])

    def test_reachable_classes_are_included_even_when_renamed(self):
        self.assertEqual('included', self.analyze_fixture('example.Crypto -> a:', '')['status'])

    def test_inlined_code_is_not_mistaken_for_removed_library(self):
        mapping = 'app.App -> a:\n    1:2:void example.Crypto.sign():1:2 -> a'
        self.assertEqual('included', self.analyze_fixture(mapping)['status'])

    def test_unaccounted_class_is_not_proof_of_absence(self):
        result = self.analyze_fixture(usage_text='')
        self.assertEqual('included', result['status'])
        self.assertEqual(1, result['unknown_classes'])

    def test_remaining_service_resource_keeps_its_notice(self):
        self.assertEqual('included', self.analyze_fixture(resource=True)['status'])

    def test_newly_reachable_library_cannot_keep_stripped_notice(self):
        manifest = {'components': {'example:cms:1': {'status': 'stripped'}}}
        with self.assertRaisesRegex(ValueError, 'exclusion no longer provable'):
            module.verify(manifest, {'example:cms:1': {'status': 'included'}})

    def test_unknown_footprint_is_included_and_rejects_exclusion(self):
        manifest = {'components': {'example:unknown:1': {'status': 'metadata-only'}}}
        with self.assertRaises(ValueError):
            module.verify(manifest, {'example:unknown:1': {'status': 'included'}})

    def test_binary_selection_for_metadata_only_coordinate_fails(self):
        manifest = {'components': {'example:platform:1': {'status': 'metadata-only'}}}
        with self.assertRaisesRegex(ValueError, 'Runtime artifact now selected'):
            module.verify(manifest, {'example:platform:1': {'status': 'stripped'}})

    def test_conservative_notice_is_never_silently_removed(self):
        module.verify({'components': {'example:lib:1': {'status': 'included'}}},
                      {'example:lib:1': {'status': 'stripped'}})

    def test_android_resources_and_manifest_force_conservative_inclusion(self):
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as z:
            z.writestr('res/values/strings.xml', '<resources/>')
            z.writestr('AndroidManifest.xml', '<manifest><uses-permission/></manifest>')
            z.writestr('META-INF/LICENSE.md', 'License is not implementation footprint')
        classes, resources, uncertain = module.contents(data.getvalue())
        self.assertFalse(classes)
        self.assertFalse(resources)
        self.assertTrue(uncertain)

    def test_service_config_is_runtime_footprint_and_class_named_license_is_code(self):
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as z:
            z.writestr('META-INF/services/java.security.Provider', 'example.CryptoProvider')
            z.writestr('example/LICENSE.class', b'fake class fixture')
        classes, resources, uncertain = module.contents(data.getvalue())
        self.assertEqual({'example.LICENSE'}, classes)
        self.assertEqual({'META-INF/services/java.security.Provider'}, resources)
        self.assertFalse(uncertain)


if __name__ == '__main__':
    unittest.main()
