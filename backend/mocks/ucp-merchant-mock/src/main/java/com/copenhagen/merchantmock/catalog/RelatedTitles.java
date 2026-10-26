package com.copenhagen.merchantmock.catalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 연관 상품이 쓰는 제목 가공 넷(DESIGN.md 6.8.1절, ADR 0014). 파이썬 벤치 {@code docs/related-bench.py} 와 정규식이 같다.
 * 한 글자라도 다르면 대조 테스트(RelatedParityTest)가 어긋나므로 고칠 때는 벤치도 같이 고친다.
 *
 * <p>파이썬 {@code re} 는 str 패턴에서 {@code \d}·{@code \s} 가 유니코드다. 자바는 기본이 ASCII 라 UNICODE_CHARACTER_CLASS 를 켠다.
 */
final class RelatedTitles {

    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS;
    private static final String UNIT =
            "(ml|l|g|kg|mm|cm|m|mah|w|개입|개|입|매입|매|팩|종|롤|켤레|장|구|세트|p|x|호|인용|인분|단)?";

    private static final Pattern PAREN = Pattern.compile("\\([^)]*\\)", FLAGS);
    private static final Pattern NUM = Pattern.compile("\\d+(?:\\.\\d+)?\\s*" + UNIT, FLAGS | Pattern.CASE_INSENSITIVE);
    private static final Pattern NONWORD = Pattern.compile("[^0-9a-z가-힣]+", FLAGS);
    private static final Pattern TOKEN = Pattern.compile("[0-9a-z가-힣]+", FLAGS);
    private static final Pattern TAK = Pattern.compile("택\\s*\\d+", FLAGS);
    private static final Pattern BRAND = Pattern.compile("\\[([^\\]]*)\\]", FLAGS);
    private static final Pattern PUNCT = Pattern.compile("[,_/&+*~!]+", FLAGS);
    private static final Pattern SPACES = Pattern.compile("\\s+", FLAGS);
    private static final Pattern CATEGORY_LEVEL = Pattern.compile("\\s*>\\s*", FLAGS);
    private static final Pattern CATEGORY_PIECE = Pattern.compile("[·/,&\\s]+", FLAGS);

    private RelatedTitles() {
    }

    /**
     * R1c 검색어. 괄호 안(택1·옵션 안내·용량 묶음)과 택N 을 걷고 대괄호는 기호만 뺀다. 숫자·단위는 남긴다 — 지웠더니 "KS365" → "KS"
     * 로 낱말이 부서지고 "치약 100g" 의 규격 신호를 잃었다(장부 3.4절).
     */
    static String cleanTitle(String title) {
        String t = PAREN.matcher(title).replaceAll(" ");
        t = TAK.matcher(t).replaceAll(" ");
        t = t.replace("[", " ").replace("]", " ");
        t = PUNCT.matcher(t).replaceAll(" ");
        return SPACES.matcher(t).replaceAll(" ").strip();
    }

    /**
     * 중복 판정용 정규화 제목. 괄호 안·숫자·단위·기호를 지운다. 대괄호 브랜드 라벨은 남긴다 — [동원샘물]·[제주 삼다수] 무라벨 생수는
     * 다른 상품이다(장부 3.1절 자 정정).
     */
    static String normTitle(String title) {
        String t = PAREN.matcher(title.toLowerCase(Locale.ROOT)).replaceAll(" ");
        t = NUM.matcher(t).replaceAll(" ");
        return NONWORD.matcher(t).replaceAll("");
    }

    /** 소문자 낱말(한글·영숫자 연속). */
    static List<String> titleTokens(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** 카테고리 경로 조각. "과일·견과·쌀 > 친환경" → c:과일, c:견과, c:쌀, c:친환경. 제목 낱말과 섞이지 않게 접두어를 붙인다. */
    static List<String> categoryTokens(String category) {
        List<String> out = new ArrayList<>();
        if (category == null) {
            return out;
        }
        for (String level : CATEGORY_LEVEL.split(category, -1)) {
            for (String piece : CATEGORY_PIECE.split(level, -1)) {
                if (!piece.isEmpty()) {
                    out.add("c:" + piece.toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    /** 대괄호 라벨 안의 낱말. TF-IDF 에서 가중 0.3 을 받는다(R2b). */
    static Set<String> brandTokens(String title) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = BRAND.matcher(title);
        while (m.find()) {
            out.addAll(titleTokens(m.group(1)));
        }
        return out;
    }
}
