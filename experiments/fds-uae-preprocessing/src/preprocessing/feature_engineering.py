"""Leakage-safe, schema-adaptive feature engineering for payment fraud data."""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from typing import Callable, Iterable

import numpy as np
import pandas as pd

from .config import PreprocessingConfig
from .load_data import normalize_column_name


LOGGER = logging.getLogger(__name__)
KST_TIMEZONE = "Asia/Seoul"


@dataclass
class FeatureEngineeringResult:
    """Feature-engineering outputs and audit metadata."""

    frame: pd.DataFrame
    created_features: list[str] = field(default_factory=list)
    skipped_features: dict[str, str] = field(default_factory=dict)
    warnings: list[str] = field(default_factory=list)
    outlier_stats: dict[str, dict[str, float]] = field(default_factory=dict)
    velocity_comparisons: dict[str, dict[str, float | int]] = field(default_factory=dict)
    user_column: str | None = None
    timestamp_column: str | None = None
    amount_column: str | None = None
    timestamp_sorted: bool = False


def find_column(frame: pd.DataFrame, candidates: Iterable[str]) -> str | None:
    """Find the first actual column matching a case-insensitive alias list."""

    normalized_to_column = {normalize_column_name(column): column for column in frame.columns}
    for candidate in candidates:
        found = normalized_to_column.get(normalize_column_name(candidate))
        if found is not None:
            return found
    return None


def _add_created(created: list[str], column: str) -> None:
    if column not in created:
        created.append(column)


def _to_numeric(series: pd.Series) -> pd.Series:
    """Coerce values to finite-aware numeric values."""

    return pd.to_numeric(series, errors="coerce").replace([np.inf, -np.inf], np.nan)


def _normalize_boolean(series: pd.Series) -> pd.Series:
    """Normalize common boolean/match encodings to nullable numeric 0/1."""

    numeric = pd.to_numeric(series, errors="coerce")
    text = series.astype("string").str.strip().str.lower()
    mapping = {
        "true": 1,
        "false": 0,
        "yes": 1,
        "no": 0,
        "y": 1,
        "n": 0,
        "same": 1,
        "match": 1,
        "matched": 1,
        "equal": 1,
        "different": 0,
        "mismatch": 0,
        "not_matched": 0,
        "unknown": np.nan,
    }
    return numeric.fillna(text.map(mapping)).astype("float64")


def _timestamp_to_utc(series: pd.Series) -> tuple[pd.Series, str | None]:
    """Parse ISO or epoch timestamps and return a UTC-aware series plus a note."""

    numeric = pd.to_numeric(series, errors="coerce")
    numeric_count = int(numeric.notna().sum())
    if numeric_count:
        typical = float(numeric.abs().median())
        if typical >= 1e11:
            return pd.to_datetime(numeric, unit="ms", errors="coerce", utc=True), "epoch milliseconds"
        if typical >= 1e9:
            return pd.to_datetime(numeric, unit="s", errors="coerce", utc=True), "epoch seconds"
    parsed = pd.to_datetime(series, errors="coerce", utc=True)
    return parsed, "datetime strings / timezone-aware values"


def _group_cumsum(values: pd.Series, groups: pd.Series) -> pd.Series:
    """Cumulative sum aligned to the original Series index."""

    return values.groupby(groups, sort=False, dropna=False).cumsum()


