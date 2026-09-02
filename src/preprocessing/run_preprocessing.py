"""CLI entry point for the FDS preprocessing pipeline.

Typical usage from the project root::

    python -m src.preprocessing.run_preprocessing

The current repository is also supported when CSVs are stored directly in the
project root.  A run with no explicit ``is_fraud`` column writes a diagnostic
report only and exits with a non-zero status unless ``--allow-missing-target``
is supplied; it never fabricates a fraud label.
"""

from __future__ import annotations

import argparse
import logging
import sys
from dataclasses import dataclass, field
from pathlib import Path

import pandas as pd

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from src.preprocessing.clean import CleaningResult, clean_frame, fill_missing_values
from src.preprocessing.config import PreprocessingConfig
from src.preprocessing.feature_engineering import FeatureEngineeringResult, engineer_features
from src.preprocessing.load_data import (
    CsvInspection,
    choose_primary_csv,
    discover_csvs,
    inspect_frame,
    load_primary_csv,
    log_overview,
    read_csv_header,
)
from src.preprocessing.model_features import ModelFeaturesResult, build_model_features, summarize_features
from src.preprocessing.report import write_preprocessing_report
from src.preprocessing.validation import ValidationResult, validate_final_dataset


LOGGER = logging.getLogger("fds_preprocessing")


@dataclass
class PipelineResult:
    """Result object returned by :func:`run_pipeline`."""

    status: str
    selected_path: Path | None = None
    inspections: list[CsvInspection] = field(default_factory=list)
    original_rows: int = 0
    original_columns: list[str] = field(default_factory=list)
    original_fraud_ratio: float | None = None
    processed: pd.DataFrame | None = None
    model_features: ModelFeaturesResult | None = None
    cleaning: CleaningResult | None = None
    engineering: FeatureEngineeringResult | None = None
    validation: ValidationResult | None = None
    report_path: Path | None = None
    warnings: list[str] = field(default_factory=list)


def configure_logging(verbose: bool = False) -> None:
    """Configure concise console logging for pipeline milestones."""

    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(asctime)s | %(levelname)s | %(name)s | %(message)s",
    )


def _auxiliary_inspections(csv_paths: list[Path], selected: Path, target_col: str) -> list[CsvInspection]:
    """Create lightweight schema records for auxiliary files.

    The item-property files in the supplied repository are hundreds of MB each;
    their headers are useful for the report, but treating them as transaction
    rows would be incorrect and unnecessarily expensive.
    """

    inspections: list[CsvInspection] = []
    for path in csv_paths:
        header = read_csv_header(path)
        inspections.append(
            CsvInspection(
                path=path,
                rows=None,
                columns=header,
                dtypes={},
                missing_counts={},
                duplicate_rows=None,
                warnings=["Only the header was inspected before primary-file loading."],
            )
        )
    return inspections


def _combined_warnings(
    selection_warnings: list[str],
    cleaning: CleaningResult | None,
    engineering: FeatureEngineeringResult | None,
    model_features: ModelFeaturesResult | None,
    validation: ValidationResult | None,
) -> list[str]:
    warnings = list(selection_warnings)
    for result in (cleaning, engineering, model_features):
        if result is not None:
            warnings.extend(result.warnings)
    if validation is not None:
        warnings.extend(validation.warnings)
    return list(dict.fromkeys(warnings))


