"""Unit tests for the FDS preprocessing pipeline."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import numpy as np
import pandas as pd

from src.preprocessing.clean import clean_frame, fill_missing_values
from src.preprocessing.config import PreprocessingConfig
from src.preprocessing.feature_engineering import engineer_features
from src.preprocessing.load_data import MissingTargetError
from src.preprocessing.model_features import build_model_features, summarize_features
from src.preprocessing.run_preprocessing import run_pipeline
from src.preprocessing.validation import validate_final_dataset


def _sample_frame() -> pd.DataFrame:
    """Create a small explicit-label dataset covering the main feature branches."""

    return pd.DataFrame(
        {
            "transaction_id": ["t3", "t1", "t2", "t4", "t5"],
            "user_id": ["u1", "u1", "u1", "u2", "u2"],
            "timestamp": [
                "2024-01-01T00:30:00Z",
                "2024-01-01T00:00:00Z",
                "2024-01-01T00:10:00Z",
                "2024-01-02T01:00:00Z",
                "2024-01-02T01:05:00Z",
            ],
            "amount_aed": [250.0, 100.0, 0.0, 50.0, 75.0],
            "avg_item_price_aed": [125.0, 50.0, 0.0, 50.0, 37.5],
            "items_count": [2, 2, 0, 1, 2],
            "user_account_age_days": [10, 10, 10, 30, 30],
            "user_prev_chargebacks": [0, 0, 0, 1, 1],
            "user_is_high_risk": [False, False, False, True, True],
            "card_age_days": [90, 90, 90, 2, 2],
            "card_country_match": ["yes", "yes", "yes", "no", "no"],
            "shipping_billing_match": [1, 1, 1, 0, 0],
            "shipping_city": ["Dubai", "Dubai", "Dubai", "Abu Dhabi", "Abu Dhabi"],
            "billing_city": ["Dubai", "Dubai", "Dubai", "Dubai", "Dubai"],
            "ip_risk_score": [0.1, 0.1, 0.1, 0.9, 0.9],
            "transactions_last_1h": [1, 1, 2, 1, 2],
            "transactions_last_24h": [1, 1, 2, 1, 2],
            "device_type": ["mobile", "mobile", "mobile", "desktop", "desktop"],
            "browser": ["chrome", "chrome", "chrome", "safari", "safari"],
            "fraud_flag_ip": [0, 0, 0, 1, 1],
            "is_fraud": [1, 0, 0, 0, 1],
        }
    )


class PreprocessingTests(unittest.TestCase):
    """Validate required safety properties."""

    def test_full_pipeline_validation_and_model_safety(self) -> None:
        frame = _sample_frame()
        config = PreprocessingConfig(project_root=Path(tempfile.mkdtemp()))
        cleaned = clean_frame(frame, config, require_target=True, impute=False)
        engineered = engineer_features(cleaned.frame, config)
        filled, before, after, _ = fill_missing_values(engineered.frame, config.target_col)
        processed = filled.drop(columns=["_source_row_order"], errors="ignore")
        with tempfile.TemporaryDirectory() as output_dir:
            model = build_model_features(processed, config, Path(output_dir))
            validation = validate_final_dataset(
                processed,
                model.frame,
                original_rows=len(frame),
                config=config,
                timestamp_column=engineered.timestamp_column,
            )
        self.assertTrue(validation.passed, validation.errors)
        self.assertIn("is_fraud", model.frame.columns)
        self.assertTrue(set(model.frame["is_fraud"]).issubset({0, 1}))
        self.assertNotIn("transaction_id", model.frame.columns)
        self.assertNotIn("user_id", model.frame.columns)
        self.assertNotIn("fraud_flag_ip", model.frame.columns)
        self.assertIn("ip_risk_score", model.frame.columns)
        self.assertTrue(np.isfinite(processed["amount_ratio"]).all())
        self.assertTrue(processed["timestamp_utc"].is_monotonic_increasing)
        self.assertIn("transactions_last_1h_diff", engineered.frame.columns)
        self.assertIn("transactions_last_1h_diff", engineered.velocity_comparisons)
        self.assertEqual(len(processed), len(frame))
        self.assertEqual(before.get("is_fraud", 0), 0)
        self.assertEqual(after.get("is_fraud", 0), 0)

    def test_zero_amount_history_is_safe(self) -> None:
        frame = _sample_frame()
        config = PreprocessingConfig(project_root=Path(tempfile.mkdtemp()))
        cleaned = clean_frame(frame, config, require_target=True, impute=False)
        engineered = engineer_features(cleaned.frame, config)
        ratios = engineered.frame["amount_ratio"].to_numpy(dtype=float)
        self.assertTrue(np.isfinite(ratios).all())
        self.assertTrue(np.isfinite(engineered.frame["amount_zscore"]).all())

    def test_missing_target_is_never_inferred(self) -> None:
        frame = _sample_frame().drop(columns=["is_fraud"])
        config = PreprocessingConfig(project_root=Path(tempfile.mkdtemp()))
        with self.assertRaises(MissingTargetError):
            clean_frame(frame, config, require_target=True)

    def test_feature_summary_schema(self) -> None:
        summary = summarize_features(pd.DataFrame({"amount_krw": [1.0, 2.0], "is_fraud": [0, 1]}))
        self.assertEqual(
            summary.columns.tolist(),
            ["feature_name", "dtype", "missing_count", "unique_count", "min", "max", "mean", "median"],
        )

    def test_cli_pipeline_writes_requested_outputs_for_labelled_csv(self) -> None:
        with tempfile.TemporaryDirectory() as root_dir:
            root = Path(root_dir)
            raw_dir = root / "data" / "raw"
            raw_dir.mkdir(parents=True)
            _sample_frame().to_csv(raw_dir / "sample_fraud.csv", index=False)
            result = run_pipeline(PreprocessingConfig(project_root=root))
            self.assertEqual(result.status, "COMPLETED", result.validation.errors if result.validation else None)
            self.assertTrue((root / "data" / "processed" / "fraud_processed.csv").exists())
            self.assertTrue((root / "data" / "processed" / "fraud_model_features.csv").exists())
            self.assertTrue((root / "data" / "processed" / "feature_summary.csv").exists())
            self.assertTrue((root / "data" / "reports" / "preprocessing_report.md").exists())


if __name__ == "__main__":
    unittest.main()