def _prior_amount_statistics(
    frame: pd.DataFrame,
    user_column: str,
    amount: pd.Series,
) -> dict[str, pd.Series]:
    """Calculate expanding user statistics using only rows before the current row."""

    numeric_amount = _to_numeric(amount)
    valid = numeric_amount.notna()
    safe_amount = numeric_amount.fillna(0.0)
    valid_float = valid.astype("float64")
    prior_count = _group_cumsum(valid_float, frame[user_column]) - valid_float
    prior_sum = _group_cumsum(safe_amount, frame[user_column]) - safe_amount
    prior_mean = prior_sum.divide(prior_count).where(prior_count.gt(0))

    square = safe_amount.pow(2)
    prior_square_sum = _group_cumsum(square, frame[user_column]) - square
    with np.errstate(divide="ignore", invalid="ignore"):
        variance = (prior_square_sum - prior_count * prior_mean.pow(2)).divide(prior_count - 1)
    variance = variance.where(prior_count.gt(1)).clip(lower=0)
    prior_std = np.sqrt(variance)

    # Expanding median is less algebraically reducible than mean/std.  It is
    # intentionally computed per user and shifted first, so future rows never
    # enter the current-row statistic.
    median_input = pd.DataFrame(
        {"_user": frame[user_column].to_numpy(), "_amount": numeric_amount.to_numpy()}
    )
    prior_median = median_input.groupby("_user", sort=False, dropna=False)["_amount"].transform(
        lambda values: values.shift(1).expanding(min_periods=1).median()
    )
    prior_median.index = frame.index

    prior_txn_count = frame.groupby(user_column, sort=False, dropna=False).cumcount().astype("float64")
    return {
        "user_avg_amount_prev": prior_mean,
        "user_median_amount_prev": prior_median,
        "user_std_amount_prev": prior_std,
        "user_txn_count_prev": prior_txn_count,
    }


def _timestamp_nanoseconds(timestamps: pd.Series) -> tuple[np.ndarray, np.ndarray]:
    """Return timestamps as int64 nanoseconds and a valid-time mask."""

    valid = timestamps.notna().to_numpy()
    output = np.zeros(len(timestamps), dtype="int64")
    if valid.any():
        output[valid] = timestamps.loc[valid].astype("int64").to_numpy(dtype="int64")
    return output, valid


def _prior_window_mean(
    frame: pd.DataFrame,
    user_column: str,
    timestamps: pd.Series,
    values: pd.Series,
    window: pd.Timedelta,
) -> pd.Series:
    """Calculate a prior-only, per-user rolling mean over a time window."""

    output = np.full(len(frame), np.nan, dtype="float64")
    timestamp_ns, valid_time = _timestamp_nanoseconds(timestamps)
    numeric_values = _to_numeric(values).to_numpy(dtype="float64")
    valid_value = np.isfinite(numeric_values)
    window_ns = int(window.value)

    for _, positions in frame.groupby(user_column, sort=False, dropna=False).groups.items():
        positions_array = np.asarray(positions, dtype="int64")
        usable = valid_time[positions_array]
        positions_array = positions_array[usable]
        if len(positions_array) == 0:
            continue
        positions_array = positions_array[np.argsort(timestamp_ns[positions_array], kind="stable")]
        group_times = timestamp_ns[positions_array].astype("int64")
        group_values = numeric_values[positions_array]
        group_valid = valid_value[positions_array]
        safe = np.where(group_valid, group_values, 0.0)
        counts = group_valid.astype("int64")
        cumulative_sum = np.concatenate(([0.0], np.cumsum(safe)))
        cumulative_count = np.concatenate(([0], np.cumsum(counts)))
        row_positions = np.arange(len(positions_array), dtype="int64")
        left = np.searchsorted(group_times, group_times - window_ns, side="left")
        prior_sum = cumulative_sum[row_positions] - cumulative_sum[left]
        prior_count = cumulative_count[row_positions] - cumulative_count[left]
        calculated = np.divide(
            prior_sum,
            prior_count,
            out=np.full(len(prior_sum), np.nan, dtype="float64"),
            where=prior_count > 0,
        )
        output[positions_array] = calculated
    return pd.Series(output, index=frame.index)


