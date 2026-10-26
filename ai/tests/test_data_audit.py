"""Synthetic CSV audits expose aggregate defects without raw cells or inferred proof."""

import csv
import hashlib
import json

import pytest

from copenhagen_fds.data_audit import AuditInputError, audit_csv, main


def write_csv(tmp_path, header, rows):
    path = tmp_path / "synthetic.csv"
    with path.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(header)
        writer.writerows(rows)
    return path


def test_counts_hash_and_unknown_provenance_do_not_become_training_approval(tmp_path):
    path = write_csv(tmp_path, ["amount_krw", "user_account_age_days", "is_fraud"], [
        [10, 5, 0], [20, -1, 1], [30, 14, 0],
    ])
    payload = path.read_bytes()
    report = audit_csv(path)
    assert report["sha256"] == hashlib.sha256(payload).hexdigest()
    assert report["row_count"] == 3
    assert report["feature_count"] == 2
    assert report["label_counts"] == {"normal": 2, "fraud": 1, "invalid": 0}
    assert report["fraud_ratio_of_all_rows"] == 1 / 3
    assert report["column_statistics"]["user_account_age_days"]["negative"] == 1
    assert set(report["evidence_status"].values()) == {"NOT_VERIFIED"}
    assert all(not value for value in report["recognized_replay_columns"].values())
    assert path.read_bytes() == payload


def test_bad_numbers_missing_cells_and_invalid_labels_are_counted_without_values(tmp_path):
    marker = "synthetic-private-cell"
    path = write_csv(tmp_path, ["amount_krw", "is_fraud"], [[" ", ""], [marker, marker], ["NaN", "0.0"], ["Infinity", 1]])
    report = audit_csv(path)
    assert report["column_statistics"]["amount_krw"] == {"missing": 1, "nonnumeric": 1, "nonfinite": 2, "negative": 0}
    assert report["label_counts"] == {"normal": 0, "fraud": 1, "invalid": 3}
    assert report["fraud_ratio_of_all_rows"] == 0.25
    assert marker not in json.dumps(report)


def test_structural_checks_distinguish_mismatch_from_unavailable_inputs(tmp_path):
    path = write_csv(tmp_path, ["day_of_week", "is_weekend", "kst_hour", "is_night", "user_account_age_days", "is_fresh_account", "txn_count_10m", "txn_count_1h_calc", "txn_count_24h_calc", "is_fraud"], [
        [5, 1, 23, 1, 13, 1, 1, 2, 3, 0],
        [2, 1, 12, 1, -1, 1, 3, 2, 1, 1],
        ["", 0, "", 0, "", 0, "", 1, 2, 0],
    ])
    checks = audit_csv(path)["structural_checks"]
    for name in checks:
        expected = {"evaluated_rows": 0, "mismatched_rows": 0, "skipped_rows": 3} if name == "payment_method_exactly_one" else {"evaluated_rows": 2, "mismatched_rows": 1, "skipped_rows": 1}
        assert checks[name] == expected


def test_invalid_one_hot_is_rejected_even_when_sum_is_one(tmp_path):
    header = ["payment_method_apple_pay", "payment_method_bank_transfer", "payment_method_card", "payment_method_google_pay", "payment_method_paypal", "is_fraud"]
    path = write_csv(tmp_path, header, [[0, 0, 1, 0, 0, 0], [-1, 0, 2, 0, 0, 1], [0, 0, 0, 0, 0, 0]])
    assert audit_csv(path)["structural_checks"]["payment_method_exactly_one"] == {"evaluated_rows": 3, "mismatched_rows": 2, "skipped_rows": 0}


def test_recognized_identity_and_times_are_only_column_presence_evidence(tmp_path):
    path = write_csv(tmp_path, ["user_id", "event_id", "event_time", "available_at", "label_available_at", "is_fraud"], [["private-user", "private-event", "invalid-time", "invalid-time", "invalid-time", 0]])
    report = audit_csv(path)
    assert report["recognized_replay_columns"]["user_identity"] == ["user_id"]
    assert report["recognized_replay_columns"]["available_time"] == ["available_at"]
    assert report["evidence_status"]["point_in_time_correctness"] == "NOT_VERIFIED"
    assert "private-user" not in json.dumps(report)


@pytest.mark.parametrize("header,rows", [
    (["amount_krw", "amount_krw", "is_fraud"], [[1, 1, 0]]),
    (["", "is_fraud"], [[1, 0]]),
    (["amount_krw"], [[1]]),
    (["amount_krw", "is_fraud"], [[1, 0, "private-overflow"]]),
    (["amount_krw", "is_fraud"], [[1]]),
])
def test_malformed_csv_is_rejected_without_showing_cells(tmp_path, header, rows):
    path = write_csv(tmp_path, header, rows)
    with pytest.raises(AuditInputError) as caught:
        audit_csv(path)
    assert "private-overflow" not in str(caught.value)


def test_empty_rows_have_no_invented_fraud_ratio(tmp_path):
    path = write_csv(tmp_path, ["is_fraud"], [])
    report = audit_csv(path)
    assert report["row_count"] == 0
    assert report["fraud_ratio_of_all_rows"] is None


def test_cli_reports_json_and_uses_fixed_error_text(tmp_path, capsys):
    path = write_csv(tmp_path, ["amount_krw", "is_fraud"], [[100, 0]])
    assert main([str(path)]) == 0
    assert json.loads(capsys.readouterr().out)["row_count"] == 1
    path.write_text('amount_krw,is_fraud\n"synthetic-private-cell', encoding="utf-8")
    assert main([str(path)]) == 2
    captured = capsys.readouterr()
    assert captured.out == ""
    assert captured.err == "Dataset audit failed: unreadable or malformed CSV input.\n"
