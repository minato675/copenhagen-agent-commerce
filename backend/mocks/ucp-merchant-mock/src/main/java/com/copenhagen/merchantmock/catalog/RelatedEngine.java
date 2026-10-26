package com.copenhagen.merchantmock.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 연관 상품 순위(DESIGN.md 6.8.1절, ADR 0014). 풀 색인 위에서만 돌고 몰·재고를 모른다 — 그것은 {@link RelatedService} 가 본다.
 * 그래서 파이썬 벤치와 그대로 대조된다(RelatedParityTest).
 *
 * <pre>
 * 씨앗 하나   R1c = 정제한 제목으로 S19 검색 상위 100 · R2b = TF-IDF 상위 100 · e5nb = 이웃 파일 상위 20
 *             모드에 따라 R1c 만(search) 또는 R1c + R2b(rrf2) 또는 셋(rrf3)을 역순위 합 1/(60 + 순위)로 섞어 상위 60
 *             → 중복 제거(씨앗·이미 고른 것과 정규화 제목이 같으면 건너뜀)
 * 여러 씨앗   씨앗마다 위 목록을 만들고 씨앗 순서대로 하나씩 번갈아 꺼낸다. 씨앗 자신·이미 꺼낸 것·정규화 제목이 겹치는 것은 건너뛴다
 * </pre>
 *
 * <p>역순위 합의 동점은 풀 순번 오름차순이다(벤치와 같다). 풀에 없는 상품(V3 fixture)은 순번이 없어 맨 뒤, 그 안에서는 id 순이다.
 * 씨앗이 풀에 없으면 TF-IDF·이웃이 없으니 그 씨앗은 검색만 쓴다.
 */
final class RelatedEngine {

    static final int RRF_K = 60;
    static final int PART_DEPTH = 100;
    static final int NEIGHBOR_DEPTH = 20;
    static final int RANK_DEPTH = 60;

    private final List<String> ids;
    private final List<String> titles;
    private final Map<String, Integer> poolIndex;
    private final String[] norms;
    private final RelatedIndex index;
    private final RelatedNeighbors neighbors;
    private final RelatedProperties.Mode mode;

