package com.copenhagen.merchantmock.catalog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 연관 상품 R2b — TF-IDF 코사인 역색인(DESIGN.md 6.8.1절, ADR 0014). 풀 파일 순서 그대로 한 벌이고 몰과 무관하다.
 *
 * <pre>
 * 문서 = 정제한 제목(RelatedTitles.cleanTitle)의 낱말 + 카테고리 조각(c: 접두어)
 * 가중치 = (1 + ln tf) × (ln(N / df) + 1) × (대괄호 브랜드 낱말이면 0.3)
 * 점수(씨앗 i, 후보 j) = Σ w_i · w_j / ‖j‖   ‖i‖ 는 모든 후보에 같아 나누지 않는다
 * 순서 = 점수 내림차순, 같으면 풀 순번 오름차순
 * </pre>
 *
 * <p>파이썬 벤치와 같은 double 을 내려고 더하는 순서까지 맞춘다 — 낱말은 문서 안에서 처음 나온 순서, 게시 목록은 문서 순번 순서다.
 * 씨앗 쪽 가중치는 저장하지 않고 요청 때 같은 식으로 다시 계산한다(10만 문서 × 낱말 수만큼 메모리를 아낀다).
 */
final class RelatedIndex {

    static final double BRAND_WEIGHT = 0.3;

    private final int size;
    private final Map<String, Integer> tokenIds;
    private final int[] df;
    private final double[] norms;
    private final int[] postStart;
    private final int[] postDoc;
    private final double[] postWeight;
    private final List<String> titles;
    private final List<String> categories;

    private RelatedIndex(int size, Map<String, Integer> tokenIds, int[] df, double[] norms, int[] postStart,
                         int[] postDoc, double[] postWeight, List<String> titles, List<String> categories) {
        this.size = size;
        this.tokenIds = tokenIds;
        this.df = df;
        this.norms = norms;
        this.postStart = postStart;
        this.postDoc = postDoc;
        this.postWeight = postWeight;
        this.titles = titles;
        this.categories = categories;
    }

    /** 문서 하나의 낱말을 첫 등장 순서로 센 것. */
    private record Counted(List<String> tokens, int[] counts) {
    }

    private static Counted count(String title, String category) {
        LinkedHashMap<String, Integer> tf = new LinkedHashMap<>();
        for (String token : tokensOf(title, category)) {
            tf.merge(token, 1, Integer::sum);
        }
        int[] counts = new int[tf.size()];
        int i = 0;
        for (int c : tf.values()) {
            counts[i++] = c;
        }
        return new Counted(new ArrayList<>(tf.keySet()), counts);
    }

    static RelatedIndex build(List<String> titles, List<String> categories) {
        int n = titles.size();
        Map<String, Integer> ids = new HashMap<>();
        List<Counted> docs = new ArrayList<>(n);
        List<Integer> dfList = new ArrayList<>();
        for (int j = 0; j < n; j++) {
            Counted doc = count(titles.get(j), categories.get(j));
            for (String token : doc.tokens()) {
                Integer id = ids.get(token);
                if (id == null) {
                    id = ids.size();
                    ids.put(token, id);
                    dfList.add(0);
                }
                dfList.set(id, dfList.get(id) + 1);
            }
            docs.add(doc);
        }
        int vocabulary = ids.size();
        int[] df = new int[vocabulary];
        double[] idf = new double[vocabulary];
        for (int t = 0; t < vocabulary; t++) {
            df[t] = dfList.get(t);
            idf[t] = Math.log((double) n / df[t]) + 1.0;
        }

        // 게시 목록 CSR. 낱말마다 문서 순번 순서
        int[] postStart = new int[vocabulary + 1];
        for (int t = 0; t < vocabulary; t++) {
            postStart[t + 1] = postStart[t] + df[t];
        }
        int[] fill = Arrays.copyOf(postStart, vocabulary);
        int[] postDoc = new int[postStart[vocabulary]];
        double[] postWeight = new double[postStart[vocabulary]];
        double[] norms = new double[n];
        for (int j = 0; j < n; j++) {
            Counted doc = docs.get(j);
            Set<String> brand = RelatedTitles.brandTokens(titles.get(j));
            double squares = 0;
            for (int p = 0; p < doc.counts().length; p++) {
                String token = doc.tokens().get(p);
                int t = ids.get(token);
                double w = weight(doc.counts()[p], idf[t], brand.contains(token));
                squares += w * w;
                postDoc[fill[t]] = j;
                postWeight[fill[t]] = w;
                fill[t]++;
            }
            double norm = Math.sqrt(squares);
            norms[j] = norm == 0 ? 1.0 : norm;
            docs.set(j, null);
        }
        return new RelatedIndex(n, Map.copyOf(ids), df, norms, postStart, postDoc, postWeight,
                List.copyOf(titles), List.copyOf(categories));
    }

    private static List<String> tokensOf(String title, String category) {
        List<String> tokens = RelatedTitles.titleTokens(RelatedTitles.cleanTitle(title));
        tokens.addAll(RelatedTitles.categoryTokens(category));
        return tokens;
    }

    private static double weight(int tf, double idf, boolean brand) {
        return (1 + Math.log(tf)) * idf * (brand ? BRAND_WEIGHT : 1.0);
    }

    int size() {
        return size;
    }

    int vocabularySize() {
        return tokenIds.size();
    }

    int postings() {
        return postDoc.length;
    }

    /** 씨앗 문서 {@code seed} 와 가장 가까운 {@code k} 개의 풀 순번. 씨앗 자신은 뺀다. */
    int[] top(int seed, int k) {
        Counted doc = count(titles.get(seed), categories.get(seed));
        Set<String> brand = RelatedTitles.brandTokens(titles.get(seed));
        double[] acc = new double[size];
        boolean[] touched = new boolean[size];
        List<Integer> hits = new ArrayList<>();
        for (int p = 0; p < doc.counts().length; p++) {
            String token = doc.tokens().get(p);
            int t = tokenIds.get(token);
            double idf = Math.log((double) size / df[t]) + 1.0;
            double w = weight(doc.counts()[p], idf, brand.contains(token));
            for (int q = postStart[t]; q < postStart[t + 1]; q++) {
                int j = postDoc[q];
                acc[j] += w * postWeight[q];
                if (!touched[j]) {
                    touched[j] = true;
                    hits.add(j);
                }
            }
        }
        // 상위 k 만 남기는 최소 힙. 순서는 (점수 내림차순, 순번 오름차순)이라 힙의 "가장 약한 것" 은 (점수 작음, 순번 큼)
        PriorityQueue<Integer> heap = new PriorityQueue<>((a, b) -> {
            int c = Double.compare(acc[a] / norms[a], acc[b] / norms[b]);
            return c != 0 ? c : Integer.compare(b, a);
        });
        for (int j : hits) {
            if (j == seed) {
                continue;
            }
            heap.add(j);
            if (heap.size() > k) {
                heap.poll();
            }
        }
        int[] out = new int[heap.size()];
        for (int i = out.length - 1; i >= 0; i--) {
            out[i] = heap.poll();
        }
        return out;
    }
}
