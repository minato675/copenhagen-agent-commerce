"""Offline contract regressions through existing native, provider and SDK paths.

Scripted completions are controlled inputs, not observations of language-model
quality. Injection cases exercise prompt construction and response contracts;
they do not establish a real model's resistance to malicious instructions.
"""

from pathlib import Path

from copenhagen_fds.contracts.personal_replay import PersonalReplaySettings
from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.evaluation.cases import load_dataset
from copenhagen_fds.evaluation.grading import evaluate
from copenhagen_fds.evaluation.offline import OfflineViolation, deny_network, run_mock_dataset
from copenhagen_fds.native_cli.experiments import run_suite as run_native_suite
from copenhagen_fds.native_cli.runtime import fingerprint
from copenhagen_fds.native_cli.scenarios import demo_request, demo_script, demo_settings, observable_demo, prepare_demo
from copenhagen_fds.native_cli.storage import check_new_output, evaluate_file, write_artifacts
from copenhagen_fds.personal_replay_export import read_json
from copenhagen_fds.provider_cli.preparation import prepare as prepare_provider
from copenhagen_fds.provider_cli.workflow import run_suite as run_provider_suite
from copenhagen_fds.synthetic.storage import digest


SCHEMA = "temporal-benchmark-regressions-v1"
SUITE_EXPECTED_COUNTS = {"native": 22, "provider": 27, "intent": 35, "matrix": 8}
MATRIX_SIGNALS = ("MATCH", "MISMATCH", "UNKNOWN", "FAILED")


def _counts(rows: list[dict], *, expected: int) -> dict:
    if type(expected) is not int or expected < 0 or len(rows) > expected:
        raise ValueError("regression result count exceeds its declared suite")
    ids = [row.get("case_id") for row in rows]
    if any(not isinstance(value, str) or not value for value in ids) or len(set(ids)) != len(ids):
        raise ValueError("regression case identities must be present and distinct")
    if any(type(row.get("passed")) is not bool for row in rows):
        raise ValueError("regression checks must report an explicit boolean result")
    passed = sum(row["passed"] for row in rows)
    return dict(n_total=expected, n_passed=passed, n_failed=len(rows) - passed,
                n_not_run=expected - len(rows))


def _suite_result(rows, name, **metadata):
    counts = _counts(rows, expected=SUITE_EXPECTED_COUNTS[name])
    return dict(status="PASSED" if counts["n_failed"] == counts["n_not_run"] == 0 else "FAILED",
                **counts, **metadata)


async def _native(settings_path, output):
    source, destination = output / "native-inputs", output / "native-run"
    prepare_demo(settings_path, source)
    await run_native_suite(source, destination)
    comparisons, reuse = read_json(destination / "comparisons.json"), read_json(destination / "reuse.json")
    rows = [dict(case_id="evaluation:" + row["case_id"], passed=row["passed"]) for row in comparisons["rows"]]
    rows += [dict(case_id="reuse:" + row["case_id"], passed=row["passed"]) for row in reuse["rows"]]
    return _suite_result(rows, "native", evaluation_cases=len(comparisons["rows"]), reuse_checks=len(reuse["rows"]),
                         manifest="native-run/manifest.json", expected_exception_cases_are_contract_checks=True)


async def _provider(settings_path, output):
    source, destination = output / "provider-inputs", output / "provider-run"
    prepare_provider(settings_path, source)
    await run_provider_suite(source, destination)
    report = read_json(destination / "comparisons.json")
    return _suite_result(report["rows"], "provider", manifest="provider-run/manifest.json",
                         scope="REAL_IN_PROCESS_PROVIDER_CONTRACT_RUNTIME; NO_HTTP_SERVICE_OR_DATABASE")


