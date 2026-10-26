"""Selection and optional one-hot encoding of model-ready features."""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

import numpy as np
import pandas as pd

try:  # Optional in the bundled runtime; pandas fallback keeps CSV generation usable.
    import joblib
except ImportError:  # pragma: no cover - depends on the active runtime
    joblib = None  # type: ignore[assignment]

try:  # Optional in the bundled runtime; scikit-learn is preferred when available.
    from sklearn.preprocessing import OneHotEncoder
except ImportError:  # pragma: no cover - depends on the active runtime
    OneHotEncoder = None  # type: ignore[assignment,misc]

from .clean import identify_identifier_columns, identify_leakage_columns
from .config import PreprocessingConfig
from .load_data import normalize_column_name


LOGGER = logging.getLogger(__name__)


@dataclass
class ModelFeaturesResult:
    """Model dataset plus feature-selection and encoding metadata."""

    frame: pd.DataFrame
    removed_identifier_columns: list[str] = field(default_factory=list)
    removed_non_model_columns: dict[str, str] = field(default_factory=dict)
    numeric_features: list[str] = field(default_factory=list)
    categorical_features: list[str] = field(default_factory=list)
    encoded_feature_names: list[str] = field(default_factory=list)
    encoder_path: Path | None = None
    warnings: list[str] = field(default_factory=list)


RAW_TEMPORAL_NAMES = {
    "timestamp",
    "timestamputc",
    "timestamputc",
    "timestampkst",
    "transactiontime",
    "transactiontimestamp",
    "createdat",
    "eventtime",
    "date",
}
RAW_LOCATION_NAMES = {
    "cardcountry",
    "bincountry",
    "shippingcity",
    "billingcity",
    "shippingcountry",
    "billingcountry",
}
RAW_METADATA_NAMES = {"currency", "currencycode", "currencytype", "datasource", "dataset"}


def _is_raw_temporal_or_location(column: str) -> bool:
    normalized = normalize_column_name(column)
    return (
        normalized in RAW_TEMPORAL_NAMES
        or normalized.endswith("timestamp")
        or normalized.endswith("datetime")
        or normalized in RAW_LOCATION_NAMES
        or normalized in RAW_METADATA_NAMES
    )


def _safe_one_hot_encoder() -> OneHotEncoder:
    """Create a version-compatible dense OneHotEncoder."""

    if OneHotEncoder is None:
        raise ImportError("scikit-learn is not installed")
    try:
        return OneHotEncoder(handle_unknown="ignore", sparse_output=False)
    except TypeError:  # scikit-learn < 1.2
        return OneHotEncoder(handle_unknown="ignore", sparse=False)


def _impute_for_model(
    frame: pd.DataFrame,
    numeric_features: list[str],
    categorical_features: list[str],
) -> tuple[pd.DataFrame, list[str]]:
    """Apply finite numeric and ``UNKNOWN`` categorical imputation before encoding."""

    working = frame.copy()
    warnings: list[str] = []
    for column in numeric_features:
        values = pd.to_numeric(working[column], errors="coerce").replace([np.inf, -np.inf], np.nan)
        median = values.median()
        if pd.isna(median):
            median = 0.0
            warnings.append(f"Model numeric column '{column}' had no finite values; filled with 0.")
        working[column] = values.fillna(median)
    for column in categorical_features:
        working[column] = working[column].astype("string").fillna("UNKNOWN")
    return working, warnings


def summarize_features(frame: pd.DataFrame) -> pd.DataFrame:
    """Return the required per-feature summary, leaving unavailable statistics blank."""

    rows: list[dict[str, object]] = []
    for column in frame.columns:
        series = frame[column]
        row: dict[str, object] = {
            "feature_name": column,
            "dtype": str(series.dtype),
            "missing_count": int(series.isna().sum()),
            "unique_count": int(series.nunique(dropna=True)),
            "min": np.nan,
            "max": np.nan,
            "mean": np.nan,
            "median": np.nan,
        }
        numeric = pd.to_numeric(series, errors="coerce")
        if numeric.notna().any():
            finite = numeric.replace([np.inf, -np.inf], np.nan).dropna()
            if not finite.empty:
                row.update(
                    {
                        "min": float(finite.min()),
                        "max": float(finite.max()),
                        "mean": float(finite.mean()),
                        "median": float(finite.median()),
                    }
                )
        rows.append(row)
    return pd.DataFrame(
        rows,
        columns=["feature_name", "dtype", "missing_count", "unique_count", "min", "max", "mean", "median"],
    )


