"""Prepare a fresh synthetic HTTP request and trusted Mock settings, without secrets."""

import argparse
from datetime import datetime, timedelta, timezone
from pathlib import Path

from copenhagen_fds.api.settings import HttpSettings
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.contracts.personal_replay import PersonalReplaySettings
from copenhagen_fds.contracts.provider_evaluation import ProviderEvaluationRequest, evaluation_configuration_hash
from copenhagen_fds.native_cli.scenarios import demo_request, demo_script, demo_settings, observable_demo
from copenhagen_fds.native_cli.storage import check_new_output
from copenhagen_fds.provider_runtime.boundary import request_scope


def _shift(value, delta):
    if isinstance(value, datetime):
        return value + delta
    if isinstance(value, dict):
        return {key: _shift(item, delta) for key, item in value.items()}
    if isinstance(value, (tuple, list)):
        return [_shift(item, delta) for item in value]
    return value


def demo_inputs(settings_path: Path):
    """Only known synthetic fixtures determine demo permissions, never HTTP input."""
    original = PersonalReplaySettings.model_validate_json(settings_path.read_bytes())
    seed = demo_request("http-demo", observable_demo(), demo_settings(original))
    delta = datetime.now(timezone.utc) - timedelta(seconds=1) - seed.feature_input.evaluation.evaluated_at
    native = EngineEvaluationRequest.model_validate(_shift(seed.model_dump(), delta))
    request = ProviderEvaluationRequest(operation="EVALUATE", evaluation=native,
        call=dict(request_id="http-demo", trace_id=native.trace_id,
                  sent_at=native.feature_input.evaluation.evaluated_at),
        configuration=dict(configuration_id="http-demo", version="v1",
                           content_sha256=evaluation_configuration_hash(native)))
    scope = request_scope(request)
    script = demo_script(native)
    settings = HttpSettings(mode="MOCK", service_id="synthetic-be",
        grants=[{name: sorted(getattr(scope, name)) for name in ("user_ids", "profile_ids", "cohort_ids")}],
        configurations=[request.configuration],
        limits=dict(max_body_bytes=2_000_000, max_depth=64, max_nodes=100_000,
            max_collection_items=10_000, max_string_chars=50_000, max_integer_abs=10**15, max_float_abs=1e15),
        timeout_seconds=60.0, cleanup_timeout_seconds=1.0, max_response_bytes=2_000_000,
        body_read_timeout_seconds=5.0, max_concurrent_evaluations=2,
        mock=dict(extraction=script.extraction, comparison=script.comparison))
    return settings, request


def prepare(settings_path: Path, output: Path):
    check_new_output(output, settings_path)
    settings, request = demo_inputs(settings_path)
    output.mkdir(parents=True, exist_ok=False)
    for name, model in (("server.json", settings), ("request.json", request)):
        with (output / name).open("x", encoding="utf-8") as stream:
            stream.write(model.model_dump_json(indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--settings", type=Path, default=Path("examples/personal-replay-settings.json"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        prepare(args.settings, args.output)
    except (ValueError, OSError):
        print("Preparation failed: check synthetic settings and a new output directory.")
        return 2
    print("PREPARED: server.json and request.json; set AI_HTTP_SERVICE_TOKEN separately. No GMS call.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