def _write_blocked_report(
    config: PreprocessingConfig,
    csv_paths: list[Path],
    selected_path: Path,
    inspections: list[CsvInspection],
    original: pd.DataFrame,
    selection_warnings: list[str],
) -> PipelineResult:
    """Write an honest schema report when no explicit fraud target exists."""

    inspection = inspect_frame(original, config.target_col)
    warning_list = list(selection_warnings)
    warning_list.extend(inspection.warnings)
    warning_list.extend(
        [
            f"Selected input columns are {inspection.columns}; this schema does not contain '{config.target_col}'.",
            "The discovered files match an interaction/event-log layout rather than the requested UAE E-Commerce Fraud Dataset schema.",
            "No target was inferred from event='transaction' or transactionid presence; those are not fraud labels.",
            "Place the actual label-bearing UAE fraud CSV under data/raw (or pass --input-csv) and rerun.",
        ]
    )
    report_path = write_preprocessing_report(
        config.report_path,
        config,
        inspections,
        selected_path,
        original_rows=len(original),
        original_columns=inspection.columns,
        original_fraud_ratio=None,
        warnings=warning_list,
        status="BLOCKED_MISSING_TARGET",
    )
    LOGGER.error(
        "Cannot create fraud_model_features.csv: explicit target '%s' is missing from %s.",
        config.target_col,
        selected_path.name,
    )
    return PipelineResult(
        status="BLOCKED_MISSING_TARGET",
        selected_path=selected_path,
        inspections=inspections,
        original_rows=len(original),
        original_columns=inspection.columns,
        original_fraud_ratio=None,
        report_path=report_path,
        warnings=list(dict.fromkeys(warning_list)),
    )


def run_pipeline(config: PreprocessingConfig) -> PipelineResult:
    """Run discovery, cleaning, feature engineering, encoding, validation and reporting."""

    csv_paths = discover_csvs(config)
    selected_path, selection_warnings = choose_primary_csv(csv_paths, config)
    LOGGER.info("Discovered CSVs: %s", [path.name for path in csv_paths])
    LOGGER.info("Primary CSV: %s", selected_path)
    inspections = _auxiliary_inspections(csv_paths, selected_path, config.target_col)
    original = load_primary_csv(selected_path)
    original_inspection = inspect_frame(original, config.target_col)
    original_inspection.path = selected_path
    inspections = [
        original_inspection if inspection.path == selected_path else inspection for inspection in inspections
    ]
    log_overview(original_inspection, config.target_col)

    if config.target_col not in original.columns:
        result = _write_blocked_report(
            config,
            csv_paths,
            selected_path,
            inspections,
            original,
            selection_warnings,
        )
        if not config.allow_missing_target:
            result.warnings.append("Strict mode: rerun with a label-bearing CSV; --allow-missing-target is report-only mode.")
        return result

    cleaning = clean_frame(original, config, require_target=True, impute=False, logger=LOGGER)
    engineered = engineer_features(cleaning.frame, config, logger=LOGGER)
    filled, missing_before, missing_after, missing_warnings = fill_missing_values(
        engineered.frame, config.target_col, LOGGER
    )
    cleaning.missing_before = missing_before
    cleaning.missing_after = missing_after
    cleaning.warnings.extend(missing_warnings)
    processed = filled.drop(columns=["_source_row_order"], errors="ignore")

    model_features = build_model_features(
        processed,
        config,
        output_dir=config.processed_dir,
        logger=LOGGER,
    )
    feature_summary = summarize_features(model_features.frame)
    validation = validate_final_dataset(
        processed,
        model_features.frame,
        original_rows=len(original),
        config=config,
        timestamp_column=engineered.timestamp_column,
        logger=LOGGER,
    )

    config.processed_dir.mkdir(parents=True, exist_ok=True)
    processed.to_csv(config.processed_path, index=False)
    model_features.frame.to_csv(config.model_features_path, index=False)
    feature_summary.to_csv(config.feature_summary_path, index=False)
    status = "COMPLETED" if validation.passed else "VALIDATION_FAILED"
    warning_list = _combined_warnings(selection_warnings, cleaning, engineered, model_features, validation)
    report_path = write_preprocessing_report(
        config.report_path,
        config,
        inspections,
        selected_path,
        original_rows=len(original),
        original_columns=original_inspection.columns,
        original_fraud_ratio=original_inspection.fraud_ratio,
        processed=processed,
        model_features=model_features.frame,
        removed_leakage=cleaning.removed_leakage_columns,
        removed_identifiers=model_features.removed_identifier_columns,
        missing_before=cleaning.missing_before,
        missing_after=cleaning.missing_after,
        created_features=engineered.created_features,
        skipped_features=engineered.skipped_features,
        numeric_features=model_features.numeric_features,
        categorical_features=model_features.categorical_features,
        outlier_stats=engineered.outlier_stats,
        velocity_comparisons=engineered.velocity_comparisons,
        validation_errors=validation.errors,
        warnings=warning_list,
        status=status,
    )
    return PipelineResult(
        status=status,
        selected_path=selected_path,
        inspections=inspections,
        original_rows=len(original),
        original_columns=original_inspection.columns,
        original_fraud_ratio=original_inspection.fraud_ratio,
        processed=processed,
        model_features=model_features,
        cleaning=cleaning,
        engineering=engineered,
        validation=validation,
        report_path=report_path,
        warnings=warning_list,
    )


