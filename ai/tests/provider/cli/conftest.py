"""304: isolated prepared provider fixture bundles for file workflows."""
from pathlib import Path
import pytest
from copenhagen_fds.provider_cli.preparation import prepare

@pytest.fixture
def prepared(tmp_path):
    source = tmp_path / 'source'
    prepare(Path('examples/personal-replay-settings.json'), source)
    return source
