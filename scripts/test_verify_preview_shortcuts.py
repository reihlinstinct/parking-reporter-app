import unittest
from verify_preview_shortcuts import verify, PACKAGE, ACTIVITY

TREE = '\n'.join(f'    A: android:{name}(0x01010000)="{value}" (Raw: "{value}")'
                 for _ in range(2) for name, value in [('targetPackage', PACKAGE), ('targetClass', ACTIVITY)])

class PreviewShortcutTest(unittest.TestCase):
    def test_actual_compiled_attribute_shape(self):
        verify(TREE)

    def test_wrong_package_in_either_intent_fails(self):
        for count in (1, 4):
            with self.assertRaises(ValueError):
                verify(TREE.replace(PACKAGE, 'il.sidewalks.reporter', count))

    def test_missing_extra_or_reference_target_fails(self):
        for tree in ('', TREE + '\n' + TREE, TREE.replace('="' + PACKAGE + '"', '=@0x7f001234')):
            with self.assertRaises(ValueError):
                verify(tree)

    def test_wrong_activity_fails(self):
        with self.assertRaises(ValueError):
            verify(TREE.replace(ACTIVITY, 'il.sidewalks.reporter.preview.MainActivity'))

if __name__ == '__main__':
    unittest.main()
