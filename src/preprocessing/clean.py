"""Cleaning, target validation, leakage removal and missing-value handling."""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from typing import Any

import numpy as np
import pandas as pd

from .config import PreprocessingConfig
from .load_data import MissingTargetError, normalize_column_name


LOGGER = logging.getLogger(__name__)


LEAKAGE_EXACT_COLUMNS: tuple[str, ...] = (
    "fraud_flag_ip",
    "fraud_flag_velocity",
    "fraud_flag_email",
    "fraud_flag_mismatch",
    "fraud_flag_fresh_account",
    "fraud_flag_high_value",
)
LEAKAGE_NAME_TOKENS: tuple[str, ...] = (
    "fraud_flag",
    "fraud_score",
    "risk_label",
    "fraud_reason",
)

IDENTIFIER_EXACT_NAMES: tuple[str, ...] = (
    "transaction_id",
    "transactionid",
    "user_id",
    "userid",
    "visitor_id",
    "visitorid",
    "customer_id",
    "customerid",
    "account_id",
    "accountid",
    "order_id",
    "orderid",
    "session_id",
    "sessionid",
    "device_id",
    "deviceid",
    "payment_id",
    "paymentid",
    "ip_address",
    "ipaddress",
    "email",
    "card_number",
    "cardnumber",
)

KNOWN_NUMERIC_NAMES: tuple[str, ...] = (
    "amount",
    "amount_aed",
    "amount_krw",
    "avg_item_price",
    "avg_item_price_aed",
    "avg_item_price_krw",
    "items_count",
    "user_account_age_days",
    "user_prev_chargebacks",
    "user_is_high_risk",
    "card_age_days",
    "card_country_match",
    "shipping_billing_match",
    "ip_risk_score",
    "transactions_last_1h",
    "transactions_last_24h",
    "txn_count_10m",
    "txn_count_1h_calc",
    "txn_count_24h_calc",
)


@dataclass
class CleaningResult:
    """Outputs and audit information from the cleaning stage."""

    frame: pd.DataFrame
    removed_leakage_columns: dict[str, str] = field(default_factory=dict)
    leakage_warnings: list[str] = field(default_factory=list)
    target_rows_removed: int = 0
    missing_before: dict[str, int] = field(default_factory=dict)
    missing_after: dict[str, int] = field(default_factory=dict)
    warnings: list[str] = field(default_factory=list)


def _normalized_set(values: tuple[str, ...]) -> set[str]:
    return {normalize_column_name(value) for value in values}


def identify_leakage_columns(columns: list[str], target_col: str) -> tuple[dict[str, str], list[str]]:
    """Find direct fraud-rule columns and warn on suspicious names.

    ``ip_risk_score`` is intentionally not matched: it is an independently
    observable service-time risk feature, not a target-derived flag.
    """

    exact_names = _normalized_set(LEAKAGE_EXACT_COLUMNS)
    token_names = tuple(normalize_column_name(token) for token in LEAKAGE_NAME_TOKENS)
    target_normalized = normalize_column_name(target_col)
    removed: dict[str, str] = {}
    warnings: list[str] = []
    for column in columns:
        normalized = normalize_column_name(column)
        if normalized == target_normalized:
            continue
        if normalized in exact_names:
            removed[column] = "direct fraud-label generation rule / target proxy"
            continue
        matched = next((token for token in token_names if token in normalized), None)
        if matched is not None:
            removed[column] = f"column name contains leakage token '{matched}'"
            warnings.append(
                f"Removed leakage-risk column '{column}' because its name contains '{matched}'."
            )
    return removed, warnings


def remove_leakage_columns(
    frame: pd.DataFrame,
    target_col: str,
    logger: logging.Logger | None = None,
) -> tuple[pd.DataFrame, dict[str, str], list[str]]:
    """Drop leakage-risk columns from the sanitized working frame."""

    active_logger = logger or LOGGER
    removed, warnings = identify_leakage_columns(frame.columns.tolist(), target_col)
    if removed:
        active_logger.warning("Leakage columns removed: %s", list(removed))
        for column, reason in removed.items():
            active_logger.warning("Removed '%s': %s", column, reason)
        frame = frame.drop(columns=list(removed), errors="ignore")
    else:
        active_logger.info("No leakage-risk columns detected.")
    return frame, removed, warnings


def identify_identifier_columns(columns: list[str]) -> list[str]:
    """Return direct identifier columns for final model-input removal."""

    exact_names = _normalized_set(IDENTIFIER_EXACT_NAMES)
    substring_tokens = (
        "transactionid",
        "userid",
        "visitorid",
        "customerid",
        "accountid",
        "orderid",
        "sessionid",
        "deviceid",
        "paymentid",
        "ipaddress",
        "email",
        "cardnumber",
    )
    identified: list[str] = []
    for column in columns:
        normalized = normalize_column_name(column)
        # A domain category is not the same as a raw email identifier and is
        # explicitly allowed to remain as a low-cardinality categorical feature.
        if "emaildomain" in normalized:
            continue
        if normalized in exact_names or any(token in normalized for token in substring_tokens):
            identified.append(column)
    return identified


