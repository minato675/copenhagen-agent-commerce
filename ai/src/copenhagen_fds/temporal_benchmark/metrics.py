"""Pure evaluation metrics; scores describe deviations, not fraud probabilities.

Binary ``status`` is the status of the behavior score calculation, NOT the
overall evaluation or GPT call. A successful behavior score remains measurable
when GPT fails. Scores retained by a failed/incomplete behavior calculation are
counted, but excluded from confusion/ranking metrics and score coverage.

All performance metrics use labeled, eligible scored rows. Overall coverage and
labeled coverage expose that conditional denominator; missing scores are never
converted to zero. Average precision groups equal scores before computing each
recall increment. It is not trapezoidal PR-AUC.
"""

from collections import Counter
from collections.abc import Iterable, Mapping
import math


SCORE_STATUSES = ("SUCCESS", "INCOMPLETE", "FAILED", "CANCELLED", "UNKNOWN", "NOT_RUN")
SEMANTIC_LABELS = ("MATCH", "MISMATCH", "UNKNOWN")
SEMANTIC_OUTCOMES = (*SEMANTIC_LABELS, "FAILED", "NOT_RUN")


def _ratio(numerator: int, denominator: int) -> float | None:
    return numerator / denominator if denominator else None


def _score(value: object) -> float:
    if type(value) not in (int, float) or not 0 <= value <= 100 or not math.isfinite(value):
        raise ValueError("score and threshold must be finite numbers from 0 through 100")
    return float(value)


def _row_keys(row: object, expected: set[str]) -> None:
    if not isinstance(row, Mapping) or set(row) != expected:
        raise ValueError("metric row must contain exactly the declared fields")


def _average_precision(scored: list[tuple[float, bool]]) -> float | None:
    positives = sum(label for _, label in scored)
    if not positives:
        return None
    # Sorting within tied scores must never change the measured ranking quality.
    groups: dict[float, list[int]] = {}
    for score, label in scored:
        counts = groups.setdefault(score, [0, 0])
        counts[0] += int(label)
        counts[1] += 1
    true_positive = observed = 0
    terms = []
    for score in sorted(groups, reverse=True):
        positive_count, count = groups[score]
        true_positive += positive_count
        observed += count
        terms.append((positive_count / positives) * (true_positive / observed))
    return math.fsum(terms)


def binary_metrics(rows: Iterable[Mapping], *, threshold: float) -> dict:
    """Measure labeled successful behavior scores at an inclusive threshold.

    Each row has exactly ``label`` (bool or None), ``score`` (0..100 or None)
    and ``status`` (behavior calculation status). Unknown labels never become
    negatives. ``coverage`` is eligible scores / all rows, regardless of label;
    ``labeled_coverage`` is eligible labeled scores / all labeled rows.
    ``n_missing_score`` counts null scores, including unlabeled/failed rows.
    Non-success retained scores are reported separately and never evaluated.
    """
    threshold = _score(threshold)
    total = labeled = scored_count = missing_score = excluded_score = 0
    statuses = Counter({status: 0 for status in SCORE_STATUSES})
    scored = []
    confusion = dict(tp=0, fp=0, fn=0, tn=0)
    for row in rows:
        _row_keys(row, {"label", "score", "status"})
        label, score, status = row["label"], row["score"], row["status"]
        if label is not None and type(label) is not bool:
            raise ValueError("binary ground truth must be bool or None")
        if not isinstance(status, str) or status not in SCORE_STATUSES:
            raise ValueError("unsupported behavior score status")
        if score is not None:
            score = _score(score)
        total += 1
        labeled += label is not None
        statuses[status] += 1
        if score is None:
            missing_score += 1
            continue
        if status != "SUCCESS":
            excluded_score += 1
            continue
        scored_count += 1
        if label is None:
            continue
        scored.append((score, label))
        positive = score >= threshold
        key = ("tp" if label else "fp") if positive else ("fn" if label else "tn")
        confusion[key] += 1
    tp, fp, fn, tn = (confusion[key] for key in ("tp", "fp", "fn", "tn"))
    return dict(
        n_total=total, n_labeled=labeled, n_unknown_label=total - labeled,
        n_scored=scored_count, n_scored_labeled=len(scored),
        n_missing_score=missing_score, n_excluded_retained_score=excluded_score,
        coverage=_ratio(scored_count, total), labeled_coverage=_ratio(len(scored), labeled),
        status_counts=dict(statuses), threshold=threshold,
        score_quality_rule="SUCCESS_BEHAVIOR_SCORE_ONLY",
        performance_denominator="LABELED_SUCCESSFUL_BEHAVIOR_SCORES",
        confusion=confusion, precision=_ratio(tp, tp + fp), recall=_ratio(tp, tp + fn),
        false_positive_rate=_ratio(fp, fp + tn), average_precision=_average_precision(scored),
        rank_metric="AVERAGE_PRECISION_TIED_SCORE_GROUPS",
    )


def semantic_confusion(rows: Iterable[Mapping]) -> dict:
    """Keep expected UNKNOWN, missing labels, and execution failures distinct.

    Rows contain exactly ``expected`` (MATCH/MISMATCH/UNKNOWN or None) and
    ``actual`` (MATCH/MISMATCH/UNKNOWN/FAILED/NOT_RUN). UNKNOWN is a semantic
    label, not a missing reference label. Failures stay in the labeled accuracy
    denominator and their own matrix columns; they cannot become MATCH.
    """
    confusion = {expected: {actual: 0 for actual in SEMANTIC_OUTCOMES}
                 for expected in SEMANTIC_LABELS}
    actual_counts = Counter({actual: 0 for actual in SEMANTIC_OUTCOMES})
    total = labeled = correct = 0
    for row in rows:
        _row_keys(row, {"expected", "actual"})
        expected, actual = row["expected"], row["actual"]
        if expected is not None and (not isinstance(expected, str) or expected not in SEMANTIC_LABELS):
            raise ValueError("unsupported semantic reference label")
        if not isinstance(actual, str) or actual not in SEMANTIC_OUTCOMES:
            raise ValueError("unsupported semantic observation")
        total += 1
        actual_counts[actual] += 1
        if expected is not None:
            labeled += 1
            confusion[expected][actual] += 1
            correct += expected == actual
    resolved = actual_counts["MATCH"] + actual_counts["MISMATCH"]
    return dict(
        n_total=total, n_labeled=labeled, n_unknown_label=total - labeled,
        n_expected_unknown=sum(confusion["UNKNOWN"].values()),
        actual_counts=dict(actual_counts), confusion=confusion,
        exact_match_accuracy=_ratio(correct, labeled),
        completed_coverage=_ratio(resolved + actual_counts["UNKNOWN"], total),
        resolved_coverage=_ratio(resolved, total),
        performance_denominator="ALL_LABELED_SEMANTIC_ROWS_INCLUDING_FAILURES",
    )