def _prior_window_count(
    frame: pd.DataFrame,
    user_column: str,
    timestamps: pd.Series,
    eligible: pd.Series,
    window: pd.Timedelta,
) -> pd.Series:
    """Count eligible prior user rows in a time window, excluding the current row."""

    output = np.zeros(len(frame), dtype="float64")
    timestamp_ns, valid_time = _timestamp_nanoseconds(timestamps)
    eligible_values = eligible.fillna(False).to_numpy(dtype=bool)
    window_ns = int(window.value)

    for _, positions in frame.groupby(user_column, sort=False, dropna=False).groups.items():
        positions_array = np.asarray(positions, dtype="int64")
        usable = valid_time[positions_array]
        positions_array = positions_array[usable]
        if len(positions_array) == 0:
            continue
        positions_array = positions_array[np.argsort(timestamp_ns[positions_array], kind="stable")]
        group_times = timestamp_ns[positions_array].astype("int64")
        group_eligible = eligible_values[positions_array].astype("int64")
        cumulative = np.concatenate(([0], np.cumsum(group_eligible)))
        row_positions = np.arange(len(positions_array), dtype="int64")
        left = np.searchsorted(group_times, group_times - window_ns, side="left")
        output[positions_array] = cumulative[row_positions] - cumulative[left]
    return pd.Series(output, index=frame.index)


def _detect_payment_rows(frame: pd.DataFrame) -> tuple[pd.Series, str | None]:
    """Identify payment-like rows when an event column is explicitly present."""

    event_column = find_column(frame, ("event", "event_type", "transaction_type"))
    if event_column is None:
        return pd.Series(True, index=frame.index), None
    values = frame[event_column].astype("string").str.strip().str.lower()
    payment_values = {"transaction", "purchase", "payment", "checkout", "sale", "charge"}
    eligible = values.isin(payment_values)
    if not eligible.any():
        return pd.Series(True, index=frame.index), (
            f"Event column '{event_column}' had no recognized payment values; velocity counts use all rows."
        )
    return eligible, f"Velocity counts use recognized payment rows from '{event_column}'."


def _convert_aed_columns(
    frame: pd.DataFrame,
    config: PreprocessingConfig,
    created: list[str],
    warnings: list[str],
) -> pd.DataFrame:
    """Create KRW companions for numeric AED columns while preserving originals."""

    working = frame.copy()
    currency_column = find_column(working, ("currency", "currency_code", "currency_type"))
    currency_is_aed = False
    if currency_column is not None:
        currency_values = working[currency_column].astype("string").str.strip().str.upper().dropna()
        currency_is_aed = not currency_values.empty and bool(currency_values.eq("AED").all())
    for column in list(working.columns):
        normalized = normalize_column_name(column)
        if "aed" not in normalized or "krw" in normalized:
            continue
        numeric = _to_numeric(working[column])
        if not numeric.notna().any():
            warnings.append(f"Skipped AED conversion for '{column}': no numeric values were found.")
            continue
        krw_name = re.sub("aed", "krw", column, flags=re.IGNORECASE)
        if krw_name == column:
            krw_name = f"{column}_krw"
        working[krw_name] = numeric * float(config.aed_to_krw)
        _add_created(created, krw_name)
    if currency_is_aed:
        # Some source snapshots label the currency in a separate column while
        # naming the numeric fields simply ``amount``/``avg_item_price``.
        for column in list(working.columns):
            normalized = normalize_column_name(column)
            if "krw" in normalized or "aed" in normalized:
                continue
            if "amount" not in normalized and "itemprice" not in normalized:
                continue
            numeric = _to_numeric(working[column])
            if not numeric.notna().any():
                continue
            krw_name = f"{column}_krw"
            if krw_name in working.columns:
                continue
            working[krw_name] = numeric * float(config.aed_to_krw)
            _add_created(created, krw_name)
    if any("aed" in normalize_column_name(column) for column in frame.columns):
        warnings.append(
            f"AED columns were converted with configurable AED_TO_KRW={config.aed_to_krw}; the rate is not used as a fraud signal."
        )
    elif currency_is_aed:
        warnings.append(
            f"Currency column '{currency_column}' contains AED; amount-like fields were converted with configurable AED_TO_KRW={config.aed_to_krw}."
        )
    return working


