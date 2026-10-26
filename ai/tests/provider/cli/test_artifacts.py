"""304: original sources, previous results and exact wire bytes are protected."""

import pytest

from copenhagen_fds.provider_cli.artifacts import read_bundle, read_verified, reserve, write_bundle


def test_raw_bytes_and_deferred_expected_read(tmp_path, monkeypatch):
    from copenhagen_fds.provider_cli import artifacts
    output = tmp_path / 'bundle'
    reserve(output)
    raw = b'{ "not": "canonical" }\n'
    write_bundle(output, {'request.json': raw, 'expected.json': {'secret-test-marker': True}}, kind='demo')
    seen = []
    original = artifacts.read_bytes
    def counted(path):
        seen.append(path.name)
        return original(path)
    monkeypatch.setattr(artifacts, 'read_bytes', counted)
    manifest, values = read_bundle(output, kind='demo', defer_expected=True)
    assert values['request.json'] == raw
    assert 'expected.json' not in seen
    read_verified(output, 'expected.json', manifest)
    assert seen[-1] == 'expected.json'


@pytest.mark.parametrize('target', ['source', 'source/nested', 'existing'])
def test_sources_and_previous_results_not_overwritten(tmp_path, target):
    source = tmp_path / 'source'
    source.mkdir()
    (tmp_path / 'existing').mkdir()
    with pytest.raises(ValueError):
        reserve(tmp_path / target, source)
    assert list(source.iterdir()) == []


@pytest.mark.parametrize('kind', ['changed', 'missing', 'extra'])
def test_inventory_or_content_corruption_rejected(tmp_path, kind):
    source = tmp_path / 'bundle'
    reserve(source)
    write_bundle(source, {'request.json': {}}, kind='demo')
    if kind == 'changed':
        (source / 'request.json').write_text('{"changed":true}')
    elif kind == 'missing':
        (source / 'request.json').unlink()
    else:
        (source / 'extra.json').write_text('{}')
    with pytest.raises(ValueError):
        read_bundle(source, kind='demo')


def test_path_traversal_rejected(tmp_path):
    reserve(tmp_path / 'bundle')
    with pytest.raises(ValueError):
        write_bundle(tmp_path / 'bundle', {'../outside.json': {}}, kind='demo')
    assert not (tmp_path / 'outside.json').exists()
