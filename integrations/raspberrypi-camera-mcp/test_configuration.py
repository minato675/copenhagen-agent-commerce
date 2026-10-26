import os
import unittest
from unittest.mock import patch
import connect


class ConfigurationTests(unittest.TestCase):
    def test_service_uses_environment_without_prompt(self):
        with patch.dict(os.environ, {'TUNNEL_ID':'tunnel_test', 'CONTROL_PLANE_API_KEY':'test-placeholder', 'CAMERA_BACKEND':'usb'}, clear=True):
            with patch('builtins.input', side_effect=AssertionError('No input allowed')):
                self.assertEqual(connect.configuration(['--non-interactive']), ('usb','tunnel_test','test-placeholder',True))

    def test_missing_key_fails_without_prompt(self):
        with patch.dict(os.environ, {'TUNNEL_ID':'tunnel_test'}, clear=True):
            with self.assertRaisesRegex(ValueError,'CONTROL_PLANE_API_KEY'):
                connect.configuration(['--non-interactive'])

    def test_placeholder_rejected(self):
        with patch.dict(os.environ, {'TUNNEL_ID':'tunnel_REPLACE_ME','CONTROL_PLANE_API_KEY':'REPLACE_ME'}, clear=True):
            with self.assertRaisesRegex(ValueError,'TUNNEL_ID'):
                connect.configuration(['--non-interactive'])

    def test_interactive_does_not_reuse_key(self):
        with patch.dict(os.environ, {'CONTROL_PLANE_API_KEY':'test-placeholder'}, clear=True):
            with patch('builtins.input',return_value='tunnel_test'):
                self.assertEqual(connect.configuration(['usb']),('usb','tunnel_test',None,False))


if __name__=='__main__':
    unittest.main()