def _select_amount_column(frame: pd.DataFrame) -> tuple[str | None, pd.Series | None, str | None]:
    """Resolve a source amount and the amount series used for behavior features."""

    krw_column = find_column(frame, ("amount_krw",))
    if krw_column is not None:
        return krw_column, _to_numeric(frame[krw_column]), "KRW"
    aed_column = find_column(
        frame,
        ("amount_aed", "transaction_amount_aed", "purchase_amount_aed", "total_amount_aed"),
    )
    if aed_column is not None:
        return aed_column, _to_numeric(frame[aed_column]) * 1.0, "AED"
    raw_column = find_column(
        frame,
        ("amount", "transaction_amount", "purchase_amount", "total_amount", "order_amount"),
    )
    if raw_column is not None:
        return raw_column, _to_numeric(frame[raw_column]), "UNKNOWN_CURRENCY"
    return None, None, None


def _add_amount_features(
    frame: pd.DataFrame,
    user_column: str | None,
    amount_column: str | None,
    amount: pd.Series | None,
    timestamp_column: str | None,
    created: list[str],
    skipped: dict[str, str],
    warnings: list[str],
) -> pd.DataFrame:
    """Create amount and prior-user behavior features without future information."""

    working = frame
    if amount is None or amount_column is None:
        for feature in (
            "log_amount",
            "amount_ratio",
            "amount_deviation",
            "amount_zscore",
            "user_avg_amount_prev",
            "user_median_amount_prev",
            "user_std_amount_prev",
            "user_txn_count_prev",
        ):
            skipped[feature] = "no amount column was detected"
        return working

    numeric_amount = _to_numeric(amount)
    if amount_column != "amount_krw" and normalize_column_name(amount_column).endswith("aed"):
        converted_name = re.sub("aed", "krw", amount_column, flags=re.IGNORECASE)
        if converted_name in working.columns:
            numeric_amount = _to_numeric(working[converted_name])
            amount_column_for_features = converted_name
        else:
            numeric_amount = numeric_amount * 1.0
            amount_column_for_features = amount_column
    else:
        amount_column_for_features = amount_column

    if (numeric_amount < 0).any():
        warnings.append(
            f"Amount source '{amount_column}' contains negative values; log_amount uses a non-negative floor at zero."
        )
    working["log_amount"] = np.log1p(numeric_amount.clip(lower=0)).replace([np.inf, -np.inf], np.nan)
    _add_created(created, "log_amount")

    if user_column is None:
        skipped.update(
            {
                "amount_ratio": "user identifier was not detected",
                "amount_deviation": "user identifier was not detected",
                "amount_zscore": "user identifier was not detected",
                "user_avg_amount_prev": "user identifier was not detected",
                "user_median_amount_prev": "user identifier was not detected",
                "user_std_amount_prev": "user identifier was not detected",
                "user_txn_count_prev": "user identifier was not detected",
            }
        )
        return working

    stats = _prior_amount_statistics(working, user_column, numeric_amount)
    for feature_name, values in stats.items():
        working[feature_name] = values
        _add_created(created, feature_name)

    prior_mean = stats["user_avg_amount_prev"]
    prior_std = stats["user_std_amount_prev"]
    ratio = pd.Series(1.0, index=working.index, dtype="float64")
    valid_ratio = numeric_amount.notna() & prior_mean.notna() & prior_mean.ne(0)
    ratio.loc[valid_ratio] = numeric_amount.loc[valid_ratio] / prior_mean.loc[valid_ratio]
    working["amount_ratio"] = ratio.replace([np.inf, -np.inf], np.nan).fillna(1.0)
    deviation = (numeric_amount - prior_mean).where(prior_mean.notna(), 0.0)
    working["amount_deviation"] = deviation.replace([np.inf, -np.inf], np.nan).fillna(0.0)
    zscore = pd.Series(0.0, index=working.index, dtype="float64")
    valid_z = numeric_amount.notna() & prior_mean.notna() & prior_std.gt(0)
    zscore.loc[valid_z] = (
        (numeric_amount.loc[valid_z] - prior_mean.loc[valid_z]) / prior_std.loc[valid_z]
    )
    working["amount_zscore"] = zscore.replace([np.inf, -np.inf], np.nan).fillna(0.0)
    for feature_name in ("amount_ratio", "amount_deviation", "amount_zscore"):
        _add_created(created, feature_name)

    if timestamp_column is not None:
        working["user_avg_amount_30d"] = _prior_window_mean(
            working,
            user_column,
            working["timestamp_utc"],
            numeric_amount,
            pd.Timedelta(days=30),
        )
        _add_created(created, "user_avg_amount_30d")
    else:
        skipped["user_avg_amount_30d"] = "timestamp column was not detected"
    return working


