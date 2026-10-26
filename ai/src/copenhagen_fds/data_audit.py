"""Offline CSV suitability evidence: aggregates only, without training or row output."""

import argparse
import csv
from decimal import Decimal, InvalidOperation
import hashlib
import io
import json
from pathlib import Path
import sys


EXPECTED_FEATURES = (
    "amount_krw", "log_amount", "amount_clipped", "user_avg_amount_prev",
    "user_median_amount_prev", "user_std_amount_prev", "user_txn_count_prev",
    "amount_ratio", "amount_deviation", "amount_zscore", "user_avg_amount_30d",
    "txn_count_10m", "txn_count_1h_calc", "txn_count_24h_calc", "kst_hour",
    "day_of_week", "is_weekend", "is_night", "user_avg_hour_prev", "hour_deviation",
    "user_account_age_days", "is_fresh_account", "payment_method_apple_pay",
    "payment_method_bank_transfer", "payment_method_card", "payment_method_google_pay",
    "payment_method_paypal",
)
PAYMENT_FEATURES = tuple(name for name in EXPECTED_FEATURES if name.startswith("payment_method_"))
REPLAY_COLUMNS = {
    "user_identity": ("user_id", "userId", "CUSTOMER_ID"),
    "event_identity": ("event_id", "transaction_id", "TRANSACTION_ID"),
    "event_time": ("event_time", "occurred_at", "purchase_time", "created_at", "TX_DATETIME"),
    "available_time": ("available_at", "available_time", "received_at", "ingested_at"),
    "label_available_time": ("label_available_at", "label_observed_at"),
    "currency": ("currency", "currency_code"),
}
CHECK_FIELDS = {
    "weekend_from_day_of_week": ("is_weekend", "day_of_week"),
    "night_from_kst_hour": ("is_night", "kst_hour"),
    "fresh_account_under_14_days": ("is_fresh_account", "user_account_age_days"),
    "payment_method_exactly_one": PAYMENT_FEATURES,
    "transaction_windows_nondecreasing": ("txn_count_10m", "txn_count_1h_calc", "txn_count_24h_calc"),
}


class AuditInputError(ValueError):
    """Input is malformed; never include a row or its values in the message."""


def _number(value: str) -> Decimal | None:
    try:
        return Decimal(value)
    except InvalidOperation:
        return None


def audit_csv(path: str | Path) -> dict:
    """Audit one immutable byte snapshot; no origin or leakage proof is inferred."""

    payload = Path(path).read_bytes()
    reader = csv.reader(io.StringIO(payload.decode("utf-8-sig"), newline=""), strict=True)
    header = next(reader, None)
    if not header or any(not name.strip() for name in header) or len(set(header)) != len(header):
        raise AuditInputError("CSV requires nonempty, unique column names")
    if "is_fraud" not in header:
        raise AuditInputError("CSV requires the is_fraud label column")
    stats = {name: {"missing": 0, "nonnumeric": 0, "nonfinite": 0, "negative": 0} for name in header}
    labels = {"normal": 0, "fraud": 0, "invalid": 0}
    checks = {name: {"evaluated_rows": 0, "mismatched_rows": 0, "skipped_rows": 0} for name in CHECK_FIELDS}
    row_count = 0
    for row in reader:
        if len(row) != len(header):
            raise AuditInputError("CSV row width must match the header")
        row_count += 1
        cells = dict(zip(header, row))
        numbers = {}
        for name, value in cells.items():
            if not value.strip():
                stats[name]["missing"] += 1
                continue
            number = _number(value)
            if number is None:
                stats[name]["nonnumeric"] += 1
            elif not number.is_finite():
                stats[name]["nonfinite"] += 1
            else:
                numbers[name] = number
                stats[name]["negative"] += number < 0
        labels[{"0": "normal", "1": "fraud"}.get(cells["is_fraud"], "invalid")] += 1
        for name, required in CHECK_FIELDS.items():
            check = checks[name]
            if any(field not in numbers for field in required):
                check["skipped_rows"] += 1
                continue
            check["evaluated_rows"] += 1
            if name == "weekend_from_day_of_week":
                valid = numbers["day_of_week"] in range(7) and numbers["is_weekend"] == int(numbers["day_of_week"] in (5, 6))
            elif name == "night_from_kst_hour":
                hour = numbers["kst_hour"]
                valid = hour in range(24) and numbers["is_night"] == int(hour >= 22 or hour <= 5)
            elif name == "fresh_account_under_14_days":
                age = numbers["user_account_age_days"]
                valid = age >= 0 and age == age.to_integral_value() and numbers["is_fresh_account"] == int(age < 14)
            elif name == "payment_method_exactly_one":
                values = [numbers[field] for field in required]
                valid = all(value in (0, 1) for value in values) and sum(values) == 1
            else:
                values = [numbers[field] for field in required]
                valid = all(value >= 0 and value == value.to_integral_value() for value in values) and values == sorted(values)
            check["mismatched_rows"] += not valid
    return {
        "audit_version": "erd-common-csv-audit-v1",
        "sha256": hashlib.sha256(payload).hexdigest(),
        "byte_count": len(payload),
        "row_count": row_count,
        "column_count": len(header),
        "feature_count": len(header) - 1,
        "columns": header,
        "missing_expected_features": sorted(set(EXPECTED_FEATURES) - set(header)),
        "unexpected_columns": sorted(set(header) - set(EXPECTED_FEATURES) - {"is_fraud"}),
        "label_counts": labels,
        "fraud_ratio_of_all_rows": labels["fraud"] / row_count if row_count else None,
        "column_statistics": stats,
        "structural_checks": checks,
        "recognized_replay_columns": {name: [field for field in aliases if field in header] for name, aliases in REPLAY_COLUMNS.items()},
        "evidence_status": {
            "origin": "NOT_VERIFIED", "usage_rights": "NOT_VERIFIED",
            "label_basis": "NOT_VERIFIED", "feature_generation": "NOT_VERIFIED",
            "point_in_time_correctness": "NOT_VERIFIED",
        },
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Print only schema, hashes and aggregate CSV checks; never train or modify data.")
    parser.add_argument("csv_path", type=Path)
    args = parser.parse_args(argv)
    try:
        report = audit_csv(args.csv_path)
    except (OSError, UnicodeError, csv.Error, AuditInputError):
        print("Dataset audit failed: unreadable or malformed CSV input.", file=sys.stderr)
        return 2
    print(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
