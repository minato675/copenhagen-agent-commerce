"""304: exported schemas are the implemented provider contracts."""
import json
from copenhagen_fds.provider_contracts import CONTRACT_MODELS


def test_exported_schemas_equal_current_contracts(prepared):
    for name, model in CONTRACT_MODELS.items():
        assert json.loads((prepared / (name + '.schema.json')).read_bytes()) == model.model_json_schema()
    manifest = json.loads((prepared / 'manifest.json').read_bytes())
    assert manifest['evaluated'] is False
    assert manifest['mode'] == 'MOCK'
