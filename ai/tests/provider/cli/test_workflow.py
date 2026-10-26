"""304: immutable suite runs and execution-before-expectation lifecycle."""
from hashlib import sha256
import json
import pytest
from copenhagen_fds.provider_cli import workflow
from copenhagen_fds.provider_cli.artifacts import json_bytes


def test_output_reserved_before_handler(prepared, tmp_path, run_async, monkeypatch):
    output = tmp_path / 'existing'
    output.mkdir()
    async def forbidden(*args):
        pytest.fail('must reject the output before execution')
    monkeypatch.setattr(workflow, 'run_mock', forbidden)
    with pytest.raises(ValueError):
        run_async(workflow.run_suite(prepared, output))
    assert list(output.iterdir()) == []


def test_full_suite_then_reproduction_and_modified_report_detection(prepared, tmp_path, run_async, monkeypatch):
    output = tmp_path / 'run'
    before = {p.name: p.read_bytes() for p in prepared.iterdir()}
    executed, reads = [], []
    native_run, native_read = workflow.run_mock, workflow.read_verified
    async def counted(*args):
        result = await native_run(*args)
        executed.append(args[1].operation)
        return result
    def read(source, name, manifest):
        if name == 'expected.json':
            reads.append(len(executed))
        return native_read(source, name, manifest)
    monkeypatch.setattr(workflow, 'run_mock', counted)
    monkeypatch.setattr(workflow, 'read_verified', read)
    summary = run_async(workflow.run_suite(prepared, output))
    assert summary['status'] == 'PASSED' and summary['cases'] == 27
    assert reads == [27]
    assert before == {p.name: p.read_bytes() for p in prepared.iterdir()}
    verified = run_async(workflow.verify_suite(output))
    assert verified['status'] == 'REPRODUCED' and verified['scenario_status'] == 'PASSED'
    assert reads == [27, 54]
    # A recomputed checksum is not enough: verification actually reruns and compares.
    path = output / 'comparisons.json'
    changed = json.loads(path.read_bytes())
    changed['rows'][0]['observed']['status'] = 'forged'
    path.write_bytes(json_bytes(changed))
    manifest_path = output / 'manifest.json'
    manifest = json.loads(manifest_path.read_bytes())
    manifest['artifact_sha256']['comparisons.json'] = sha256(path.read_bytes()).hexdigest()
    manifest_path.write_bytes(json_bytes(manifest))
    # Use already observed actual outputs to avoid a redundant third full engine run.
    actual_artifacts = {p.name: json.loads(p.read_bytes()) for p in output.iterdir()
        if p.name.endswith(('.response.json', '.execution.json'))}
    actual_observed = {r['case_id']: r['observed'] for r in changed['rows']}
    actual_observed['cold-normal']['status'] = 'SUCCESS'
    async def replay(_):
        return actual_artifacts, actual_observed
    monkeypatch.setattr(workflow, 'execute_cases', replay)
    with pytest.raises(ValueError, match='comparison report'):
        run_async(workflow.verify_suite(output))
