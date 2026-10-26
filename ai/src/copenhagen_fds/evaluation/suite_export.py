"""Offline suite export and review: generated artifacts do not approve labels."""

import argparse
from collections import Counter, defaultdict
from html import escape
import json
from pathlib import Path
import re
import unicodedata

from copenhagen_fds.analysis.comparison_prompt import COMPARISON_EXAMPLES, build_comparison_prompt
from copenhagen_fds.evaluation.cases import load_dataset
from copenhagen_fds.evaluation.experiments import digest, make_plan
from copenhagen_fds.evaluation.suite_cases import load_holdout_dataset, load_stability_dataset


def normalize(value):
    """Ignore identity/whitespace changes while retaining substantive facts."""
    if isinstance(value, dict):
        return {k: normalize(v) for k, v in value.items() if k not in {"order_id", "item_id"}}
    if isinstance(value, (list, tuple)):
        return [normalize(v) for v in value]
    if isinstance(value, str):
        return " ".join(unicodedata.normalize("NFKC", value).casefold().split())
    return value


def comparison_content(case):
    prompt = build_comparison_prompt(case.request.with_extracted_intent(case.mock_extraction),
                                     clarifications=tuple(case.mock_extraction.ambiguities or ()))
    return normalize(json.loads(prompt.user_message))


def group_id(case):
    return case.case_id.rsplit("-", 1)[0] if case.case_id.startswith("ho-") else case.case_id


def words(value):
    if isinstance(value, str):
        return set(re.findall(r"[\w]+", normalize(value)))
    if isinstance(value, dict):
        return set().union(*(words(v) for v in value.values()))
    if isinstance(value, (list, tuple)):
        return set().union(*(words(v) for v in value))
    return set()


def audit_suite(dataset, development_datasets=(), prompt_examples=COMPARISON_EXAMPLES):
    comparisons = {c.case_id: comparison_content(c) for c in dataset.cases}
    fingerprints = defaultdict(list)
    for key, content in comparisons.items():
        fingerprints[digest(content)].append(key)
    development = defaultdict(list)
    invalid_development_inputs = []
    for known in development_datasets:
        for case in known.cases:
            try:
                content = comparison_content(case)
            except ValueError:
                invalid_development_inputs.append(case.case_id)
                continue
            development[digest(content)].append(case.case_id)
    for index, example in enumerate(prompt_examples, 1):
        development[digest(normalize(example["input"]))].append(f"prompt-example-{index}")
    overlaps = [{"case_id": key, "matches": development[digest(content)]}
                for key, content in comparisons.items() if digest(content) in development]
    token_sets = {key: words(value) for key, value in comparisons.items()}
    similar = []
    keys = list(comparisons)
    cases = {c.case_id: c for c in dataset.cases}
    for index, first in enumerate(keys):
        for second in keys[index + 1:]:
            a, b = token_sets[first], token_sets[second]
            similarity = len(a & b) / len(a | b) if a | b else 1
            if similarity >= .8:
                similar.append({"first": first, "second": second, "token_jaccard": round(similarity, 4),
                                "same_counterfactual_group": group_id(cases[first]) == group_id(cases[second])})
    return {
        "dataset_sha256": dataset.sha256, "case_count": len(dataset.cases),
        "label_counts": dict(Counter(c.expected.comparison for c in dataset.cases)),
        "scenario_group_count": len({group_id(c) for c in dataset.cases}),
        "exact_comparison_duplicates": [v for v in fingerprints.values() if len(v) > 1],
        "development_or_prompt_exact_overlaps": overlaps,
        "development_cases_without_valid_comparison_input": invalid_development_inputs,
        "near_pair_heuristic": "NFKC/casefold value tokens, Jaccard >= 0.8; not semantic equivalence proof",
        "near_pairs_for_review": similar,
        "human_review_status": "PENDING",
        "sampling_limit": ("100 fact scenarios with 3 related counterfactuals; not 300 independent random samples"
                           if len(dataset.cases) == 300 else "20 authored probes; repeated trials do not add independent inputs"),
    }


