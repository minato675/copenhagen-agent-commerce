"""Markdown report generation for preprocessing auditability."""

from __future__ import annotations

from pathlib import Path
from typing import Any

import pandas as pd

from .config import PreprocessingConfig
from .load_data import CsvInspection


def _bullet_lines(values: list[str], empty: str = "- None") -> list[str]:
    return [f"- {value}" for value in values] if values else [empty]


def _format_ratio(frame: pd.DataFrame | None, target_col: str) -> str:
    if frame is None or target_col not in frame.columns:
        return "N/A (target column absent)"
    target = pd.to_numeric(frame[target_col], errors="coerce").dropna()
    if target.empty:
        return "N/A (no valid target rows)"
    return f"{float((target == 1).mean()):.6f}"


def _inspection_section(inspections: list[CsvInspection]) -> list[str]:
    lines = ["## Discovered CSV Schema", ""]
    if not inspections:
        return lines + ["- No CSV inspection was available.", ""]
    for inspection in inspections:
        lines.extend(
            [
                f"### `{inspection.path.name}`",
                f"- Rows: {inspection.rows if inspection.rows is not None else 'N/A'}",
                f"- Columns: {', '.join(inspection.columns)}",
                f"- Dtypes: `{inspection.dtypes}`",
                f"- Missing counts: `{inspection.missing_counts}`",
                f"- Duplicate rows: {inspection.duplicate_rows if inspection.duplicate_rows is not None else 'not computed for chunked auxiliary inspection'}",
            ]
        )
        if inspection.target_distribution:
            lines.append(f"- `{inspection.path.name}` target distribution: `{inspection.target_distribution}`")
        else:
            lines.append("- Target distribution: unavailable")
        lines.append("")
    return lines


def write_preprocessing_report(
    path: Path,
    config: PreprocessingConfig,
    inspections: list[CsvInspection],
    selected_path: Path | None,
    original_rows: int,
    original_columns: list[str],
    original_fraud_ratio: float | None,
    processed: pd.DataFrame | None = None,
    model_features: pd.DataFrame | None = None,
    removed_leakage: dict[str, str] | None = None,
    removed_identifiers: list[str] | None = None,
    missing_before: dict[str, int] | None = None,
    missing_after: dict[str, int] | None = None,
    created_features: list[str] | None = None,
    skipped_features: dict[str, str] | None = None,
    numeric_features: list[str] | None = None,
    categorical_features: list[str] | None = None,
    outlier_stats: dict[str, dict[str, float]] | None = None,
    velocity_comparisons: dict[str, dict[str, float | int]] | None = None,
    validation_errors: list[str] | None = None,
    warnings: list[str] | None = None,
    status: str = "COMPLETED",
) -> Path:
    """Write the requested preprocessing report, including blocked-run diagnostics."""

    removed_leakage = removed_leakage or {}
    removed_identifiers = removed_identifiers or []
    missing_before = missing_before or {}
    missing_after = missing_after or {}
    created_features = created_features or []
    skipped_features = skipped_features or {}
    numeric_features = numeric_features or []
    categorical_features = categorical_features or []
    outlier_stats = outlier_stats or {}
    velocity_comparisons = velocity_comparisons or {}
    validation_errors = validation_errors or []
    warnings = warnings or []

    lines = [
        "# UAE E-Commerce Fraud Preprocessing Report",
        "",
        f"**Status:** {status}",
        "",
        "## Original Dataset",
        f"- Selected input: `{selected_path.name if selected_path else 'N/A'}`",
        f"- Rows: {original_rows}",
        f"- Columns: {len(original_columns)}",
        f"- Fraud Ratio: {f'{original_fraud_ratio:.6f}' if original_fraud_ratio is not None else 'N/A (is_fraud is absent)'}",
        "",
    ]
    lines.extend(_inspection_section(inspections))
    lines.extend(["## Data Leakage Columns Removed", ""])
    if removed_leakage:
        lines.extend([f"- `{column}` — {reason}" for column, reason in removed_leakage.items()])
    else:
        lines.append("- None detected in the selected input.")
    lines.extend(["", "## Identifier Columns Removed", ""])
    lines.extend(_bullet_lines(removed_identifiers))
    lines.extend(["", "## Missing Values", ""])
    lines.append(f"- Before imputation: `{missing_before}`" if missing_before else "- Not run.")
    lines.append(f"- After imputation: `{missing_after}`" if missing_after else "- Not run.")
    lines.extend(
        [
            "",
            "## Korea Adaptation",
            "- AED → KRW: numeric AED companion columns are created using configurable `AED_TO_KRW`; original AED values are retained.",
            "- UTC → KST: parsed timestamps are converted to `Asia/Seoul` and used for hour/day features.",
            "- UAE-dependent feature handling: relation features such as country/city match are preferred over raw UAE city/country labels.",
            "",
            "## Feature Engineering",
        ]
    )
    lines.extend(_bullet_lines(created_features))
    if skipped_features:
        lines.append("")
        lines.append("### Skipped Features")
        lines.extend(f"- `{feature}` — {reason}" for feature, reason in skipped_features.items())
    lines.extend(["", "### Outlier Percentiles"])
    if outlier_stats:
        lines.extend(f"- `{feature}` — `{stats}`" for feature, stats in outlier_stats.items())
    else:
        lines.append("- No numeric amount/velocity feature was available.")
    lines.extend(["", "### Raw vs Calculated Velocity Comparison"])
    if velocity_comparisons:
        lines.extend(f"- `{feature}` — `{stats}`" for feature, stats in velocity_comparisons.items())
    else:
        lines.append("- Not available because raw and calculated velocity columns were not both present.")
    lines.extend(
        [
            "",
            "## Final Dataset",
            f"- Rows: {len(processed) if processed is not None else 'N/A'}",
            f"- Features: {len(model_features.columns) - 1 if model_features is not None and config.target_col in model_features.columns else 'N/A'}",
            f"- Fraud Ratio: {_format_ratio(model_features, config.target_col)}",
            f"- Numeric Features: {', '.join(numeric_features) if numeric_features else 'N/A'}",
            f"- Categorical Features: {', '.join(categorical_features) if categorical_features else 'N/A'}",
            "",
            "## Output Files",
        ]
    )
    if processed is not None and model_features is not None:
        lines.extend(
            [
                f"- `{config.processed_path.as_posix()}`",
                f"- `{config.model_features_path.as_posix()}`",
                f"- `{config.feature_summary_path.as_posix()}`",
            ]
        )
    else:
        lines.append("- Model CSV outputs were not written because an explicit `is_fraud` target was unavailable.")
    lines.extend(["", "## Warnings / Limitations"])
    lines.extend(_bullet_lines(warnings))
    if validation_errors:
        lines.extend(["", "### Validation Errors"])
        lines.extend(f"- {error}" for error in validation_errors)
    lines.extend(
        [
            "",
            "> 본 데이터는 UAE 전자상거래 환경을 기반으로 생성된 Synthetic Fraud Dataset이며, 한국 실제 금융거래 데이터가 아니다. 따라서 본 전처리는 AI Agent 기반 결제 FDS Prototype 검증을 위한 Domain Adaptation이며 실제 금융기관 Production FDS를 의미하지 않는다.",
            "",
            "The pipeline never infers `is_fraud` from event type, transaction status, amount or any other proxy. A label-bearing dataset is required for model training outputs.",
            "",
        ]
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines), encoding="utf-8")
    return path
