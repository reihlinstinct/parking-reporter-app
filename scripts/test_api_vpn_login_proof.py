"""Inactive workflow and actual no-network route checker regressions."""
import importlib.util
from pathlib import Path
import socket
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('vpn_probe_transport', ROOT / 'trial/l2tp.py')
transport = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transport)


class ApiVpnProbeTest(unittest.TestCase):
    def test_workflow_remains_disabled_login_only_and_cleans_up(self):
        text = (ROOT / '.github/workflows/api-vpn-login-proof.yml').read_text()
        for required in ('if: ${{ false }}', 'test "$GITHUB_REF" = refs/heads/main',
                         'environment: m0-trial-submit', 'group: live-emulator-e2e',
                         'cancel-in-progress: false', 'persist-credentials: false',
                         'API_VPN_LOGIN_IS_NOT_CREATE_OR_ATTACHMENT_PROOF',
                         'if: always()', 'sudo python3 trial/l2tp.py down'):
            self.assertIn(required, text)
        for forbidden in ('REPORTER_', 'APPROVED_PHOTO', 'TRIAL_APPROVED_TEXT', 'BridgeAdapter',
                          'BATCH_BRIDGE_AUTHORIZED', 'curl ', 'upload-artifact', 'continue-on-error',
                          'contents: write', 'httpbin'):
            self.assertNotIn(forbidden, text)
        self.assertLess(text.index('writeBatchBridgeClasspath'), text.index('secrets.VPN_L2TP'))
        self.assertLess(text.index('trial/l2tp.py up-bounded'), text.index('secrets.J106_APP_PASSWORD'))
        self.assertEqual(2, text.count('verify_municipal_routes()'))
        self.assertEqual(1, text.count('java -cp'))

    def test_actual_route_check_refuses_non_vpn_before_any_http(self):
        # Public resolver addresses are synthetic route arguments, no requests made.
        address = '8.8.8.8'
        with patch.object(transport.socket, 'getaddrinfo', return_value=[(socket.AF_INET, socket.SOCK_STREAM, 6, '', (address, 443))]), \
                patch.object(transport, 'run', return_value='[{"dev":"eth0"}]') as run:
            with self.assertRaises(transport.TunnelError):
                transport.verify_municipal_routes()
            run.assert_called_once_with('ip', '-json', 'route', 'get', address)

    def test_actual_route_check_accepts_only_vpn_for_every_resolved_target(self):
        answers = [(socket.AF_INET, socket.SOCK_STREAM, 6, '', (ip, 443)) for ip in ('8.8.8.8', '1.1.1.1')]
        with patch.object(transport.socket, 'getaddrinfo', return_value=answers), \
                patch.object(transport, 'run', return_value='[{"dev":"ppp42"}]') as run:
            transport.verify_municipal_routes()
            self.assertEqual(2, run.call_count)
            self.assertTrue(all(call.args[:4] == ('ip', '-json', 'route', 'get') for call in run.call_args_list))

    def test_actual_route_check_refuses_empty_or_private_dns(self):
        for answers in ([], [(socket.AF_INET, socket.SOCK_STREAM, 6, '', ('127.0.0.1', 443))]):
            with patch.object(transport.socket, 'getaddrinfo', return_value=answers), patch.object(transport, 'run') as run:
                with self.assertRaises(transport.TunnelError):
                    transport.verify_municipal_routes()
                run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
