package com.copenhagen.merchantmock.catalog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 상품 하나의 검색용 사전 계산(DESIGN.md 6.7절). 스냅샷을 만들 때 한 번 만들고 요청마다 다시 만들지 않는다.
 *
 * <p>풀이 9만 건이면 요청마다 9만 번 소문자 변환과 낱말 분리를 하는 것이 검색 시간의 대부분이다.
 * 제목·브랜드·카테고리의 소문자와 제목 낱말 집합을 여기 둔다.
 *
 * <p>3차(ADR 0013 규칙 5)에서 공백을 뺀 세 필드가 더 붙었다. "무선이어폰" 처럼 붙여 쓴 검색어가 "무선 이어폰" 제목에
 * 맞으려면 공백을 뺀 문자열에도 맞춰 봐야 한다. 낱말 단위 일치(4)는 줄 수 없어 제목 3 이 최고다.
 *
 * @param title           소문자 제목
 * @param brand           소문자 브랜드. 없으면 빈 문자열
 * @param category        소문자 카테고리 경로. 없으면 빈 문자열
 * @param titleWords      제목을 낱말 경계로 쪼갠 집합. 낱말 단위 일치(4단계)를 판정한다
 * @param haystack        제목·브랜드·카테고리를 공백으로 이은 소문자. 세 필드를 따로 보기 전에 한 번의 contains 로 걸러 낸다 —
 *                        대부분의 상품은 어느 낱말에도 안 맞으므로 이 한 번이 검색 시간의 대부분을 정한다
 * @param titleNoSpace    공백을 뺀 제목
 * @param brandNoSpace    공백을 뺀 브랜드
 * @param categoryNoSpace 공백을 뺀 카테고리
 * @param haystackNoSpace 공백을 뺀 세 필드를 개행으로 이은 것. 그냥 이으면 제목 끝 + 브랜드 앞이 붙어 없는 낱말이 생긴다
 * @param categoryParts   카테고리를 ">" 로 칸을 나누고 칸을 "/"·"·"·"," 로 자른 조각(5차 규칙 15). 원문 대소문자 그대로, 앞뒤 공백을
 *                        다듬는다. 조각이 머리 명사로 끝나면 그 종류의 상품이다 — "과일/견과 > 딸기" 는 딸기, "칫솔걸이/꽂이" 는 칫솔이 아니다
 * @param categoryLeaf    마지막 칸의 조각. 부속품 칸(이어폰소품·실내탈취제)을 판정한다(규칙 14)
 */