    /**
     * @param ids       풀 순서의 externalId. 비어 있으면 풀이 없는 것이고 모드는 search 로 내려간다
     * @param titles    같은 순서의 제목
     * @param index     TF-IDF. null 이면 rrf2 이상을 못 쓴다
     * @param neighbors e5nb 이웃. null 이면 rrf3 을 못 쓴다
     * @param requested 설정의 모드. 실제 모드는 가진 신호에 맞춰 내려간다({@link #mode()})
     */
    RelatedEngine(List<String> ids, List<String> titles, RelatedIndex index, RelatedNeighbors neighbors,
                  RelatedProperties.Mode requested) {
        this.ids = List.copyOf(ids);
        this.titles = List.copyOf(titles);
        Map<String, Integer> byId = new HashMap<>(ids.size() * 2);
        this.norms = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            byId.putIfAbsent(ids.get(i), i);
            norms[i] = RelatedTitles.normTitle(titles.get(i));
        }
        this.poolIndex = Map.copyOf(byId);
        this.index = index;
        this.neighbors = neighbors;
        RelatedProperties.Mode available = index == null ? RelatedProperties.Mode.SEARCH
                : neighbors == null ? RelatedProperties.Mode.RRF2 : RelatedProperties.Mode.RRF3;
        this.mode = requested.atMost(available);
    }

    /** 실제로 도는 모드. 설정보다 낮으면 준비 중 무언가를 못 만든 것이다(기동 로그에 이유가 있다). */
    RelatedProperties.Mode mode() {
        return mode;
    }

    int poolSize() {
        return ids.size();
    }

    Optional<String> poolTitle(String externalId) {
        Integer i = poolIndex.get(externalId);
        return i == null ? Optional.empty() : Optional.of(titles.get(i));
    }

    /**
     * 씨앗 하나의 순위(중복 제거 뒤, 최대 {@code depth}).
     *
     * @param search      정제한 검색어 → S19 검색 결과 externalId 순서대로(최대 100)
     * @param otherTitles 풀에 없는 상품의 제목(중복 판정용). 몰 상품에서 찾는다
     */
    List<String> ranked(String seedId, String seedTitle, int depth, Function<String, List<String>> search,
                        Function<String, Optional<String>> otherTitles) {
        Integer seed = poolIndex.get(seedId);
        List<String> byQuery = new ArrayList<>();
        for (String id : search.apply(RelatedTitles.cleanTitle(seedTitle))) {
            if (!id.equals(seedId)) {
                byQuery.add(id);
            }
        }
        RelatedProperties.Mode effective = seed == null ? RelatedProperties.Mode.SEARCH : mode;
        List<String> candidates;
        if (effective == RelatedProperties.Mode.SEARCH) {
            candidates = byQuery.subList(0, Math.min(RANK_DEPTH, byQuery.size()));
        } else {
            List<List<String>> parts = new ArrayList<>();
            parts.add(byQuery.subList(0, Math.min(PART_DEPTH, byQuery.size())));
            parts.add(idsOf(index.top(seed, PART_DEPTH)));
            if (effective == RelatedProperties.Mode.RRF3) {
                parts.add(idsOf(neighbors.of(seed, NEIGHBOR_DEPTH)));
            }
            candidates = fuse(parts);
        }
        String seedNorm = seed == null ? RelatedTitles.normTitle(seedTitle) : norms[seed];
        Set<String> seen = new HashSet<>();
        seen.add(seedNorm);
        List<String> out = new ArrayList<>();
        for (String id : candidates) {
            String norm = normOf(id, otherTitles);
            if (norm == null || !seen.add(norm)) {
                continue;
            }
            out.add(id);
            if (out.size() == depth) {
                break;
            }
        }
        return out;
    }

    /** 역순위 합. 더하는 순서(목록 순서 → 순위 순서)가 벤치와 같아야 같은 double 이 나온다. */
    private List<String> fuse(List<List<String>> parts) {
        LinkedHashMap<String, Double> score = new LinkedHashMap<>();
        for (List<String> part : parts) {
            for (int r = 0; r < part.size(); r++) {
                score.merge(part.get(r), 1.0 / (RRF_K + r + 1), Double::sum);
            }
        }
        List<String> sorted = new ArrayList<>(score.keySet());
        sorted.sort(Comparator.<String>comparingDouble(score::get).reversed()
                .thenComparingInt(id -> poolIndex.getOrDefault(id, Integer.MAX_VALUE))
                .thenComparing(Comparator.naturalOrder()));
        return sorted.subList(0, Math.min(RANK_DEPTH, sorted.size()));
    }

    /**
     * 씨앗 여럿을 번갈아 꺼낸다(RR). 씨앗 하나면 그 목록의 앞 {@code limit} 개다.
     *
     * @param lists  씨앗마다 {@link #ranked} 결과. 씨앗 순서 그대로
     * @param accept 몰에 있고 팔 수 있는가. 대조 테스트는 전부 참으로 준다
     */
    List<String> merge(List<List<String>> lists, List<String> seedIds, List<String> seedTitles, int limit,
                       Predicate<String> accept, Function<String, Optional<String>> otherTitles) {
        Set<String> seenIds = new HashSet<>(seedIds);
        Set<String> seenNorms = new HashSet<>();
        for (int s = 0; s < seedIds.size(); s++) {
            Integer seed = poolIndex.get(seedIds.get(s));
            seenNorms.add(seed == null ? RelatedTitles.normTitle(seedTitles.get(s)) : norms[seed]);
        }
        int[] pos = new int[lists.size()];
        List<String> out = new ArrayList<>();
        boolean progressed = true;
        while (out.size() < limit && progressed) {
            progressed = false;
            for (int s = 0; s < lists.size() && out.size() < limit; s++) {
                List<String> list = lists.get(s);
                while (pos[s] < list.size()) {
                    String id = list.get(pos[s]++);
                    progressed = true;
                    String norm = normOf(id, otherTitles);
                    if (seenIds.contains(id) || norm == null || seenNorms.contains(norm) || !accept.test(id)) {
                        continue;
                    }
                    out.add(id);
                    seenIds.add(id);
                    seenNorms.add(norm);
                    break;
                }
            }
        }
        return out;
    }

    private String normOf(String id, Function<String, Optional<String>> otherTitles) {
        Integer i = poolIndex.get(id);
        if (i != null) {
            return norms[i];
        }
        return otherTitles.apply(id).map(RelatedTitles::normTitle).orElse(null);
    }

    private List<String> idsOf(int[] poolIndices) {
        List<String> out = new ArrayList<>(poolIndices.length);
        for (int i : poolIndices) {
            out.add(ids.get(i));
        }
        return out;
    }
}
