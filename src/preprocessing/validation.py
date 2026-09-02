"""Post-processing validation checks for FDS datasets."""

from __future__ import annotations

import logging
from dataclasses import dataclass, field

import numpy as np
import pandas as pd

from .clean import identify_identifier_columns, identify_leakage_columns
from .config import PreprocessingConfig


LOGGER = logging.getLogger(__name__)


@dataclass
class ValidationResult:
    """Validation checks, errors and non-fatal warnings."""

    checks: dict[str, bool] = field(default_factory=dict)
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        """Whether all required checks passed."""

        return not self.errors and all(self.checks.values())


def validate_final_dataset(
    processed: pd.DataFrame,
    model_features: pd.DataFrame,
    original_rows: int,
    config: PreprocessingConfig,
    timestamp_column: str | None = None,
    logger: logging.Logger | None = None,
) -> ValidationResult:
    """Run the requested target, leakage, finite-value, order and row-count checks."""

    active_logger = logger or LOGGER
    result = ValidationResult()

    target_exists = config.target_col in model_features.columns
    result.checks["target_exists"] = target_exists
    if not target_exists:
        result.errors.append(f"'{config.target_col}' is missing from final model features.")
    else:
        target_values = pd.to_numeric(model_features[config.target_col], errors="coerce")
        binary = target_values.notna().all() and set(target_values.unique()).issubset({0, 1})
        result.checks["target_binary_0_1"] = bool(binary)
        if not binary:
            result.errors.append("Final target is not binary 0/1.")

    identifier_columns = identify_identifier_columns(
        [column for column in model_features.columns if column != config.target_col]
    )
    identifiers_removed = not identifier_columns
    result.checks["identifiers_removed"] = identifiers_removed
    if not identifiers_removed:
        result.errors.append(f"Identifier columns remain in model features: {identifier_columns}")

    leakage_columns, _ = identify_leakage_columns(model_features.columns.tolist(), config.target_col)
    leakage_removed = not leakage_columns
    result.checks["leakage_removed"] = leakage_removed
    if not leakage_removed:
        result.errors.append(f"Leakage columns remain in model features: {list(leakage_columns)}")

    numeric_columns = model_features.select_dtypes(include=[np.number]).columns.tolist()
    finite = True
    for column in numeric_columns:
        values = pd.to_numeric(model_features[column], errors="coerce").to_numpy(dtype="float64")
        if not np.isfinite(values).all():
            finite = False
            result.errors.append(f"Numeric model feature '{column}' contains infinity or NaN.")
    result.checks["numeric_features_finite"] = finite

    if "amount_ratio" in processed.columns:
        ratios = pd.to_numeric(processed["amount_ratio"], errors="coerce").to_numpy(dtype="float64")
        ratio_safe = np.isfinite(ratios).all()
        result.checks["amount_ratio_safe"] = bool(ratio_safe)
        if not ratio_safe:
            result.errors.append("amount_ratio contains NaN or infinity after safe division handling.")
    else:
        result.checks["amount_ratio_safe"] = True
        result.warnings.append("amount_ratio was skipped because the input had no usable amount/user history.")

    sort_column = "timestamp_utc" if "timestamp_utc" in processed.columns else timestamp_column
    if sort_column is not None and sort_column in processed.columns:
        parsed = pd.to_datetime(processed[sort_column], errors="coerce", utc=True).dropna()
        sorted_ok = bool(parsed.is_monotonic_increasing)
        result.checks["timestamp_sorted"] = sorted_ok
        if not sorted_ok:
            result.errors.append(f"Timestamp column '{sort_column}' is not sorted ascending.")
    else:
        result.checks["timestamp_sorted"] = True
        result.warnings.append("Timestamp ordering check was skipped because no timestamp was detected.")

    drop_fraction = 0.0 if original_rows == 0 else 1.0 - (len(processed) / original_rows)
    row_count_ok = drop_fraction <= config.max_allowed_row_drop_fraction
    result.checks["row_count_not_abnormally_reduced"] = bool(row_count_ok)
    if not row_count_ok:
        result.errors.append(
            f"Processed rows dropped by {drop_fraction:.2%}, exceeding configured limit {config.max_allowed_row_drop_fraction:.2%}."
        )

    for check, passed in result.checks.items():
        active_logger.info("Validation %-40s %s", check, "PASS" if passed else "FAIL")
    for warning in result.warnings:
        active_logger.warning("Validation warning: %s", warning)
    for error in result.errors:
        active_logger.error("Validation error: %s", error)
    return result
