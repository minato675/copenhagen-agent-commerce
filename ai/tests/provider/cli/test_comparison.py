"""304: expected values cannot turn failures or unexecuted handlers into success."""

import pytest

from copenhagen_fds.provider_cli.comparison import compare


def test_assertions_report_actual_failure_and_not_run():
    actual = dict(status="UNAUTHENTICATED", handlers=0, calls=0, reasons=[])
    report = compare({"auth": actual}, {"auth": dict(status="UNAUTHENTICATED", handlers=0, calls=0)})
    assert report["status"] == "PASSED"
    assert report["rows"][0]["observed"]["status"] == "UNAUTHENTICATED"
    assert compare({"auth": actual}, {"auth": dict(status="SUCCESS")})["status"] == "FAILED"
    assert compare({"auth": actual}, {"auth": dict(calls=False)})["status"] == "FAILED"


@pytest.mark.parametrize("expected", [{}, {"extra": {}}, {"case": {}}, {"case": {"unknown": 1}},
    {"case": {"reasons_contain": []}}, {"case": {"reasons_contain": "not-list"}}])
def test_incomplete_or_invalid_assertions_rejected(expected):
    with pytest.raises(ValueError):
        compare({"case": dict(reasons=[])}, expected)


def test_reason_checks_require_actual_evidence():
    actual = {"case": dict(reasons=["INTENT_MISMATCH", "WITHIN_POLICY"])}
    assert compare(actual, {"case": dict(reasons_contain=["INTENT_MISMATCH"])})["status"] == "PASSED"
    assert compare(actual, {"case": dict(reasons_contain=["INTENT_UNKNOWN"])})["status"] == "FAILED"