public record SearchDoc(String title, String brand, String category, Set<String> titleWords, String haystack,
                        String titleNoSpace, String brandNoSpace, String categoryNoSpace, String haystackNoSpace,
                        List<CategoryPart> categoryParts, List<String> categoryLeaf) {

    /**
     * 카테고리 조각 하나.
     *
     * @param text  조각 원문
     * @param mixed 한 칸에 조각이 둘 이상이다("가습기/공기청정기"·"스탠드/무드등"). 칸 이름만으로 종류를 못 정한다
     */
    public record CategoryPart(String text, boolean mixed) {
    }

    /** 낱말 경계. 한글·영문·숫자가 아닌 것은 전부 구분자로 본다. 상품명의 대괄호·슬래시·괄호·가운뎃점을 가른다. */
    static final Pattern WORD_BREAK = Pattern.compile("[^0-9a-z가-힣]+");

    /** 칸 안의 조각 경계. 파이썬 벤치(search-bench5.py cat_parts)와 같아야 한다. */
    static final Pattern PART_BREAK = Pattern.compile("/|·|,");

    public static SearchDoc of(Product product) {
        String title = lower(product.title());
        String brand = lower(product.brand());
        String category = lower(product.category());
        String titleNoSpace = title.replace(" ", "");
        String brandNoSpace = brand.replace(" ", "");
        String categoryNoSpace = category.replace(" ", "");
        return new SearchDoc(title, brand, category, Set.of(words(title)), title + ' ' + brand + ' ' + category,
                titleNoSpace, brandNoSpace, categoryNoSpace,
                titleNoSpace + '\n' + brandNoSpace + '\n' + categoryNoSpace, parts(product.category()),
                leaf(product.category()));
    }

    /**
     * 카테고리 일치(4차 규칙 9 를 5차 규칙 15 가 대신한다). 조각이 묶음의 낱말로 끝나야 하고 — 한국어 합성어는 끝 낱말이 종류다 —
     * 섞인 칸의 조각이면 제목에도 그 낱말이 있어야 한다. "스탠드/무드등" 칸의 무드등이 "스탠드" 로 올라오던 것을 막는다.
     */
    boolean categoryMatch(List<String> head) {
        boolean inTitle = titleContainsAny(head);
        for (CategoryPart part : categoryParts) {
            if (part.mixed() && !inTitle) {
                continue;
            }
            for (String word : head) {
                if (part.text().endsWith(word)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 머리 명사를 품은 조각이 전부 섞인 칸에 있고 제목에는 머리 명사가 없나(5차 규칙 16). 머리 명사가 카테고리로만 맞았을 때
     * 점수에서 머리 명사 가중치를 뺄지 정한다 — "정육·가공육·달걀" 칸의 캠핑 스테이크가 "캠핑 계란" 에서 달걀을 이기던 것.
     */
    boolean weakCategoryHead(List<String> head) {
        boolean any = false;
        for (CategoryPart part : categoryParts) {
            for (String word : head) {
                if (part.text().contains(word)) {
                    if (!part.mixed()) {
                        return false;
                    }
                    any = true;
                    break;
                }
            }
        }
        return any && !titleContainsAny(head);
    }

    /**
     * 부속품인가(5차 규칙 14). 제목 낱말이 부속품 낱말로 끝나면(운동화끈·칫솔 캡) 부속품이다 — 그 낱말이 검색어에 있거나, 제목 낱말이
     * 머리 명사로 끝나거나, 부속품 낱말이 머리 명사 안에 있으면 빼고. 제목에서 못 가르면 카테고리 말단 조각을 본다. 말단 조각이 머리
     * 명사로 끝나면 부속품이 아니고(컬리 "이불·베개·패드" 의 이불), 부속품 낱말로 끝나면 부속품이다(이어폰소품·실내탈취제).
     *
     * @param query      소문자 검색어 원문
     * @param titleWords 제목에서 보는 부속품 낱말
     * @param leafWords  카테고리 말단에서 보는 부속품 낱말
     */
    boolean accessory(List<String> head, String query, List<String> titleWords, List<String> leafWords) {
        for (String word : this.titleWords) {
            for (String accessory : titleWords) {
                if (word.endsWith(accessory) && !query.contains(accessory) && !endsWithAny(word, head)
                        && !anyContains(head, accessory)) {
                    return true;
                }
            }
        }
        for (String part : categoryLeaf) {
            if (endsWithAny(part, head)) {
                return false;
            }
        }
        for (String part : categoryLeaf) {
            for (String accessory : leafWords) {
                if (part.endsWith(accessory) && !query.contains(accessory)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean titleContainsAny(List<String> words) {
        for (String word : words) {
            if (title.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsWithAny(String text, List<String> words) {
        for (String word : words) {
            if (text.endsWith(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyContains(List<String> words, String part) {
        for (String word : words) {
            if (word.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static List<CategoryPart> parts(String category) {
        List<CategoryPart> out = new ArrayList<>();
        for (String level : levels(category)) {
            List<String> sub = split(level);
            for (String text : sub) {
                out.add(new CategoryPart(text, sub.size() > 1));
            }
        }
        return List.copyOf(out);
    }

    private static List<String> leaf(String category) {
        List<String> levels = levels(category);
        return levels.isEmpty() ? List.of() : split(levels.get(levels.size() - 1));
    }

    private static List<String> levels(String category) {
        if (category == null) {
            return List.of();
        }
        return Arrays.stream(category.split(">")).map(String::strip).filter(level -> !level.isEmpty()).toList();
    }

    private static List<String> split(String level) {
        return Arrays.stream(PART_BREAK.split(level)).map(String::strip).filter(text -> !text.isEmpty()).toList();
    }

    /**
     * 동의어 묶음 하나가 이 상품의 어느 자리에서 맞았나. 제목 낱말 4 > 제목 부분 3 > 브랜드 2 > 카테고리 1 > 0.
     *
     * <p>단계를 넷으로 둔 이유는 실측이다. 제목 부분문자열만 보면 "우유" 검색에서 우유팩 키링과 우유니 캔들이
     * 실제 우유와 같은 취급을 받는다. 카테고리로만 맞은 것은 맨 뒤다 — 재현율은 얻되 첫 화면은 내주지 않는다(470).
     */
    byte tier(List<String> group) {
        boolean anywhere = false;
        for (String word : group) {
            if (haystack.contains(word)) {
                anywhere = true;
                break;
            }
        }
        if (!anywhere) {
            return 0;
        }
        for (String word : group) {
            if (titleWords.contains(word)) {
                return 4;
            }
        }
        for (String word : group) {
            if (title.contains(word)) {
                return 3;
            }
        }
        for (String word : group) {
            if (brand.contains(word)) {
                return 2;
            }
        }
        for (String word : group) {
            if (category.contains(word)) {
                return 1;
            }
        }
        return 0;
    }

    /** 공백을 뺀 문자열에서만 맞은 경우의 자리. 제목 3 > 브랜드 2 > 카테고리 1. 낱말 단위(4)는 줄 수 없다. */
    byte tierNoSpace(List<String> group) {
        for (String word : group) {
            if (titleNoSpace.contains(word)) {
                return 3;
            }
        }
        for (String word : group) {
            if (brandNoSpace.contains(word)) {
                return 2;
            }
        }
        for (String word : group) {
            if (categoryNoSpace.contains(word)) {
                return 1;
            }
        }
        return 0;
    }

    /** 3차 매칭 — 그대로 맞으면 그 자리, 아니면 공백 뺀 문자열로 다시(규칙 5). */
    byte tierOrNoSpace(List<String> group) {
        byte tier = tier(group);
        return tier > 0 ? tier : tierNoSpace(group);
    }

    /** 묶음의 낱말 하나라도 이 상품 어딘가에 있나. {@code noSpace} 면 공백 뺀 문자열까지 본다. 백오프의 후보 세기용. */
    boolean containsAny(List<String> group, boolean noSpace) {
        String target = noSpace ? haystackNoSpace : haystack;
        for (String word : group) {
            if (target.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static String[] words(String title) {
        String[] split = WORD_BREAK.split(title);
        // 앞이 구분자로 시작하면 빈 문자열이 첫 원소로 온다. Set.of 는 중복을 거절하므로 걸러 낸다.
        return Arrays.stream(split).filter(word -> !word.isEmpty()).distinct().toArray(String[]::new);
    }

    private static String lower(String field) {
        return field == null ? "" : field.toLowerCase(Locale.ROOT);
    }
}