def _add_time_features(
    frame: pd.DataFrame,
    timestamp_column: str | None,
    user_column: str | None,
    config: PreprocessingConfig,
    created: list[str],
    skipped: dict[str, str],
    warnings: list[str],
) -> tuple[pd.DataFrame, bool]:
    """Parse/sort time and create KST and prior user-hour features."""

    working = frame
    if timestamp_column is None:
        for feature in (
            "timestamp_utc",
            "timestamp_kst",
            "kst_hour",
            "day_of_week",
            "is_weekend",
            "is_night",
            "user_avg_hour_prev",
            "hour_deviation",
        ):
            skipped[feature] = "timestamp column was not detected"
        return working, False

    parsed, parse_note = _timestamp_to_utc(working[timestamp_column])
    working["timestamp_utc"] = parsed
    working["timestamp_kst"] = parsed.dt.tz_convert(KST_TIMEZONE)
    before = int(working["timestamp_utc"].notna().sum())
    if before == 0:
        warnings.append(f"Timestamp column '{timestamp_column}' could not be parsed.")
        return working, False
    warnings.append(f"Parsed timestamp column '{timestamp_column}' as {parse_note} and converted UTC → KST.")

    if "_source_row_order" not in working.columns:
        working["_source_row_order"] = np.arange(len(working), dtype="int64")
    working = working.sort_values(
        by=["timestamp_utc", "_source_row_order"],
        kind="mergesort",
        na_position="last",
    ).reset_index(drop=True)

    kst = working["timestamp_kst"]
    working["kst_hour"] = kst.dt.hour.astype("float64")
    working["day_of_week"] = kst.dt.dayofweek.astype("float64")
    working["is_weekend"] = working["day_of_week"].isin([5.0, 6.0]).astype("float64")
    working["is_night"] = (
        (working["kst_hour"] >= config.night_start_hour)
        | (working["kst_hour"] < config.night_end_hour)
    ).astype("float64")
    for feature_name in ("timestamp_utc", "timestamp_kst", "kst_hour", "day_of_week", "is_weekend", "is_night"):
        _add_created(created, feature_name)

    if user_column is None:
        skipped["user_avg_hour_prev"] = "user identifier was not detected"
        skipped["hour_deviation"] = "user identifier was not detected"
    else:
        hour = working["kst_hour"]
        valid_hour = hour.notna().astype("float64")
        safe_hour = hour.fillna(0.0)
        prior_sum = _group_cumsum(safe_hour, working[user_column]) - safe_hour
        prior_count = _group_cumsum(valid_hour, working[user_column]) - valid_hour
        working["user_avg_hour_prev"] = prior_sum.divide(prior_count).where(prior_count.gt(0))
        working["hour_deviation"] = (
            (hour - working["user_avg_hour_prev"]).abs().where(working["user_avg_hour_prev"].notna(), 0.0)
        )
        for feature_name in ("user_avg_hour_prev", "hour_deviation"):
            _add_created(created, feature_name)
    return working, True