def review_html(dataset, audit):
    labels = ("MATCH", "MISMATCH", "UNKNOWN")
    families = sorted({c.categories[0] for c in dataset.cases})
    cards = []
    for case in dataset.cases:
        def pre(value):
            return "<pre>" + escape(json.dumps(value, ensure_ascii=False, indent=2)) + "</pre>"
        payload = {"reference_intent": case.mock_extraction.model_dump(mode="json"),
                   "expected_comparison": case.mock_comparison.model_dump(mode="json"),
                   "field_checks": [c.model_dump(mode="json") for c in case.expected.fields]}
        cards.append(f'<details data-label="{case.expected.comparison}" data-family="{escape(case.categories[0])}">'
                     f'<summary>{escape(case.case_id)} · {case.expected.comparison}</summary>'
                     f'<p><b>요청:</b> {escape(case.request.user_request.text or "")}</p>'
                     f'<p><b>판정 근거:</b> {escape(case.rubric)}</p>'
                     f'<p>분류: {escape(", ".join(case.categories))} · 출처: synthetic-agent-authored</p>'
                     f'<p>사례 해시: <code>{digest(case.model_dump(mode="json"))}</code></p>'
                     '<h3>주문 사실</h3>' + pre(case.request.order.model_dump(mode="json")) +
                     '<h3>검토할 기준 답안과 채점 항목</h3>' + pre(payload) + '</details>')
    options = lambda values: ''.join(f'<option>{escape(v)}</option>' for v in values)
    return '''<!doctype html><html lang="ko"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
<title>FDS 평가 후보 정답 검토</title><style>
body{font:16px/1.65 system-ui,sans-serif;max-width:1040px;margin:32px auto;padding:0 20px;color:#182233;background:#f4f6fa}
header,details{background:white;padding:20px;border:1px solid #d6dee9;border-radius:10px;margin:12px 0}
h1{font-size:27px}h3{font-size:17px}summary{cursor:pointer;font-weight:700}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f5f7fa;padding:14px;font-size:13px}
code{overflow-wrap:anywhere}select,input{font:inherit;padding:8px;max-width:100%;margin:5px}nav{position:sticky;top:0;background:#edf2f9;padding:8px;border:1px solid #d6dee9}[hidden]{display:none}
</style><header><h1>FDS 평가 후보 정답 검토</h1>
<p><b>사람 검토 전 · 유료 실행 전</b> — 모델 응답으로 정답을 만들지 않았습니다. 각 요청·주문 사실과 정답·참조·근거를 검토해 주세요. 이 페이지에는 승인 제출 기능이 없습니다.</p>''' + \
        f'<p>버전: {escape(dataset.version)} · 사례: {len(dataset.cases)}건 · 라벨: {escape(str(audit["label_counts"]))}</p>' + \
        f'<p>데이터셋 SHA-256: <code>{dataset.sha256}</code></p>' + \
        '''<p>홀드아웃 300건은 100개 사실 상황의 세 가지 변형이며 통계적으로 독립된 300개 표본은 아닙니다. 단순 어순 변경만으로 새 사례를 만들지 않았지만, 같은 유형의 틀을 공유하므로 실제 서비스 분포에 대한 보장은 아닙니다.</p>
<p>추출 필드 지표는 상품명·수량·브랜드·예산의 지정 사실과 필수/선호/모호함 필드의 존재 여부를 검사합니다. 자유 서술 전체의 의미 정확도를 검사하지 않으며, 카테고리는 선택값으로 채점에서 제외합니다. 브랜드의 조건 필드 중복도 강제하지 않습니다. 별도로 라벨·사유·참조를 채점합니다.</p>
<p>중복 검사는 식별자를 제외한 비교 입력과 개발/프롬프트 예제를 대조합니다. 유사도 후보는 audit.json에서 확인할 수 있으며 의미 중복 여부는 사람이 검토해야 합니다. 입력에 포함된 공격 문구는 검토용 데이터입니다.</p></header>''' + \
        '<nav><label>라벨<select id="label"><option value="">전체</option>' + options(labels) + \
        '</select></label><label>유형<select id="family"><option value="">전체</option>' + options(families) + \
        '</select></label><label>검색<input id="query" type="search" placeholder="사례 ID·상품·조건"></label><span id="count"></span></nav>' + \
        ''.join(cards) + '''<script>
const cards=[...document.querySelectorAll('details')];const label=document.querySelector('#label'),family=document.querySelector('#family'),query=document.querySelector('#query');
function filter(){let n=0;for(const card of cards){card.hidden=!!((label.value&&card.dataset.label!==label.value)||(family.value&&card.dataset.family!==family.value)||(query.value&&!card.textContent.toLowerCase().includes(query.value.toLowerCase())));if(!card.hidden)n++}document.querySelector('#count').textContent=`표시 ${n}건`}
for(const el of [label,family,query])el.addEventListener('input',filter);filter();
</script></html>'''


def export_suites(output):
    holdout, stability, legacy = load_holdout_dataset(), load_stability_dataset(), load_dataset()
    holdout_audit = audit_suite(holdout, (legacy, stability))
    stability_audit = audit_suite(stability, (legacy, holdout))
    for audit in (holdout_audit, stability_audit):
        if audit["exact_comparison_duplicates"] or audit["development_or_prompt_exact_overlaps"]:
            raise ValueError("suite has duplicate or development-overlapping comparison inputs")
    repeated = make_plan(stability, experiment_id="task243-stability-v1", dataset_role="STABILITY",
                         mode="COMPARISON_REPEAT", repetitions=5, max_http_requests=100)
    candidate = make_plan(holdout, experiment_id="task243-holdout-v1", dataset_role="HOLDOUT", max_http_requests=600)
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    for name, value in (("holdout-dataset.json", holdout.model_dump(mode="json")),
                        ("stability-dataset.json", stability.model_dump(mode="json")),
                        ("holdout-audit.json", holdout_audit), ("stability-audit.json", stability_audit),
                        ("stability-plan.json", repeated.model_dump(mode="json")),
                        ("holdout-draft-plan.json", candidate.model_dump(mode="json"))):
        (output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    manifest = {"source": "synthetic-agent-authored-counterfactual-facts", "human_review_status": "PENDING",
                "factory_sha256": digest(Path(__file__).with_name("suite_cases.py").read_text(encoding="utf-8")),
                "suites": [{"version": data.version, "sha256": data.sha256,
                            "cases": [{"case_id": c.case_id, "group_id": group_id(c), "case_sha256": digest(c.model_dump(mode="json")),
                                       "expected_label": c.expected.comparison, "categories": c.categories, "rationale": c.rubric}
                                      for c in data.cases]} for data in (holdout, stability)]}
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for name, data, audit in (("holdout", holdout, holdout_audit), ("stability", stability, stability_audit)):
        (output / f"{name}-review.html").write_text(review_html(data, audit), encoding="utf-8")
    return {"holdout_sha256": holdout.sha256, "stability_sha256": stability.sha256,
            "stability_plan_sha256": repeated.sha256, "holdout_review_status": "PENDING"}


def main(argv=None):
    parser = argparse.ArgumentParser(description="Export synthetic suite candidates for human review; no network")
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        result = export_suites(args.output_dir)
    except (ValueError, OSError):
        parser.exit(2, "suite export failed; check inputs and use a new output directory\n")
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