def coerce_target_to_binary(
    frame: pd.DataFrame,
    target_col: str,
    logger: logging.Logger | None = None,
) -> tuple[pd.DataFrame, int, list[str]]:
    """Validate and normalize an explicit target to integer ``0``/``1`` values."""

    active_logger = logger or LOGGER
    if target_col not in frame.columns:
        raise KeyError(f"Target column '{target_col}' is missing; no fraud label will be inferred.")

    working = frame.copy()
    raw_target = working[target_col]
    missing_mask = raw_target.isna()
    missing_count = int(missing_mask.sum())
    if missing_count:
        working = working.loc[~missing_mask].copy()
        active_logger.warning("Removed %d rows with missing target '%s'.", missing_count, target_col)

    target = working[target_col]
    if pd.api.types.is_bool_dtype(target):
        normalized = target.astype("int8")
    else:
        numeric = pd.to_numeric(target, errors="coerce")
        normalized = numeric.copy()
        unresolved = normalized.isna()
        if unresolved.any():
            text_values = target.astype("string").str.strip().str.lower()
            mapping = {
                "true": 1,
                "false": 0,
                "yes": 1,
                "no": 0,
                "fraud": 1,
                "legitimate": 0,
                "normal": 0,
                "genuine": 0,
            }
            mapped = text_values.map(mapping)
            normalized = normalized.fillna(mapped)

    if normalized.isna().any():
        bad = sorted({str(value) for value in target[normalized.isna()].tolist()})[:10]
        raise ValueError(f"Target '{target_col}' contains non-binary/unparseable values: {bad}")

    values = set(pd.Series(normalized).astype(float).unique().tolist())
    if not values.issubset({0.0, 1.0}):
        raise ValueError(f"Target '{target_col}' must contain only 0/1 values; found {sorted(values)}")
    working[target_col] = normalized.astype("int8")
    return working, missing_count, []


def coerce_known_numeric_columns(frame: pd.DataFrame) -> pd.DataFrame:
    """Coerce known amount, account, match, score and velocity columns to numeric."""

    working = frame.copy()
    normalized_known = _normalized_set(KNOWN_NUMERIC_NAMES)
    for column in working.columns:
        normalized = normalize_column_name(column)
        is_known = normalized in normalized_known
        is_amount_like = "amount" in normalized or "itemprice" in normalized
        is_velocity_like = "velocity" in normalized or normalized.startswith("transactions")
        is_score_like = normalized.endswith("riskscore") and normalized != "fraudscore"
        if is_known or is_amount_like or is_velocity_like or is_score_like:
            working[column] = pd.to_numeric(working[column], errors="coerce")
    return working


def fill_missing_values(
    frame: pd.DataFrame,
    target_col: str,
    logger: logging.Logger | None = None,
) -> tuple[pd.DataFrame, dict[str, int], dict[str, int], list[str]]:
    """Impute numeric columns with medians and categorical columns with ``UNKNOWN``."""

    active_logger = logger or LOGGER
    working = frame.copy()
    missing_before = {column: int(count) for column, count in working.isna().sum().items()}
    warnings: list[str] = []

    for column in working.columns:
        if column == target_col:
            continue
        series = working[column]
        if pd.api.types.is_numeric_dtype(series):
            median = series.replace([np.inf, -np.inf], np.nan).median()
            if pd.isna(median):
                median = 0.0
                warnings.append(f"Column '{column}' was entirely missing/non-finite; filled with 0.")
            working[column] = series.replace([np.inf, -np.inf], np.nan).fillna(median)
        elif pd.api.types.is_datetime64_any_dtype(series):
            # Datetimes are retained for auditability and are not model inputs.
            continue
        else:
            working[column] = series.astype("string").fillna("UNKNOWN")

    missing_after = {column: int(count) for column, count in working.isna().sum().items()}
    active_logger.info("Missing values before imputation: %s", missing_before)
    active_logger.info("Missing values after imputation: %s", missing_after)
    return working, missing_before, missing_after, warnings


def clean_frame(
    frame: pd.DataFrame,
    config: PreprocessingConfig,
    require_target: bool = True,
    impute: bool = True,
    logger: logging.Logger | None = None,
) -> CleaningResult:
    """Apply target, leakage, numeric coercion and missing-value policies.

    When ``require_target`` is false, the function can be used for schema
    diagnostics only; it never creates an inferred fraud label.
    """

    active_logger = logger or LOGGER
    working = frame.copy()
    target_rows_removed = 0
    warnings: list[str] = []

    if config.target_col in working.columns:
        working, target_rows_removed, target_warnings = coerce_target_to_binary(
            working, config.target_col, active_logger
        )
        warnings.extend(target_warnings)
    elif require_target:
        raise MissingTargetError(
            f"Target column '{config.target_col}' is absent. Supply an explicit fraud label; "
            "the pipeline will not infer one from event type or transaction status."
        )
    else:
        warnings.append(
            f"Target column '{config.target_col}' is absent; running diagnostics without a model dataset."
        )

    working, removed_leakage, leakage_warnings = remove_leakage_columns(
        working, config.target_col, active_logger
    )
    working = coerce_known_numeric_columns(working)
    if impute:
        working, missing_before, missing_after, missing_warnings = fill_missing_values(
            working, config.target_col, active_logger
        )
    else:
        missing_before = {column: int(count) for column, count in working.isna().sum().items()}
        missing_after = dict(missing_before)
        missing_warnings = []
        active_logger.info("Deferring missing-value imputation until after time-safe feature engineering.")
    warnings.extend(leakage_warnings)
    warnings.extend(missing_warnings)
    return CleaningResult(
        frame=working,
        removed_leakage_columns=removed_leakage,
        leakage_warnings=leakage_warnings,
        target_rows_removed=target_rows_removed,
        missing_before=missing_before,
        missing_after=missing_after,
        warnings=warnings,
    )