async def _intent(settings_path, output):
    dataset = load_dataset()
    batch = await run_mock_dataset(dataset)
    report = evaluate(dataset, batch)
    destination = output / "intent-run"
    destination.mkdir(parents=True, exist_ok=False)
    write_artifacts(destination, {"observations.json": batch.model_dump(mode="json"), "report.json": report},
                    dict(schema_version="temporal-intent-regression-v1", code=fingerprint(),
                         dataset_sha256=dataset.sha256, mode="MOCK", external_api="NOT_RUN"))
    return _suite_result(report["cases"], "intent", manifest="intent-run/manifest.json",
                         interpretation=report["interpretation"], dataset_sha256=dataset.sha256,
                         prompt_injection_claim="SCRIPTED_PROMPT_AND_RESPONSE_CONTRACTS_ONLY")


def _matrix_checks(summaries):
    expected_ids = {f"{regime}-{signal.lower()}" for regime in ("low", "high") for signal in MATRIX_SIGNALS}
    if set(summaries) != expected_ids:
        raise ValueError("controlled matrix requires all eight behavior/semantic combinations")
    rows = []
    for regime in ("low", "high"):
        reference = summaries[f"{regime}-match"]
        for signal in MATRIX_SIGNALS:
            case_id = f"{regime}-{signal.lower()}"
            current = summaries[case_id]
            outcome, comparison = current["outcome"], current["analysis"]["comparison"]
            status = "FAILED" if signal == "FAILED" else "INCOMPLETE" if signal == "UNKNOWN" else "SUCCESS"
            expected_risk = regime.upper() if status == "SUCCESS" else "UNKNOWN"
            advice = ("REQUIRE_AUTH" if regime == "high" else "REVIEW_ORDER" if signal == "MISMATCH"
                      else "NO_ADDITIONAL_REVIEW") if status == "SUCCESS" else None
            checks = dict(
                same_request=current["request_sha256"] == reference["request_sha256"],
                behavior_score_preserved=current["behavior_score"] is not None and
                    current["behavior_score"] == reference["behavior_score"],
                features_preserved=current["features"] == reference["features"],
                outcome_status=outcome["status"] == status, risk=outcome["risk_level"] == expected_risk,
                recommendation=outcome["recommendation"] == advice,
                comparison_status=comparison is not None and comparison["status"] == ("FAILED" if signal == "FAILED" else "SUCCESS"),
                comparison_signal=comparison is not None and comparison["signal"] == (None if signal == "FAILED" else signal),
                review_preserved=("RETRY_EVALUATION" in outcome["review_actions"] if signal == "FAILED" else
                                  "VERIFY_USER_INTENT" in outcome["review_actions"] if signal == "UNKNOWN" else True),
                offline=current["mode"] == "MOCK" and current["external_api"] == "NOT_RUN",
            )
            rows.append(dict(case_id=case_id, regime=regime, scripted_signal=signal, passed=all(checks.values()),
                             checks=checks, behavior_score=current["behavior_score"], outcome=outcome,
                             request_sha256=current["request_sha256"], result_sha256=current["result_sha256"]))
    return rows