def print_summary(result: PipelineResult) -> None:
    """Print the requested terminal summary without hiding blocked states."""

    original_rows = result.original_rows
    processed_rows = len(result.processed) if result.processed is not None else "N/A"
    fraud_rate = result.original_fraud_ratio
    if result.model_features is not None and "is_fraud" in result.model_features.frame.columns:
        target = result.model_features.frame["is_fraud"]
        fraud_rate = float(pd.to_numeric(target, errors="coerce").mean())
    created = result.engineering.created_features if result.engineering else []
    numeric = result.model_features.numeric_features if result.model_features else []
    categorical = result.model_features.categorical_features if result.model_features else []
    leakage = list(result.cleaning.removed_leakage_columns) if result.cleaning else []
    identifiers = result.model_features.removed_identifier_columns if result.model_features else []

    title = "FDS PREPROCESSING COMPLETE" if result.status == "COMPLETED" else f"FDS PREPROCESSING {result.status}"
    print("\n========================================")
    print(title)
    print("========================================\n")
    print(f"Original Rows: {original_rows}")
    print(f"Processed Rows: {processed_rows}")
    print(f"\nFraud Rate: {f'{fraud_rate:.6f}' if fraud_rate is not None else 'N/A'}")
    print(f"\nRemoved Leakage Columns: {leakage or 'None'}")
    print(f"Removed Identifier Columns: {identifiers or 'None'}")
    print(f"\nCreated Features: {created or 'None (schema did not provide source columns)'}")
    print(f"\nNumeric Features: {numeric or 'N/A'}")
    print(f"Categorical Features: {categorical or 'N/A'}")
    print("\nOutput:")
    if result.processed is not None:
        print("- fraud_processed.csv")
        print("- fraud_model_features.csv")
        print("- feature_summary.csv")
    else:
        print("- preprocessing_report.md")
        print("- fraud CSV outputs skipped: explicit is_fraud target is required")
    print("\nWarnings:")
    for warning in result.warnings:
        print(f"- {warning}")
    if result.validation and result.validation.errors:
        for error in result.validation.errors:
            print(f"- VALIDATION ERROR: {error}")
    print("========================================")


def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Preprocess a fraud dataset for FDS model training.")
    parser.add_argument("--project-root", type=Path, default=Path.cwd())
    parser.add_argument("--input-csv", type=Path, default=None)
    parser.add_argument("--allow-missing-target", action="store_true", help="Write report-only diagnostics when is_fraud is absent.")
    parser.add_argument("--aed-to-krw", type=float, default=None)
    parser.add_argument("--no-encode-categoricals", action="store_true")
    parser.add_argument("--verbose", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    """CLI main function."""

    args = _parse_args(argv)
    configure_logging(args.verbose)
    project_root = args.project_root.resolve()
    config_kwargs = {
        "project_root": project_root,
        "input_csv": args.input_csv,
        "allow_missing_target": args.allow_missing_target,
        "encode_categoricals": not args.no_encode_categoricals,
    }
    if args.aed_to_krw is not None:
        config_kwargs["aed_to_krw"] = args.aed_to_krw
    config = PreprocessingConfig(**config_kwargs)
    try:
        result = run_pipeline(config)
        print_summary(result)
        return 0 if result.status == "COMPLETED" or config.allow_missing_target else 2
    except Exception:
        LOGGER.exception("FDS preprocessing failed.")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
