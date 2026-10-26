package com.copenhagen.merchantmock.catalog;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelatedEngineTest {

    private static final List<String> IDS = List.of("p0", "p1", "p2", "p3", "p4", "p5");
    private static final List<String> TITLES = List.of(
            "[서울우유] 나 100% 우유 1000mL",
            "[서울우유] 나 100% 우유 1.8L",
            "[매일] 소화가 잘되는 우유 930mL",
            "[남양] 맛있는 우유 GT 900mL",
            "[서울우유] 더 진한 플레인 요거트 1.8L",
            "[하림] 냉장 닭가슴살 4종");
    private static final List<String> CATEGORIES = List.of(
            "유제품 > 우유 > 흰우유", "유제품 > 우유 > 흰우유", "유제품 > 우유 > 흰우유", "유제품 > 우유 > 흰우유",
            "유제품 > 요거트", "닭/오리고기 > 닭가슴살");
    private static final Function<String, Optional<String>> NO_OTHERS = id -> Optional.empty();

    private static Function<String, List<String>> searchReturning(List<String> ids) {
        return query -> ids;
    }

    @Test
    @DisplayName("TF-IDF 가 없으면 search 로 내려가 검색 결과를 씨앗만 빼고 그대로 쓴다")
    void ranked_withoutIndex_usesSearchOnly() {
        // given
        RelatedEngine engine = new RelatedEngine(IDS, TITLES, null, null, RelatedProperties.Mode.RRF3);

        // when
        List<String> out = engine.ranked("p0", TITLES.get(0), 4, searchReturning(List.of("p0", "p2", "p3", "p5")),
                NO_OTHERS);

        // then
        assertEquals(RelatedProperties.Mode.SEARCH, engine.mode());
        assertEquals(List.of("p2", "p3", "p5"), out);
    }

    @Test
    @DisplayName("같은 상품의 다른 용량은 중복으로 보고 건너뛴다")
    void ranked_sizeVariant_isDeduplicated() {
        // given — p1 은 p0 과 용량만 다르다(정규화 제목이 같다)
        RelatedEngine engine = new RelatedEngine(IDS, TITLES, null, null, RelatedProperties.Mode.SEARCH);

        // when
        List<String> out = engine.ranked("p0", TITLES.get(0), 4, searchReturning(List.of("p1", "p2", "p3")),
                NO_OTHERS);

        // then
        assertEquals(List.of("p2", "p3"), out);
    }

    @Test
    @DisplayName("rrf2 는 검색과 TF-IDF 를 역순위 합으로 섞고, 둘 다 높게 본 상품이 위로 온다")
    void ranked_rrf2_fusesSearchAndTfidf() {
        // given
        RelatedIndex index = RelatedIndex.build(TITLES, CATEGORIES);
        RelatedEngine engine = new RelatedEngine(IDS, TITLES, index, null, RelatedProperties.Mode.RRF3);

        // when — 검색은 닭가슴살을 맨 위에 둔다. TF-IDF 는 우유를 위에 둔다
        List<String> out = engine.ranked("p0", TITLES.get(0), 3, searchReturning(List.of("p5", "p2", "p3")),
                NO_OTHERS);

        // then
        assertEquals(RelatedProperties.Mode.RRF2, engine.mode());
        assertEquals("p2", out.get(0));
        assertTrue(out.contains("p3"));
    }

    @Test
    @DisplayName("씨앗이 풀에 없으면 그 씨앗은 검색만 쓴다")
    void ranked_seedOutsidePool_usesSearchOnly() {
        // given
        RelatedIndex index = RelatedIndex.build(TITLES, CATEGORIES);
        RelatedEngine engine = new RelatedEngine(IDS, TITLES, index, null, RelatedProperties.Mode.RRF2);

        // when
        List<String> out = engine.ranked("fixture-1", "러닝화 260", 4, searchReturning(List.of("p5", "p4")),
                NO_OTHERS);

        // then
        assertEquals(List.of("p5", "p4"), out);
    }

    @Test
    @DisplayName("여러 씨앗은 번갈아 꺼내고, 씨앗·이미 꺼낸 것·팔 수 없는 것은 건너뛴다")
    void merge_multipleSeeds_alternatesAndSkips() {
        // given
        RelatedEngine engine = new RelatedEngine(IDS, TITLES, null, null, RelatedProperties.Mode.SEARCH);
        List<List<String>> lists = List.of(List.of("p2", "p3", "p4"), List.of("p0", "p3", "p1", "p4"));
        Map<String, Boolean> sellable = Map.of("p2", true, "p3", true, "p4", false, "p1", true);

        // when
        List<String> out = engine.merge(lists, List.of("p0", "p5"), List.of(TITLES.get(0), TITLES.get(5)), 4,
                id -> sellable.getOrDefault(id, true), NO_OTHERS);

        // then — p0 은 씨앗, p1 은 씨앗 p0 과 정규화 제목이 같다, p4 는 팔 수 없다
        assertEquals(List.of("p2", "p3"), out);
    }

    @Test
    @DisplayName("씨앗 하나면 목록의 앞 limit 개다")
    void merge_singleSeed_takesPrefix() {
        // given
        RelatedEngine engine = new RelatedEngine(IDS, TITLES, null, null, RelatedProperties.Mode.SEARCH);

        // when
        List<String> out = engine.merge(List.of(List.of("p2", "p3", "p5")), List.of("p0"), List.of(TITLES.get(0)), 2,
                id -> true, NO_OTHERS);

        // then
        assertEquals(List.of("p2", "p3"), out);
    }
}
