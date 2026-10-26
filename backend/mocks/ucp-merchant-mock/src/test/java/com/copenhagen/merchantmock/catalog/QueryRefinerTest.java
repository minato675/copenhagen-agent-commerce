package com.copenhagen.merchantmock.catalog;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 검색어 정제 어절 규칙(ADR 0013 규칙 1~2). 파이썬 벤치 {@code docs/search-bench3.py} 의 nori_terms 와 같은 답을 내야 한다.
 * 384 검색어 전체 대조는 {@link SearchParityTest}.
 */
class QueryRefinerTest {

    private static QueryRefiner refiner;

    /** 풀에 있는 어절 원문. 규칙 2 의 "원문 우선" 을 흉내 낸다. */
    private static final Predicate<String> IN_POOL = Set.of("파스타면", "물놀이", "수납함", "우유", "운동화", "러닝화", "김치")::contains;
    private static final Predicate<String> NOTHING = surface -> false;

    @BeforeAll
    static void loadDictionary() {
        refiner = new QueryRefiner();
    }

    private static List<String> tokens(String query, Predicate<String> inPool) {
        return refiner.refine(query, inPool).stream().map(QueryRefiner.Token::token).toList();
    }

    @Test
    @DisplayName("조사와 서술어를 걷어낸다 — 우유를 찾아줘 → 우유, 운동화가 필요해 → 운동화")
    void refine_stripsParticlesAndDropsPredicates() {
        assertEquals(List.of("우유"), tokens("우유를 찾아줘", IN_POOL));
        assertEquals(List.of("운동화"), tokens("운동화가 필요해", IN_POOL));
        assertEquals(List.of("계란"), tokens("신선한 계란을 사고 싶어", NOTHING));
    }

    @Test
    @DisplayName("형용사 어절을 버린다 — 실제 호출자가 남기는 수식어(가벼운·신선한·맛있는·부드러운·큰)")
    void refine_dropsAdjectives() {
        assertEquals(List.of("러닝화"), tokens("가벼운 러닝화", IN_POOL));
        assertEquals(List.of("두부"), tokens("부드러운 두부", NOTHING));
        assertEquals(List.of("텀블러"), tokens("텀블러 큰 거", NOTHING));
        assertEquals(List.of("립밤", "향"), tokens("립밤 촉촉한 거 향 없는 거", NOTHING));
    }

    @Test
    @DisplayName("명사+하다 활용은 서술어라 버린다 — 추천해줘·주문할래. 명사 하나짜리 추천은 남는다(수식어 목록이 깎는다)")
    void refine_dropsNounPlusHada() {
        assertEquals(List.of("러닝화"), tokens("러닝화 추천해줘", IN_POOL));
        assertEquals(List.of("치즈"), tokens("치즈 주문할래", NOTHING));
        assertEquals(List.of("강아지", "사료", "추천"), tokens("강아지 사료 추천", NOTHING));
    }

    @Test
    @DisplayName("어절 원문은 쪼개지 않는다 — 러닝화·에어프라이어·요쿠르트·10만원")
    void refine_keepsWholeWordsThatNoriWouldSplit() {
        assertEquals(List.of("에어프라이어"), tokens("에어프라이어 추천해줘", NOTHING));
        assertEquals(List.of("요쿠르트"), tokens("요쿠르트", NOTHING));
        assertEquals(List.of("10만원", "이하", "러닝화"), tokens("10만원 이하 러닝화", IN_POOL));
    }

    @Test
    @DisplayName("원문 우선 — 풀에 있는 어절은 조사를 걷어내지 않는다(물놀이·파스타면), 없으면 걷어낸다")
    void refine_prefersOriginalWhenItExistsInPool() {
        // 혼자면 물놀이/NNG 인데 문장 안에서는 물놀+이(조사)로 읽는다 — nori 는 문맥에 따라 다르게 자른다
        assertEquals(List.of("여름", "바다", "물놀이", "튜브"), tokens("여름 바다 물놀이 튜브", IN_POOL));
        assertEquals(List.of("여름", "바다", "물놀", "튜브"), tokens("여름 바다 물놀이 튜브", NOTHING));
        assertEquals(List.of("파스타면"), tokens("파스타면", IN_POOL));
        assertEquals(List.of("파스타"), tokens("파스타면", NOTHING), "nori 는 파스타+이면(이다)로 읽는다");
    }

    @Test
    @DisplayName("명사형 어미로 끝나면 명사다 — 수납함(수납+하+ㅁ)을 버리지 않는다")
    void refine_keepsNominalizedWords() {
        assertEquals(List.of("사무실", "책상", "정리", "수납함"), tokens("사무실 책상 정리 수납함", IN_POOL));
    }

    @Test
    @DisplayName("내용 품사가 없는 어절은 버린다 — 거·것·하나·어디, 접두사 신")
    void refine_dropsFunctionWords() {
        assertEquals(List.of("김치"), tokens("신 김치", IN_POOL));
        assertEquals(List.of("이불"), tokens("이불은 어디 있어", NOTHING));
        assertEquals(List.of("프라이팬"), tokens("프라이팬 하나 사줘", NOTHING));
    }

    @Test
    @DisplayName("조각 — 복합어의 내용 품사 조각을 함께 준다. 백오프가 쓴다")
    void refine_exposesPiecesForBackoff() {
        List<QueryRefiner.Token> refined = refiner.refine("무선이어폰", NOTHING);

        assertEquals(1, refined.size());
        assertEquals("무선이어폰", refined.get(0).token());
        assertEquals(List.of("무선", "이어폰"), refined.get(0).pieces());
    }

    @Test
    @DisplayName("전부 걸러지면 빈 목록 — 호출자가 공백 분리 원문으로 돌아간다(안전망)")
    void refine_returnsEmptyWhenEverythingIsDropped() {
        assertTrue(tokens("찾아줘", NOTHING).isEmpty());
        assertTrue(tokens("좀 보여줘", NOTHING).isEmpty());
    }

    @Test
    @DisplayName("소문자로 눕히고 원문 어절(surface)을 함께 준다 — 수식어 판정은 둘 다로 한다")
    void refine_lowercasesAndKeepsSurface() {
        List<QueryRefiner.Token> refined = refiner.refine("USB-C 케이블", NOTHING);

        assertEquals("usb-c", refined.get(0).token());
        assertEquals("usb-c", refined.get(0).surface());
        assertEquals("케이블", refined.get(1).token());
    }
}
