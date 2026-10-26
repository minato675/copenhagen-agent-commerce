package com.copenhagen.merchantmock.catalog;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 기대값은 파이썬 벤치({@code docs/related-bench.py} 의 clean_title·norm_title·title_tokens·cat_tokens)에서 뽑았다. 사람이 적지 않았다.
 * 정규화가 이상해 보이는 곳(V4 Pro → "vro", 택1 → "택")도 벤치와 같아야 해서 그대로 둔다.
 */
class RelatedTitlesTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            [KS365] 3겹 천연펄프 더 오래쓰는 화장지 (40m X 18롤) | KS365 3겹 천연펄프 더 오래쓰는 화장지 | ks겹천연펄프더오래쓰는화장지
            [브릭글로] 끈질긴 니트릴 고무장갑 5color 2세트&3세트 18종 (택1) | 브릭글로 끈질긴 니트릴 고무장갑 5color 2세트 3세트 18종 | 브릭글로끈질긴니트릴고무장갑color
            [리더스] 아임피토 마스크 10매 6종 (NEW 옵션 2종 추가, 택1) | 리더스 아임피토 마스크 10매 6종 | 리더스아임피토마스크
            스냅식 커터칼(9 mm/리필5개입)1000 | 스냅식 커터칼 1000 | 스냅식커터칼
            [그래놀라 하우스] 그래놀라 300g 6종, 택1 | 그래놀라 하우스 그래놀라 300g 6종 | 그래놀라하우스그래놀라택
            [서울우유] 나 100% 우유 1000mL | 서울우유 나 100% 우유 1000mL | 서울우유나우유
            Razer Viper V4 Pro (정품) | Razer Viper V4 Pro | razervipervro
            [메디안] 멀티케어 치약 120G*3 | 메디안 멀티케어 치약 120G 3 | 메디안멀티케어치약
            [동원샘물] 무라벨 생수 (500mL X 20개) | 동원샘물 무라벨 생수 | 동원샘물무라벨생수
            """)
    @DisplayName("검색어 정제와 중복 판정 정규화가 파이썬 벤치와 같다")
    void cleanAndNorm_poolTitles_matchPythonBench(String title, String clean, String norm) {
        // when & then
        assertEquals(clean, RelatedTitles.cleanTitle(title));
        assertEquals(norm, RelatedTitles.normTitle(title));
    }

    @Test
    @DisplayName("TF-IDF 낱말은 정제한 제목의 소문자 연속이고 숫자·단위를 남긴다")
    void titleTokens_cleanedTitle_keepsSpecTokens() {
        // when & then
        assertEquals(List.of("ks365", "3겹", "천연펄프", "더", "오래쓰는", "화장지"),
                RelatedTitles.titleTokens(RelatedTitles.cleanTitle("[KS365] 3겹 천연펄프 더 오래쓰는 화장지 (40m X 18롤)")));
        assertEquals(List.of("서울우유", "나", "100", "우유", "1000ml"),
                RelatedTitles.titleTokens(RelatedTitles.cleanTitle("[서울우유] 나 100% 우유 1000mL")));
    }

    @Test
    @DisplayName("카테고리는 단계와 가운뎃점·빗금으로 가른 조각에 c: 를 붙이고 겹쳐도 그대로 둔다")
    void categoryTokens_path_splitsLevelsAndPieces() {
        // when & then
        assertEquals(List.of("c:과일", "c:견과", "c:쌀", "c:친환경"), RelatedTitles.categoryTokens("과일·견과·쌀 > 친환경"));
        assertEquals(List.of("c:패션", "c:잡화", "c:잡화", "c:양말"), RelatedTitles.categoryTokens("패션/잡화 > 잡화 > 양말"));
        assertEquals(List.of("c:뷰티", "c:생활", "c:생활", "c:헤어케어", "c:생활", "c:샴푸"),
                RelatedTitles.categoryTokens("뷰티 생활 > 생활 헤어케어 > 생활 샴푸"));
        assertEquals(List.of(), RelatedTitles.categoryTokens(null));
    }

    @Test
    @DisplayName("브랜드 낱말은 대괄호 라벨 안의 낱말이다")
    void brandTokens_bracketLabel_returnsInnerWords() {
        // when & then
        assertEquals(Set.of("그래놀라", "하우스"), RelatedTitles.brandTokens("[그래놀라 하우스] 그래놀라 300g 6종, 택1"));
        assertEquals(Set.of(), RelatedTitles.brandTokens("Razer Viper V4 Pro (정품)"));
    }
}
