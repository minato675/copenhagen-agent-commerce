"""CSV discovery, schema inspection and safe loading utilities."""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable

import numpy as np
import pandas as pd

from .config import PreprocessingConfig


LOGGER = logging.getLogger(__name__)


class MissingTargetError(ValueError):
    """Raised when a model-ready dataset has no explicit fraud target."""


@dataclass
class CsvInspection:
    """Compact, JSON/report-friendly summary of one CSV file."""

    path: Path
    rows: int | None
    columns: list[str]
    dtypes: dict[str, str]
    missing_counts: dict[str, int]
    duplicate_rows: int | None
    target_distribution: dict[str, int] = field(default_factory=dict)
    fraud_ratio: float | None = None
    warnings: list[str] = field(default_factory=list)


def normalize_column_name(name: str) -> str:
    """Normalize a column name for case-insensitive alias matching."""

    return re.sub(r"[^a-z0-9]+", "", str(name).strip().lower())


def discover_csvs(config: PreprocessingConfig) -> list[Path]:
    """Discover input CSVs, preferring ``data/raw`` and supporting this repo's root CSVs.

    The fallback to the project root is intentional because the supplied
    repository currently stores the four CSV files beside the source tree.
    Processed/report directories are never searched as raw input.
    """

    if config.input_csv is not None:
        candidate = config.input_csv
        if not candidate.is_absolute():
            candidate = config.project_root / candidate
        if not candidate.exists():
            raise FileNotFoundError(f"Input CSV does not exist: {candidate}")
        return [candidate]

    preferred = sorted(config.raw_dir.glob("*.csv")) if config.raw_dir.exists() else []
    if preferred:
        return preferred

    root_csvs = sorted(config.project_root.glob("*.csv"))
    if root_csvs:
        LOGGER.warning(
            "No CSV files found under %s; using CSVs in project root: %s",
            config.raw_dir,
            ", ".join(path.name for path in root_csvs),
        )
        return root_csvs

    raise FileNotFoundError(
        f"No CSV files found. Put the source file under {config.raw_dir} or pass --input-csv."
    )


def read_csv_header(path: Path) -> list[str]:
    """Read only a CSV header without loading its data rows."""

    return pd.read_csv(path, nrows=0).columns.tolist()


def _target_header_matches(headers: Iterable[str], target_col: str) -> bool:
    target_normalized = normalize_column_name(target_col)
    return any(normalize_column_name(header) == target_normalized for header in headers)


def _primary_file_score(path: Path, headers: list[str]) -> tuple[int, int, str]:
    """Score a no-target CSV as a likely transactional primary file."""

    normalized = {normalize_column_name(header) for header in headers}
    score = 0
    for candidate in ("timestamp", "transactiontime", "transactiontimestamp", "amount"):
        if candidate in normalized:
            score += 5
    for candidate in ("userid", "visitorid", "customerid", "transactionid"):
        if candidate in normalized:
            score += 3
    if "event" in normalized:
        score += 2
    if {"property", "value"}.issubset(normalized):
        score -= 10
    if {"categoryid", "parentid"}.issubset(normalized):
        score -= 10
    return score, path.stat().st_size, path.name


def choose_primary_csv(csv_paths: list[Path], config: PreprocessingConfig) -> tuple[Path, list[str]]:
    """Choose the label-bearing CSV, or a clearly documented fallback for diagnostics."""

    if not csv_paths:
        raise FileNotFoundError("No CSV candidates were discovered.")

    header_rows = [(path, read_csv_header(path)) for path in csv_paths]
    target_candidates = [
        (path, headers) for path, headers in header_rows if _target_header_matches(headers, config.target_col)
    ]
    if target_candidates:
        selected = max(target_candidates, key=lambda item: item[0].stat().st_size)[0]
        return selected, [
            f"Selected label-bearing CSV: {selected.name}.",
            f"Other discovered CSVs were treated as auxiliary files: {', '.join(path.name for path in csv_paths if path != selected)}.",
        ]

    selected = max((_primary_file_score(path, headers) for path, headers in header_rows))[2]
    selected_path = next(path for path in csv_paths if path.name == selected)
    return selected_path, [
        f"No discovered CSV contains target column '{config.target_col}'.",
        f"Selected '{selected_path.name}' as a schema-diagnostic fallback using column-name scoring; this is not a fraud dataset selection.",
    ]


def load_primary_csv(path: Path) -> pd.DataFrame:
    """Load the selected primary CSV with a stable, non-shuffling row order."""

    LOGGER.info("Loading primary CSV: %s", path)
    frame = pd.read_csv(path, low_memory=False)
    frame["_source_row_order"] = np.arange(len(frame), dtype=np.int64)
    return frame