async def _matrix(settings_path, output):
    original = PersonalReplaySettings.model_validate(read_json(settings_path))
    settings, observations = demo_settings(original), observable_demo()
    source, destination = output / "matrix-inputs", output / "matrix-run"
    source.mkdir(parents=True, exist_ok=False)
    artifacts = {"demo-settings.json": settings.model_dump(mode="json"), "observations.json": observations}
    cases = []
    for regime in ("low", "high"):
        request = demo_request("matrix-" + regime, observations, settings, profile="full",
                               amount=10000 if regime == "low" else 1000000, burst=regime == "high")
        artifacts[regime + ".request.json"] = request.model_dump(mode="json")
        for signal in MATRIX_SIGNALS:
            case_id = f"{regime}-{signal.lower()}"
            script = demo_script(request, failure="comparison") if signal == "FAILED" else demo_script(request, signal=signal)
            artifacts[case_id + ".script.json"] = script.model_dump(mode="json")
            cases.append((case_id, regime))
    write_artifacts(source, artifacts, dict(schema_version="temporal-controlled-matrix-input-v1", code=fingerprint(),
                    origin="SYNTHETIC", behavior="REAL_SCORER; AMOUNT_AND_HISTORY_INPUTS_NOT_INJECTED_FINAL_SCORES",
                    semantics="SCRIPTED_COMPLETIONS", evaluated=False))
    summaries = {}
    for case_id, regime in cases:
        summaries[case_id] = await evaluate_file(source / (regime + ".request.json"), destination / case_id,
                                               script_path=source / (case_id + ".script.json"))
    rows = _matrix_checks(summaries)
    write_artifacts(destination, {"comparisons.json": dict(rows=rows)},
                    dict(schema_version="temporal-controlled-matrix-v1", code=fingerprint(), mode="MOCK", external_api="NOT_RUN"))
    return _suite_result(rows, "matrix", manifest="matrix-run/manifest.json",
                         interpretation="CONTROLLED_POLICY_RESPONSE_NOT_FRAUD_OR_LANGUAGE_MODEL_ACCURACY")


async def run_regressions(settings_path: Path, output: Path) -> dict:
    """Run all four fixed offline suites once into a new immutable directory.

    A suite exception aborts later suites, preserves completed artifacts and
    records unverified checks as NOT_RUN. Exception text is never exported.
    The 22 native checks comprise 17 evaluations and 5 result-reuse checks.
    Ordinary intentional provider/GPT failures pass when their contract holds.
    """
    settings_path, output = Path(settings_path), Path(output)
    check_new_output(output, settings_path)
    PersonalReplaySettings.model_validate(read_json(settings_path))
    source_hash, code = digest(settings_path), fingerprint()
    output.mkdir(parents=True, exist_ok=False)
    results = {name: dict(status="NOT_RUN", n_total=count, n_passed=0, n_failed=0, n_not_run=count)
               for name, count in SUITE_EXPECTED_COUNTS.items()}
    error = None
    with deny_network():
        for name, run in (("native", _native), ("provider", _provider), ("intent", _intent), ("matrix", _matrix)):
            try:
                results[name] = await run(settings_path, output)
            except (Exception, OfflineViolation) as exc:
                error = "OFFLINE_BOUNDARY_VIOLATION" if isinstance(exc, OfflineViolation) else "SUITE_EXECUTION_ERROR"
                results[name] = {**results[name], "status": "FAILED", "execution_error": error}
                break
    if fingerprint() != code or digest(settings_path) != source_hash:
        error = "CODE_OR_SETTINGS_CHANGED_DURING_RUN"
    counts = {key: sum(result[key] for result in results.values())
              for key in ("n_total", "n_passed", "n_failed", "n_not_run")}
    summary = dict(status="PASSED" if error is None and all(row["status"] == "PASSED" for row in results.values()) else "FAILED",
                   **counts, suites=results, execution_error=error, external_api="NOT_RUN", mode="MOCK",
                   source_settings_sha256=source_hash, code_sha256=code["source_sha256"],
                   interpretation="SCRIPTED_CONTRACT_REGRESSION_NOT_MODEL_QUALITY",
                   notes=["Expected exception cases are successful checks when failure is preserved correctly.",
                          "Mock injection cases do not establish real language-model attack resistance.",
                          "Behavior scores use the real engine and five-sample demo readiness, not calibrated operating settings.",
                          "Provider error UUIDs and SDK observation timestamps may differ on a rerun; hashes audit this recorded run.",
                          "No Core/Pay HTTP service, persistent database, real GMS call or payment execution is performed."])
    children = {path.relative_to(output).as_posix(): digest(path) for path in sorted(output.rglob("*.json"))}
    write_artifacts(output, {"summary.json": summary}, dict(schema_version=SCHEMA, code=code,
                    source_settings_sha256=source_hash, external_api="NOT_RUN", child_artifact_sha256=children,
                    summary_sha256=personalization_hash(summary)))
    return summary


