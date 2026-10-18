"""현재 seed의 종료 판정을 제어 응답으로 재현하며 실제 API는 호출하지 않는다."""
import contextlib
import importlib.util
import io
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

path = Path(sys.argv.pop(1))
spec = importlib.util.spec_from_file_location('seed_baseline', path)
seed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(seed)


class VerdictRegression(unittest.TestCase):
    """인덱싱 완료 뒤 검색 실패를 전체 실패로 판정해야 한다."""

    def assert_search_failure(self, status, payload):
        client = unittest.mock.Mock()
        client.base_url = 'http://localhost:8080'
        client.call.return_value = (200, {'data': {'documentStatus': 'INDEXED',
            'currentVersion': {'status': 'INDEXED'}, 'processingVersion': None}})
        client.call_json.return_value = (status, payload)
        with contextlib.ExitStack() as stack:
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            for name, value in [('load_manifest', [{'file': 'fixture.pdf', 'title': '합성 문서'}]),
                                ('DocGridClient', client), ('embedding_ready', True),
                                ('authenticate', None), ('read_password', 'synthetic-only'),
                                ('upload', {'documentId': 1}),
                                ('wait_for_indexing', ({1: 'INDEXED'}, {1}))]:
                stack.enter_context(patch.object(seed, name, return_value=value))
            result = seed.run(['--skip-embedding-check', '--rag-wait', '0'])
        self.assertNotEqual(result, 0)

    def test_search_http_error_must_fail(self):
        self.assert_search_failure(500, {'code': 'SYNTHETIC-ERROR'})

    def test_empty_results_must_fail(self):
        self.assert_search_failure(200, {'data': {'results': [], 'ragStatus': 'FAILED'}})


if __name__ == '__main__':
    unittest.main(verbosity=2)