def inspect_frame(frame: pd.DataFrame, target_col: str) -> CsvInspection:
    """Inspect a loaded frame and return the required dataset diagnostics."""

    target_distribution: dict[str, int] = {}
    fraud_ratio: float | None = None
    warnings: list[str] = []
    if target_col in frame.columns:
        counts = frame[target_col].value_counts(dropna=False)
        target_distribution = {str(key): int(value) for key, value in counts.items()}
        numeric_target = pd.to_numeric(frame[target_col], errors="coerce")
        valid_target = numeric_target.dropna()
        if len(valid_target):
            fraud_ratio = float((valid_target == 1).mean())
    else:
        warnings.append(f"Target column '{target_col}' is absent.")

    return CsvInspection(
        path=Path("<loaded-frame>"),
        rows=len(frame),
        columns=frame.columns.drop("_source_row_order", errors="ignore").tolist(),
        dtypes={column: str(dtype) for column, dtype in frame.drop(columns=["_source_row_order"], errors="ignore").dtypes.items()},
        missing_counts={column: int(count) for column, count in frame.isna().sum().items() if column != "_source_row_order"},
        duplicate_rows=int(frame.drop(columns=["_source_row_order"], errors="ignore").duplicated().sum()),
        target_distribution=target_distribution,
        fraud_ratio=fraud_ratio,
        warnings=warnings,
    )


def inspect_csv(path: Path, target_col: str, chunksize: int = 250_000) -> CsvInspection:
    """Inspect a CSV in chunks so large auxiliary files need not fit in memory."""

    LOGGER.info("Inspecting CSV: %s", path)
    sample = pd.read_csv(path, nrows=10_000, low_memory=False)
    missing = pd.Series(0, index=sample.columns, dtype="int64")
    rows = 0
    target_counts: dict[str, int] = {}
    for chunk in pd.read_csv(path, chunksize=chunksize, low_memory=False):
        rows += len(chunk)
        missing = missing.add(chunk.isna().sum(), fill_value=0).astype("int64")
        if target_col in chunk.columns:
            counts = chunk[target_col].value_counts(dropna=False)
            for key, value in counts.items():
                key_text = str(key)
                target_counts[key_text] = target_counts.get(key_text, 0) + int(value)

    fraud_ratio: float | None = None
    if target_counts:
        numeric_keys: list[tuple[float, int]] = []
        for key, count in target_counts.items():
            try:
                numeric_keys.append((float(key), count))
            except ValueError:
                continue
        total_valid = sum(count for value, count in numeric_keys if not np.isnan(value))
        if total_valid:
            fraud_ratio = sum(count for value, count in numeric_keys if value == 1) / total_valid

    warnings: list[str] = []
    if target_col not in sample.columns:
        warnings.append(f"Target column '{target_col}' is absent.")
    warnings.append("Duplicate count for chunked inspection is omitted; exact duplicate validation is performed on the selected primary frame.")
    return CsvInspection(
        path=path,
        rows=rows,
        columns=sample.columns.tolist(),
        dtypes={column: str(dtype) for column, dtype in sample.dtypes.items()},
        missing_counts={column: int(count) for column, count in missing.items()},
        duplicate_rows=None,
        target_distribution=target_counts,
        fraud_ratio=fraud_ratio,
        warnings=warnings,
    )


def log_overview(inspection: CsvInspection, target_col: str) -> None:
    """Log the required shape, columns, dtypes, missingness and class metrics."""

    LOGGER.info("Data shape: (%s, %s)", inspection.rows, len(inspection.columns))
    LOGGER.info("Columns: %s", inspection.columns)
    LOGGER.info("Dtypes: %s", inspection.dtypes)
    LOGGER.info("Missing counts: %s", inspection.missing_counts)
    LOGGER.info("Duplicate rows: %s", inspection.duplicate_rows)
    if inspection.target_distribution:
        LOGGER.info("%s class distribution: %s", target_col, inspection.target_distribution)
        LOGGER.info("Fraud ratio: %.6f", inspection.fraud_ratio or 0.0)
    else:
        LOGGER.warning("Target '%s' is not available; fraud ratio cannot be computed.", target_col)


def inspect_discovered_csvs(csv_paths: list[Path], target_col: str) -> list[CsvInspection]:
    """Inspect all discovered files, using chunked reads for large auxiliaries."""

    return [inspect_csv(path, target_col) for path in csv_paths]
