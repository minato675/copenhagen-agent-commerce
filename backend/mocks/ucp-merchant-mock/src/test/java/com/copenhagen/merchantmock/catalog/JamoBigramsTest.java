package com.copenhagen.merchantmock.catalog;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 퍼지 백오프의 자모 바이그램(ADR 0013 규칙 4b). 파이썬 벤치 {@code docs/search-bench3.py} 의 jamo·bigrams·fuzzy 와 같아야 한다. */
class JamoBigramsTest {

    @Test
    @DisplayName("초성·중성·종성으로 펼친다. 종성이 없으면 두 글자, 한글이 아니면 그대로")
    void jamo_decomposesHangul() {
        assertEquals("ㅎㅏㄴ", JamoBigrams.jamo("한"));
        assertEquals("ㅊㅛㅋㅗㄹㄹㅣㅅ", JamoBigrams.jamo("쵸콜릿"));
        assertEquals("a4", JamoBigrams.jamo("a4"));
    }

    @Test
    @DisplayName("바이그램 집합. 한 글자 자모면 그 글자 하나")
    void bigrams_ofShortText() {
        assertEquals(Set.of("ㅋㅔ", "ㅔㅊ", "ㅊㅏ", "ㅏㅂ"), JamoBigrams.bigrams("케찹"));
        assertEquals(Set.of("a"), JamoBigrams.bigrams("a"));
    }

    @Test
    @DisplayName("표기 변이를 찾는다 — 쵸콜릿 ≈ 초콜릿, 도너츠 ≈ 도넛, 쏘세지 ≈ 소세지")
    void similar_findsSpellingVariants() {
        JamoBigrams index = JamoBigrams.of(List.of("초콜릿", "도넛", "소세지", "소시지", "우유", "커피"));

        assertEquals(List.of("초콜릿"), index.similar("쵸콜릿"));
        assertEquals(List.of("도넛"), index.similar("도너츠"));
        // 소시지는 자카드 0.25 라 못 잡고 소세지(0.67)만 잡는다 — 실측과 같다
        assertEquals(List.of("소세지"), index.similar("쏘세지"));
    }

    @Test
    @DisplayName("음절 길이 차가 1 을 넘는 낱말은 뺀다 — 린스가 프린세스에 먹히지 않는다")
    void similar_rejectsLengthMismatch() {
        JamoBigrams index = JamoBigrams.of(List.of("프린세스", "린스"));

        assertTrue(index.similar("린스").isEmpty(), "자기 자신은 빼고 프린세스는 길이 차 2");
    }

    @Test
    @DisplayName("한 글자 토큰과 한 글자 사전 낱말은 아무 것과도 비슷하지 않다")
    void similar_ignoresSingleCharacters() {
        JamoBigrams index = JamoBigrams.of(List.of("비", "비누", "비타민"));

        assertTrue(index.similar("비").isEmpty());
        assertEquals(2, index.size(), "한 글자 '비' 는 사전에 들어가지 않는다");
    }

    @Test
    @DisplayName("문턱 이상인 낱말을 자카드 높은 순으로 최대 셋")
    void similar_returnsTopThreeByJaccard() {
        JamoBigrams index = JamoBigrams.of(List.of("1인용", "2인용", "3인용", "4인용", "5인용"));

        List<String> near = index.similar("2인용");
        assertEquals(3, near.size());
        assertTrue(near.stream().allMatch(word -> word.endsWith("인용") && !word.equals("2인용")));
    }

    @Test
    @DisplayName("4차 — 음절 길이가 같은 낱말을 먼저 고른다(케이불 → 케이블)")
    void similar_prefersSameLength() {
        // given: 이불의 바이그램은 케이불 안에 통째로 들어 있어 자카드는 이불이 높다. 3차는 이불을 먼저 골랐다
        JamoBigrams index = JamoBigrams.of(List.of("이불", "케이블"));

        // when & then
        assertEquals(List.of("케이블", "이불"), index.similar("케이불"));
    }
}