def verify_regressions(output: Path) -> dict:
    """Audit recorded evidence without rerunning suites or making provider calls.

    Hashes detect changes against the saved manifest, not an adversary who can
    rewrite the whole bundle. A matching current source fingerprint is reported
    separately; artifact verification alone does not reproduce engine execution.
    """
    output = Path(output)
    if output.is_symlink() or not output.is_dir():
        raise ValueError("regression output must be a regular directory")
    manifest = read_json(output / "manifest.json")
    if (manifest.get("schema_version") != SCHEMA or manifest.get("external_api") != "NOT_RUN"
            or set(manifest.get("artifact_sha256", {})) != {"summary.json"}):
        raise ValueError("unsupported regression manifest")
    children = manifest.get("child_artifact_sha256")
    if not isinstance(children, dict):
        raise ValueError("regression manifest requires child artifact hashes")
    expected = {"manifest.json", "summary.json", *children}
    actual = set()
    for path in output.rglob("*"):
        if path.is_symlink():
            raise ValueError("regression artifacts cannot contain symlinks")
        if path.is_file():
            actual.add(path.relative_to(output).as_posix())
    if actual != expected:
        raise ValueError("regression artifact inventory changed")
    for name, expected_hash in {"summary.json": manifest["artifact_sha256"]["summary.json"], **children}.items():
        relative = Path(name)
        if (not isinstance(name, str) or relative.is_absolute() or relative.as_posix() != name
                or ".." in relative.parts or relative.suffix != ".json"
                or name in {"manifest.json", "summary.json"} and name in children):
            raise ValueError("invalid regression artifact path")
        path = output / relative
        if output.resolve() not in path.resolve().parents or digest(path) != expected_hash:
            raise ValueError("regression artifact hash changed")
    summary = read_json(output / "summary.json")
    if (personalization_hash(summary) != manifest.get("summary_sha256")
            or summary.get("source_settings_sha256") != manifest.get("source_settings_sha256")
            or summary.get("code_sha256") != manifest.get("code", {}).get("source_sha256")
            or summary.get("mode") != "MOCK" or summary.get("external_api") != "NOT_RUN"):
        raise ValueError("regression summary provenance changed")
    suites = summary.get("suites")
    if not isinstance(suites, dict) or set(suites) != set(SUITE_EXPECTED_COUNTS):
        raise ValueError("regression suite inventory changed")
    for name, result in suites.items():
        counts = [result.get(key) for key in ("n_total", "n_passed", "n_failed", "n_not_run")]
        if (any(type(value) is not int or value < 0 for value in counts)
                or counts[0] != SUITE_EXPECTED_COUNTS[name] or sum(counts[1:]) != counts[0]
                or result.get("status") not in {"PASSED", "FAILED", "NOT_RUN"}
                or result["status"] == "PASSED" and (counts[2] or counts[3])
                or result["status"] == "NOT_RUN" and counts[3] != counts[0]):
            raise ValueError("regression suite counts or status changed")
    for key in ("n_total", "n_passed", "n_failed", "n_not_run"):
        if type(summary.get(key)) is not int or summary[key] != sum(result[key] for result in suites.values()):
            raise ValueError("regression total counts changed")
    expected_status = "PASSED" if summary.get("execution_error") is None and all(
        result["status"] == "PASSED" for result in suites.values()) else "FAILED"
    if summary.get("status") != expected_status:
        raise ValueError("regression final status changed")
    return dict(status="VERIFIED", scenario_status=summary["status"], verification="RECORDED_ARTIFACTS_ONLY",
                execution_repeated=False, external_api="NOT_RUN", artifact_count=len(actual),
                current_code_matches=manifest["code"] == fingerprint(), manifest_sha256=digest(output / "manifest.json"))
