"""S15P21A207-293: explicit scripted provider payloads and native input files."""

from datetime import timedelta
from types import SimpleNamespace

import pytest

from copenhagen_fds.native_cli.runtime import MockScript
from copenhagen_fds.synthetic.storage import canonical


@pytest.fixture
def cli_cases(final_cases, tmp_path):
    def make(**kwargs):
        case = final_cases.prepare(**kwargs)
        script = MockScript(clock_start=case.request.feature_input.evaluation.evaluated_at + timedelta(seconds=1),
            **case.adapter.outcomes)
        source = tmp_path / "input.json"
        script_path = tmp_path / "script.json"
        source.write_text(canonical(case.request.model_dump(mode="json")), encoding="utf-8")
        script_path.write_text(script.model_dump_json(), encoding="utf-8")
        return SimpleNamespace(request=case.request, script=script, source=source, script_path=script_path,
            output=tmp_path / "run")
    return make
