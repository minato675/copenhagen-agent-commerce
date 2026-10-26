"""Versioned, finite semantic equivalents for the unchanged synthetic v1 inputs."""

from collections import Counter
import hashlib
import json

from copenhagen_fds.contracts import IntentComparisonOutput
from copenhagen_fds.evaluation.models import FieldCheck


LEGACY_GRADER_VERSION = "fds-intent-grader-v1"
GRADER_VERSION = "fds-intent-grader-v2"
GRADER_VERSIONS = (LEGACY_GRADER_VERSION, GRADER_VERSION)
V1_DATASET_SHA256 = "ace399e23dc6d9d8f2082a769837acfdc997e9093d67d1ffaef1d8699d3e4546"

# Alternatives describe the same missing request information, not arbitrary
# substitutions between reason codes. References remain empty for these causes.
REASON_ALTERNATIVES = {
    "fds-04-no-quantity": [("INSUFFICIENT_DATA", "QUANTITY", "PROVIDE_MISSING_DATA"),
                           ("INSUFFICIENT_DATA", "QUANTITY", "CLARIFY_INTENT")],
    "fds-05-no-currency": [("INSUFFICIENT_DATA", "BUDGET", "PROVIDE_MISSING_DATA"),
                           ("INSUFFICIENT_DATA", "BUDGET", "CLARIFY_INTENT")],
    "fds-06-budget-kind": [("INTENT_UNCERTAIN", "BUDGET", "CLARIFY_INTENT")],
    "fds-17-purpose-only": [("INSUFFICIENT_DATA", "PRODUCT", "PROVIDE_MISSING_DATA"),
                            ("INTENT_UNCERTAIN", "PRODUCT", "CLARIFY_INTENT")],
}
FIELD_ALTERNATIVES = {
    # The product name already identifies the product. A nullable category need
    # not repeat it; no other name, quantity or brand is accepted by this rule.
    "fds-19-source-unknown": {
        "items": [[{"name": "키보드", "quantity": 2, "brand": None, "category": None}]],
    },
    # These requests explicitly contain '2개만'. Keeping that literal constraint
    # duplicates the quantity field but does not invent a new semantic condition.
    "fds-16-second-merchant": {"explicit_conditions": [["2개만"]]},
    "fds-23-injection-mismatch": {"explicit_conditions": [["2개만"]]},
}
RUBRIC_SHA256 = hashlib.sha256(json.dumps({
    "grader": GRADER_VERSION, "dataset": V1_DATASET_SHA256,
    "reasons": REASON_ALTERNATIVES, "fields": FIELD_ALTERNATIVES,
}, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode("utf-8")).hexdigest()


def field_alternatives(case, check):
    return tuple(FieldCheck(path=check.path, operation="equals", value=value)
                 for value in FIELD_ALTERNATIVES.get(case.case_id, {}).get(check.path, ()))


def comparison_variants(case):
    variants = [case.mock_comparison]
    for code, dimension, confirmation in REASON_ALTERNATIVES.get(case.case_id, ()):
        variants.append(IntentComparisonOutput.model_validate({
            "comparison": "UNKNOWN",
            "findings": [{"code": code, "dimension": dimension, "related_items": []}],
            "confirmation_requests": [{"code": confirmation, "related_items": []}],
        }))
    return variants


def signature(entry):
    return (entry.code, getattr(entry, "dimension", None),
            tuple(sorted((ref.order_id, ref.item_id) for ref in entry.related_items)))


def check_reasons(case, actual):
    """Require one complete allowed explanation; reject extras and duplicates."""
    findings = Counter(map(signature, actual.findings))
    confirmations = Counter(map(signature, actual.confirmation_requests))
    variants = comparison_variants(case)
    findings_ok = any(findings == Counter(map(signature, v.findings)) for v in variants)
    confirmations_ok = any(confirmations == Counter(map(signature, v.confirmation_requests)) for v in variants)
    paired = any(findings == Counter(map(signature, v.findings))
                 and confirmations == Counter(map(signature, v.confirmation_requests)) for v in variants)
    return findings_ok, confirmations_ok, paired