def _add_velocity_features(
    frame: pd.DataFrame,
    user_column: str | None,
    timestamp_column: str | None,
    config: PreprocessingConfig,
    created: list[str],
    skipped: dict[str, str],
    warnings: list[str],
    comparisons: dict[str, dict[str, float | int]],
) -> pd.DataFrame:
    """Use raw velocity columns and calculate prior-only user windows where possible."""

    working = frame
    raw_1h = find_column(working, ("transactions_last_1h",))
    raw_24h = find_column(working, ("transactions_last_24h",))
    for column in (raw_1h, raw_24h):
        if column is not None:
            working[column] = _to_numeric(working[column])

    if user_column is None or timestamp_column is None:
        for feature in ("txn_count_10m", "txn_count_1h_calc", "txn_count_24h_calc"):
            skipped[feature] = "user identifier and timestamp are both required"
    else:
        eligible, note = _detect_payment_rows(working)
        if note:
            warnings.append(note)
        working["txn_count_10m"] = _prior_window_count(
            working, user_column, working["timestamp_utc"], eligible, pd.Timedelta(minutes=10)
        )
        working["txn_count_1h_calc"] = _prior_window_count(
            working, user_column, working["timestamp_utc"], eligible, pd.Timedelta(hours=1)
        )
        working["txn_count_24h_calc"] = _prior_window_count(
            working, user_column, working["timestamp_utc"], eligible, pd.Timedelta(hours=24)
        )
        for feature_name in ("txn_count_10m", "txn_count_1h_calc", "txn_count_24h_calc"):
            _add_created(created, feature_name)

    comparison_pairs = (
        (raw_1h, "txn_count_1h_calc", "transactions_last_1h_diff"),
        (raw_24h, "txn_count_24h_calc", "transactions_last_24h_diff"),
    )
    for raw_column, calculated_column, diff_column in comparison_pairs:
        if raw_column is None or calculated_column not in working.columns:
            continue
        raw_values = _to_numeric(working[raw_column])
        calculated_values = _to_numeric(working[calculated_column])
        valid = raw_values.notna() & calculated_values.notna()
        if not valid.any():
            continue
        difference = raw_values - calculated_values
        working[diff_column] = difference.fillna(0.0)
        _add_created(created, diff_column)
        absolute_difference = difference.loc[valid].abs()
        comparisons[diff_column] = {
            "compared_rows": int(valid.sum()),
            "mean_abs_diff": float(absolute_difference.mean()),
            "max_abs_diff": float(absolute_difference.max()),
            "mismatch_rows": int((~np.isclose(raw_values.loc[valid], calculated_values.loc[valid])).sum()),
        }
        warnings.append(
            f"Velocity comparison {raw_column} vs {calculated_column}: {comparisons[diff_column]}"
        )

    velocity_sources: list[pd.Series] = []
    if raw_1h is not None:
        velocity_sources.append(_to_numeric(working[raw_1h]).ge(config.high_velocity_1h_threshold))
    if raw_24h is not None:
        velocity_sources.append(_to_numeric(working[raw_24h]).ge(config.high_velocity_24h_threshold))
    if "txn_count_1h_calc" in working.columns:
        velocity_sources.append(working["txn_count_1h_calc"].ge(config.high_velocity_1h_threshold))
    if "txn_count_24h_calc" in working.columns:
        velocity_sources.append(working["txn_count_24h_calc"].ge(config.high_velocity_24h_threshold))
    if velocity_sources:
        high_velocity = velocity_sources[0].fillna(False)
        for source in velocity_sources[1:]:
            high_velocity = high_velocity | source.fillna(False)
        working["high_velocity"] = high_velocity.astype("float64")
        _add_created(created, "high_velocity")
    else:
        skipped["high_velocity"] = "no raw or calculated velocity feature was available"
    return working


