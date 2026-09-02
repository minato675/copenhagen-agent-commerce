"""Configuration for the fraud-detection preprocessing pipeline.

The defaults are deliberately conservative prototype values.  They are kept in
one place so that a production calibration exercise can replace them without
changing feature-engineering code.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Final


# This is a configurable prototype conversion factor, not a live FX feed and
# not a fraud signal.  Supply the rate appropriate for the source snapshot.
AED_TO_KRW: Final[float] = 370.0
FRESH_ACCOUNT_DAYS: Final[int] = 14
HIGH_VELOCITY_1H_THRESHOLD: Final[float] = 10.0
HIGH_VELOCITY_24H_THRESHOLD: Final[float] = 30.0
OUTLIER_CLIP_QUANTILE: Final[float] = 0.995
MAX_ALLOWED_ROW_DROP_FRACTION: Final[float] = 0.05


@dataclass(frozen=True)
class PreprocessingConfig:
    """Runtime configuration for data discovery, feature engineering and output."""

    project_root: Path = Path(".")
    target_col: str = "is_fraud"
    raw_dir_name: str = "data/raw"
    processed_dir_name: str = "data/processed"
    reports_dir_name: str = "data/reports"
    aed_to_krw: float = AED_TO_KRW
    fresh_account_days: int = FRESH_ACCOUNT_DAYS
    high_velocity_1h_threshold: float = HIGH_VELOCITY_1H_THRESHOLD
    high_velocity_24h_threshold: float = HIGH_VELOCITY_24H_THRESHOLD
    outlier_clip_quantile: float = OUTLIER_CLIP_QUANTILE
    max_allowed_row_drop_fraction: float = MAX_ALLOWED_ROW_DROP_FRACTION
    night_start_hour: int = 23
    night_end_hour: int = 6
    max_categorical_unique: int = 100
    encode_categoricals: bool = True
    allow_missing_target: bool = False
    input_csv: Path | None = None

    @property
    def raw_dir(self) -> Path:
        """Return the preferred raw-data directory."""

        return self.project_root / self.raw_dir_name

    @property
    def processed_dir(self) -> Path:
        """Return the processed-data directory."""

        return self.project_root / self.processed_dir_name

    @property
    def reports_dir(self) -> Path:
        """Return the report directory."""

        return self.project_root / self.reports_dir_name

    @property
    def report_path(self) -> Path:
        """Return the canonical preprocessing report path."""

        return self.reports_dir / "preprocessing_report.md"

    @property
    def model_features_path(self) -> Path:
        """Return the canonical model-feature output path."""

        return self.processed_dir / "fraud_model_features.csv"

    @property
    def processed_path(self) -> Path:
        """Return the canonical all-features output path."""

        return self.processed_dir / "fraud_processed.csv"

    @property
    def feature_summary_path(self) -> Path:
        """Return the canonical feature-summary output path."""

        return self.processed_dir / "feature_summary.csv"
