package com.copenhagen.merchantmock.catalog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 자모 바이그램으로 비슷한 제목 낱말을 찾는다 — 검색 3차 퍼지 백오프(DESIGN.md 6.7절, ADR 0013 규칙 4b).
 *
 * <p>한글을 초성·중성·종성으로 펼친 뒤 두 글자씩 묶어 집합을 만들고 자카드로 잰다. 음절 단위보다 잘게 보는 이유는
 * "쵸콜릿 ↔ 초콜릿", "쏘세지 ↔ 소세지" 처럼 모음 하나·자음 하나가 다른 표기 변이를 잡기 위해서다. 실측 문턱은 0.5 이고
 * 음절 길이 차가 1 을 넘는 낱말은 뺀다 — 짧은 낱말이 긴 낱말에 먹히는 것(린스 ≈ 프린세스)을 막는다.
 *
 * <p>사전은 스냅샷을 만들 때 한 번 만든다({@link CatalogStore}). 두 글자 이상인 제목 낱말 전부이고 전량 풀에서 7만여 개다.
 * 검색은 후보가 한 화면(4건) 미만인 토큰에만 돌아 요청당 비용이 거의 없다.
 */
public final class JamoBigrams {

    private static final String CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
    private static final String JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
    private static final String JONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ";
    private static final int HANGUL_BASE = 0xAC00;
    private static final int HANGUL_COUNT = 11172;

    public static final double DEFAULT_MIN_JACCARD = 0.5;
    public static final int DEFAULT_MAX_LENGTH_DIFF = 1;
    public static final int DEFAULT_TOP = 3;

    private final Set<String> vocabulary;
    private final Map<String, List<String>> byBigram;

    private JamoBigrams(Set<String> vocabulary, Map<String, List<String>> byBigram) {
        this.vocabulary = vocabulary;
        this.byBigram = byBigram;
    }

    /** 두 글자 이상인 낱말만 사전에 넣는다. 한 글자는 바이그램이 하나라 아무 것과도 비슷해진다. */
    public static JamoBigrams of(Collection<String> words) {
        Set<String> vocabulary = new HashSet<>();
        Map<String, List<String>> byBigram = new HashMap<>();
        for (String word : words) {
            if (word == null || word.length() < 2 || !vocabulary.add(word)) {
                continue;
            }
            for (String bigram : bigrams(word)) {
                byBigram.computeIfAbsent(bigram, key -> new ArrayList<>()).add(word);
            }
        }
        return new JamoBigrams(Set.copyOf(vocabulary), byBigram);
    }

    public static JamoBigrams empty() {
        return new JamoBigrams(Set.of(), Map.of());
    }

    public int size() {
        return vocabulary.size();
    }

    /**
     * {@code token} 과 자모 바이그램 자카드가 {@code minJaccard} 이상이고 음절 길이 차가 {@code maxLengthDiff} 이하인
     * 사전 낱말 {@code top} 개. 같은 낱말은 뺀다. 한 글자 토큰은 빈 목록.
     *
     * <p>순서는 음절 길이 차 오름 → 자카드 내림 → 낱말 사전순 오름(4차 규칙 12). 3차는 자카드만 봐서 "케이불" 이 "이불" 로 갔다 —
     * 이불의 바이그램이 케이불 안에 통째로 들어 있어 자카드가 케이블보다 높다. 오타는 대개 음절 수를 바꾸지 않는다.
     */
    public List<String> similar(String token, double minJaccard, int maxLengthDiff, int top) {
        if (token == null || token.length() < 2) {
            return List.of();
        }
        Set<String> mine = bigrams(token);
        Map<String, Double> seen = new HashMap<>();
        for (String bigram : mine) {
            for (String word : byBigram.getOrDefault(bigram, List.of())) {
                if (seen.containsKey(word) || Math.abs(word.length() - token.length()) > maxLengthDiff) {
                    continue;
                }
                seen.put(word, jaccard(mine, bigrams(word)));
            }
        }
        List<Map.Entry<String, Double>> ranked = new ArrayList<>();
        for (Map.Entry<String, Double> entry : seen.entrySet()) {
            if (entry.getValue() >= minJaccard && !entry.getKey().equals(token)) {
                ranked.add(entry);
            }
        }
        // 파이썬 벤치(search-bench4.py W)와 같은 순서 — sorted((길이 차, -자카드, 낱말))
        int length = token.length();
        ranked.sort(Comparator.<Map.Entry<String, Double>>comparingInt(e -> Math.abs(e.getKey().length() - length))
                .thenComparing(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed())
                .thenComparing(Map.Entry::getKey));
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Double> entry : ranked) {
            if (result.size() == top) {
                break;
            }
            result.add(entry.getKey());
        }
        return result;
    }

    public List<String> similar(String token) {
        return similar(token, DEFAULT_MIN_JACCARD, DEFAULT_MAX_LENGTH_DIFF, DEFAULT_TOP);
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        int both = 0;
        for (String x : a) {
            if (b.contains(x)) {
                both++;
            }
        }
        int union = a.size() + b.size() - both;
        return union == 0 ? 0 : (double) both / union;
    }

    /** 초성·중성·종성으로 펼친다. 한글이 아닌 글자는 그대로. 종성이 없으면 두 글자다. */
    static String jamo(String text) {
        StringBuilder out = new StringBuilder(text.length() * 3);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            int code = ch - HANGUL_BASE;
            if (code >= 0 && code < HANGUL_COUNT) {
                out.append(CHO.charAt(code / 588));
                out.append(JUNG.charAt((code % 588) / 28));
                if (code % 28 != 0) {
                    out.append(JONG.charAt(code % 28));
                }
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    /** 자모 문자열의 두 글자 묶음 집합. 한 글자면 그 글자 하나. */
    static Set<String> bigrams(String text) {
        String j = jamo(text);
        Set<String> set = new HashSet<>();
        if (j.length() < 2) {
            set.add(j);
            return set;
        }
        for (int i = 0; i + 1 < j.length(); i++) {
            set.add(j.substring(i, i + 2));
        }
        return set;
    }
}