def _add_account_and_location_features(
    frame: pd.DataFrame,
    config: PreprocessingConfig,
    created: list[str],
    skipped: dict[str, str],
) -> pd.DataFrame:
    """Normalize account, match, IP-risk and city-relation features."""

    working = frame
    aliases: dict[str, tuple[str, ...]] = {
        "user_account_age_days": ("user_account_age_days", "account_age_days"),
        "user_prev_chargebacks": ("user_prev_chargebacks", "prev_chargebacks"),
        "user_is_high_risk": ("user_is_high_risk", "is_high_risk", "high_risk"),
        "card_age_days": ("card_age_days",),
        "card_country_match": ("card_country_match",),
        "shipping_billing_match": ("shipping_billing_match",),
        "ip_risk_score": ("ip_risk_score",),
        "items_count": ("items_count", "item_count", "quantity"),
    }
    numeric_features = {
        "user_account_age_days",
        "user_prev_chargebacks",
        "card_age_days",
        "ip_risk_score",
        "items_count",
    }
    boolean_features = {"user_is_high_risk", "card_country_match", "shipping_billing_match"}
    source_columns: dict[str, str] = {}
    for canonical, candidates in aliases.items():
        source = find_column(working, candidates)
        if source is None:
            skipped[canonical] = "source column was not detected"
            continue
        source_columns[canonical] = source
        if canonical in numeric_features:
            working[source] = _to_numeric(working[source])
        elif canonical in boolean_features:
            working[canonical] = _normalize_boolean(working[source])
            if source != canonical:
                _add_created(created, canonical)

    account_age = source_columns.get("user_account_age_days")
    if account_age is not None:
        working["is_fresh_account"] = (
            _to_numeric(working[account_age]).lt(config.fresh_account_days).astype("float64")
        )
        _add_created(created, "is_fresh_account")
    else:
        skipped["is_fresh_account"] = "user account age column was not detected"

    shipping = find_column(working, ("shipping_city",))
    billing = find_column(working, ("billing_city",))
    if shipping is not None and billing is not None:
        shipping_text = working[shipping].astype("string").str.strip().str.casefold()
        billing_text = working[billing].astype("string").str.strip().str.casefold()
        valid = shipping_text.notna() & billing_text.notna()
        working["city_match"] = (shipping_text.eq(billing_text).fillna(False) & valid).astype("float64")
        _add_created(created, "city_match")
    else:
        skipped["city_match"] = "both shipping_city and billing_city are required"
    return working


def _add_item_features(
    frame: pd.DataFrame,
    amount: pd.Series | None,
    created: list[str],
    skipped: dict[str, str],
) -> pd.DataFrame:
    """Create safe per-item amount features when count and amount are present."""

    working = frame
    item_column = find_column(working, ("items_count", "item_count", "quantity"))
    if item_column is None or amount is None:
        skipped["calculated_avg_item_price"] = "amount and items_count were not both detected"
        skipped["amount_per_item"] = "amount and items_count were not both detected"
        return working
    items = _to_numeric(working[item_column])
    converted_amount_column = find_column(working, ("amount_krw",))
    numeric_amount = (
        _to_numeric(working[converted_amount_column])
        if converted_amount_column is not None
        else _to_numeric(amount)
    )
    valid = items.gt(0) & numeric_amount.notna()
    for feature_name in ("calculated_avg_item_price", "amount_per_item"):
        result = pd.Series(0.0, index=working.index, dtype="float64")
        result.loc[valid] = numeric_amount.loc[valid] / items.loc[valid]
        working[feature_name] = result.replace([np.inf, -np.inf], np.nan).fillna(0.0)
        _add_created(created, feature_name)
    return working


def _outlier_statistics(
    frame: pd.DataFrame,
    columns: Iterable[str],
    clip_quantile: float,
    created: list[str],
    warnings: list[str],
) -> dict[str, dict[str, float]]:
    """Report extreme-value percentiles and create a non-destructive amount clip."""

    stats: dict[str, dict[str, float]] = {}
    for column in columns:
        if column not in frame.columns:
            continue
        numeric = _to_numeric(frame[column]).dropna()
        if numeric.empty:
            continue
        quantiles = numeric.quantile([0.99, 0.995, 0.999])
        stats[column] = {
            "p99": float(quantiles.loc[0.99]),
            "p99_5": float(quantiles.loc[0.995]),
            "p99_9": float(quantiles.loc[0.999]),
            "max": float(numeric.max()),
        }
    amount_candidates = [column for column in ("amount_krw", "amount") if column in frame.columns]
    if amount_candidates:
        source = amount_candidates[0]
        numeric = _to_numeric(frame[source])
        finite = numeric.dropna()
        if not finite.empty:
            limit = float(finite.quantile(clip_quantile))
            frame["amount_clipped"] = numeric.clip(upper=limit).fillna(limit)
            _add_created(created, "amount_clipped")
            warnings.append(
                f"Created amount_clipped at quantile {clip_quantile:.3f}; original '{source}' remains unchanged."
            )
    return stats