def build_model_features(
    frame: pd.DataFrame,
    config: PreprocessingConfig,
    output_dir: Path | None = None,
    logger: logging.Logger | None = None,
) -> ModelFeaturesResult:
    """Remove identifiers/raw location-time columns and encode low-cardinality categoricals.

    ``user_id`` remains available to feature engineering because this function
    is called only after all prior-only user statistics have been created.
    """

    active_logger = logger or LOGGER
    if config.target_col not in frame.columns:
        raise KeyError(f"Cannot build model features without '{config.target_col}'.")

    working = frame.copy()
    target = working[config.target_col].copy()
    leakage, _ = identify_leakage_columns(working.columns.tolist(), config.target_col)
    if leakage:
        working = working.drop(columns=list(leakage), errors="ignore")

    removed_identifiers = identify_identifier_columns(
        [column for column in working.columns if column != config.target_col]
    )
    removed_non_model: dict[str, str] = {
        column: "direct identifier; retained only until user-level feature engineering completed"
        for column in removed_identifiers
    }
    for column in working.columns:
        if column == config.target_col or column in removed_identifiers:
            continue
        if _is_raw_temporal_or_location(column):
            removed_non_model[column] = "raw timestamp/location; use derived temporal or relation feature"
    working = working.drop(columns=list(removed_non_model), errors="ignore")

    # Internal bookkeeping columns are not model inputs.
    internal = [column for column in working.columns if column.startswith("_")]
    for column in internal:
        removed_non_model[column] = "internal pipeline bookkeeping"
    working = working.drop(columns=internal, errors="ignore")

    features = [column for column in working.columns if column != config.target_col]
    numeric_features = [column for column in features if pd.api.types.is_numeric_dtype(working[column])]
    categorical_candidates = [column for column in features if column not in numeric_features]
    categorical_features: list[str] = []
    warnings: list[str] = []
    for column in categorical_candidates:
        unique_count = int(working[column].nunique(dropna=True))
        normalized = normalize_column_name(column)
        if unique_count <= config.max_categorical_unique or normalized in {"devicetype", "browser"}:
            categorical_features.append(column)
        else:
            removed_non_model[column] = (
                f"high-cardinality categorical ({unique_count} unique values); preserve in processed output, exclude from model"
            )
            warnings.append(
                f"Excluded high-cardinality categorical '{column}' from model features ({unique_count} unique values)."
            )
    working = working.drop(
        columns=[column for column in removed_non_model if column in working.columns], errors="ignore"
    )

    # Recompute feature lists after high-cardinality exclusions.
    features = [column for column in working.columns if column != config.target_col]
    numeric_features = [column for column in features if pd.api.types.is_numeric_dtype(working[column])]
    categorical_features = [column for column in features if column not in numeric_features]
    working, imputation_warnings = _impute_for_model(working, numeric_features, categorical_features)
    warnings.extend(imputation_warnings)

    target_numeric = pd.to_numeric(target, errors="coerce")
    if target_numeric.isna().any() or not set(target_numeric.unique()).issubset({0, 1}):
        raise ValueError("Target must be binary 0/1 before model-feature construction.")
    target_output = target_numeric.astype("int8").rename(config.target_col)

    encoder_path: Path | None = None
    if config.encode_categoricals and categorical_features:
        if OneHotEncoder is None:
            # This fallback is deterministic for the current dataset and keeps
            # the preprocessing CSV usable when scikit-learn is not installed.
            encoded_frame = pd.get_dummies(
                working[categorical_features],
                columns=categorical_features,
                prefix=categorical_features,
                dtype="float64",
            )
            encoded_names = encoded_frame.columns.tolist()
            warnings.append(
                "scikit-learn is unavailable; used pandas.get_dummies for one-hot CSV output. Install scikit-learn for reusable inference encoding."
            )
            model_frame = pd.concat([working[numeric_features], encoded_frame, target_output], axis=1)
            encoded_feature_names = numeric_features + encoded_names
        else:
            encoder = _safe_one_hot_encoder()
            encoded_array = encoder.fit_transform(working[categorical_features])
            encoded_names = encoder.get_feature_names_out(categorical_features).tolist()
            encoded_frame = pd.DataFrame(encoded_array, columns=encoded_names, index=working.index)
            model_frame = pd.concat([working[numeric_features], encoded_frame, target_output], axis=1)
            encoded_feature_names = numeric_features + encoded_names
            if output_dir is not None:
                output_dir.mkdir(parents=True, exist_ok=True)
                if joblib is not None:
                    encoder_path = output_dir / "fraud_feature_encoder.joblib"
                    joblib.dump(encoder, encoder_path)
                else:
                    warnings.append(
                        "joblib is unavailable; the fitted encoder was not serialized. Install joblib for inference reuse."
                    )
    else:
        model_frame = pd.concat([working[numeric_features + categorical_features], target_output], axis=1)
        encoded_feature_names = numeric_features + categorical_features

    active_logger.info("Removed identifier columns from model input: %s", removed_identifiers)
    active_logger.info("Numeric model features: %s", numeric_features)
    active_logger.info("Categorical model features: %s", categorical_features)
    return ModelFeaturesResult(
        frame=model_frame,
        removed_identifier_columns=removed_identifiers,
        removed_non_model_columns=removed_non_model,
        numeric_features=numeric_features,
        categorical_features=categorical_features,
        encoded_feature_names=encoded_feature_names,
        encoder_path=encoder_path,
        warnings=warnings,
    )
