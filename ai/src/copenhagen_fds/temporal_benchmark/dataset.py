"""Construct observable histories and evaluator-only targets without model scores."""

from copy import deepcopy
import hashlib

from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.synthetic.generator import generate
from copenhagen_fds.synthetic.learning import OBSERVABLE_TABLES
from copenhagen_fds.synthetic.replay import instant
from copenhagen_fds.synthetic.storage import audit_records, canonical
from .protocol import BenchmarkPlan


def select_purchases(observations: dict, plan: BenchmarkPlan) -> tuple[str, ...]:
    """Select only observable arrival times, never truth, scenario or outcomes."""
    lower, upper = plan.interval()
    selected = []
    seen = {}
    for row in observations["purchases"]:
        occurred, available = instant(row["occurred_at"]), instant(row["available_at"])
        if not lower <= occurred < upper or not lower <= available < upper:
            continue
        if available < occurred:
            raise ValueError("purchase availability cannot precede occurrence")
        pid = row["purchase_request_id"]
        if seen.setdefault(pid, row) != row:
            raise ValueError("conflicting visible purchase identity")
        selected.append((available, pid))
    return tuple(pid for _, pid in sorted(set(selected))[:plan.purchase_limit])


def choose_recipe(purchase_id: str, seed: int) -> str:
    """A fixed independent assignment, shared by all attempts of a purchase."""
    recipes = ("MATCH", "PRODUCT_MISMATCH", "MISSING_ATTRIBUTE",
               "INJECTION_MATCH", "INJECTION_MISMATCH", "MISSING_TEXT")
    digest = hashlib.sha256(canonical(["semantic-assignment-v1", seed, purchase_id]).encode()).digest()
    return recipes[int.from_bytes(digest[:8], "big") % len(recipes)]


def build_dataset(plan: BenchmarkPlan) -> tuple[dict, dict]:
    """Return disjoint observable and evaluator dictionaries; no FDS execution."""
    plan = BenchmarkPlan.model_validate(plan.model_dump())
    config = plan.simulation.generator_config()
    data = generate(config, suite=plan.suite)
    if plan.distribution_variant == "DOUBLE_AMOUNTS":
        for purchase in data["purchases"]:
            purchase["amount_minor"] *= 2
            for merchant in purchase["merchant_orders"]:
                merchant["amount_minor"] *= 2
            purchase["order_version"] += "-double-amounts-v1"
            snapshot = {name: purchase[name] for name in ("merchant_orders", "amount_minor", "currency")}
            purchase["snapshot_hash"] = personalization_hash(snapshot)
        for persona in data["personas"]:
            persona["amount_median_minor"] *= 2
    audit_records(data, config)
    observations = {name: deepcopy(data[name]) for name in OBSERVABLE_TABLES}
    selected = select_purchases(observations, plan)
    if not selected:
        raise ValueError("no observable purchases in the frozen evaluation interval")
    selected_set = set(selected)
    evaluator = dict(
        truth=[deepcopy(row) for row in data["truth"] if row["purchase_request_id"] in selected_set],
        personas=deepcopy(data["personas"]), selected_purchase_ids=list(selected),
        label_origin="SYNTHETIC_GENERATOR_CAUSE_NOT_MODEL_PREDICTION",
        behavioral_axes_note="Declared simulator perturbations, not exact score labels.",
        late_arrivals_excluded=sum(1 for row in data["purchases"]
            if plan.interval()[0] <= instant(row["occurred_at"]) < plan.interval()[1]
            and instant(row["available_at"]) >= plan.interval()[1]),
    )
    return observations, evaluator