def engineer_features(
    frame: pd.DataFrame,
    config: PreprocessingConfig,
    logger: logging.Logger | None = None,
) -> FeatureEngineeringResult:
    """Run all schema-adaptive feature engineering steps in time-safe order."""

    active_logger = logger or LOGGER
    working = frame.copy()
    created: list[str] = []
    skipped: dict[str, str] = {}
    warnings: list[str] = []
    velocity_comparisons: dict[str, dict[str, float | int]] = {}

    user_column = find_column(
        working,
        ("user_id", "userid", "visitor_id", "visitorid", "customer_id", "customerid", "account_id"),
    )
    timestamp_column = find_column(
        working,
        (
            "timestamp",
            "timestamp_utc",
            "transaction_time",
            "transaction_timestamp",
            "created_at",
            "event_time",
            "date",
        ),
    )

    working, timestamp_sorted = _add_time_features(
        working, timestamp_column, user_column, config, created, skipped, warnings
    )
    # Sorting can change row alignment, so resolve/use all subsequent series
    # from the sorted working frame.
    working = _convert_aed_columns(working, config, created, warnings)
    amount_column, amount, amount_currency = _select_amount_column(working)
    if amount_currency == "UNKNOWN_CURRENCY":
        warnings.append(
            f"Amount column '{amount_column}' was found but its currency is unspecified; no automatic KRW conversion was applied."
        )
    working = _add_account_and_location_features(working, config, created, skipped)
    working = _add_amount_features(
        working,
        user_column,
        amount_column,
        amount,
        "timestamp_utc" if timestamp_sorted else None,
        created,
        skipped,
        warnings,
    )
    working = _add_item_features(working, amount, created, skipped)
    working = _add_velocity_features(
        working,
        user_column,
        "timestamp_utc" if timestamp_sorted else None,
        config,
        created,
        skipped,
        warnings,
        velocity_comparisons,
    )

    outlier_candidates = [
        amount_column,
        find_column(working, ("amount_krw",)),
        find_column(working, ("avg_item_price", "avg_item_price_aed", "avg_item_price_krw")),
        find_column(working, ("items_count", "item_count", "quantity")),
        find_column(working, ("transactions_last_1h",)),
        find_column(working, ("transactions_last_24h",)),
        "txn_count_10m",
        "txn_count_1h_calc",
        "txn_count_24h_calc",
    ]
    outlier_columns = list(dict.fromkeys(column for column in outlier_candidates if column is not None))
    outlier_stats = _outlier_statistics(working, outlier_columns, config.outlier_clip_quantile, created, warnings)
    if outlier_stats:
        active_logger.info("Outlier percentiles (p99/p99.5/p99.9/max): %s", outlier_stats)

    # Make every skipped feature visible to the logs/report rather than silently
    # pretending it exists.
    for feature_name, reason in skipped.items():
        active_logger.warning("Skipped feature '%s': %s", feature_name, reason)
    for warning in dict.fromkeys(warnings):
        active_logger.warning("Feature engineering warning: %s", warning)
    active_logger.info("Created features: %s", created)
    return FeatureEngineeringResult(
        frame=working,
        created_features=created,
        skipped_features=skipped,
        warnings=warnings,
        outlier_stats=outlier_stats,
        velocity_comparisons=velocity_comparisons,
        user_column=user_column,
        timestamp_column=timestamp_column,
        amount_column=amount_column,
        timestamp_sorted=timestamp_sorted,
    )
