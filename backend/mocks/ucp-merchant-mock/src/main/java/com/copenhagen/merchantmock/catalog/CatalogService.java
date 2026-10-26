package com.copenhagen.merchantmock.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

import com.copenhagen.merchantmock.support.ApiException;
import com.copenhagen.merchantmock.support.MerchantProperties;
import org.springframework.stereotype.Service;

/**
 * Catalog Search·Lookup·Product. 몰 하나 안에서만 찾는다(393).
 *
 * <p>여기서 주는 가격과 재고는 탐색용 현재 정보다. 최종 거래 조건의 원본은 Checkout 이다.
 * Agent 가 이 값을 그대로 믿고 결제를 시도해도 Core 가 Checkout 값으로 다시 확정한다(DESIGN.md 1절).
 *
 * <p>DISCONTINUED 상품은 세 엔드포인트 모두에서 숨긴다. 파는 물건이 아니다.
 */
@Service
public class CatalogService {

    /**
     * 몰 하나가 검색 한 번에 돌려줄 수 있는 최대 건수(470). Core 는 모든 몰의 결과를 합친 뒤 자르므로
     * 몰당 상한이다. MCP 도구의 {@code pageSize} 상한이 30 이라 페이지를 여러 장 넘겨도 걸리지 않는다.
     * 상한이 없으면 빈 검색어 한 번에 카탈로그 전량이 나간다.
     */
    private static final int MAX_RESULTS = 100;

    /** MCP 도구 기본 {@code pageSize}. 첫 화면 넷이 곧 결과다. 백오프는 후보가 이보다 적을 때만 건다(ADR 0013 규칙 4). */
    static final int PAGE = 4;

    /** {@link #count} 가 순차로 보는 앞부분. 흔한 낱말은 여기서 끝난다(전량의 2%). */
    static final int PREFIX_SCAN = 2_000;

    private static final int PET_FLAG = 1;
    private static final int CATEGORY_FLAG = 2;
    private static final int ACCESSORY_FLAG = 4;
    private static final int WEAK_HEAD_FLAG = 8;

    /** 머리 명사 가중치에 더하는 여유. 나머지 토큰이 전부 맞아도 머리 명사 하나가 이기게 한다(규칙 7). */
    private static final double HEAD_MARGIN = 0.5;

    private final CatalogStore store;
    private final MerchantProperties merchants;
    private final Map<String, List<String>> synonyms;
    private final MerchantProperties.Catalog.Search tuning;
    private final QueryRefiner refiner;

    public CatalogService(CatalogStore store, MerchantProperties merchants) {
        this.store = store;
        this.merchants = merchants;
        this.synonyms = indexSynonyms(merchants.catalog().synonyms());
        this.tuning = merchants.catalog().search();
        this.refiner = tuning.nori() ? new QueryRefiner() : null;
    }

    /** 낱말 하나에서 자기 묶음으로 가는 색인. 같은 낱말이 두 묶음에 있으면 먼저 온 묶음을 쓴다. */
    private static Map<String, List<String>> indexSynonyms(List<List<String>> groups) {
        Map<String, List<String>> index = new HashMap<>();
        for (List<String> group : groups) {
            for (String word : group) {
                index.putIfAbsent(word, group);
            }
        }
        return Map.copyOf(index);
    }

    /**
     * 검색어를 정제해 토큰으로 만들고, 하나라도 제목·브랜드·카테고리에 있으면 후보로 삼아 점수로 줄을 세운다
     * (DESIGN.md 6.7절, ADR 0013 — 3차 S19).
     *
     * <pre>
     * 검색어 → 토큰   nori 어절 정제(서술어·조사 제거, 원문 보존) → 동의어 → 후보가 한 화면 미만이면 조각·퍼지 백오프
     * 매칭           제목·브랜드·카테고리 부분문자열 OR. 공백 뺀 문자열에도 맞춰 본다
     * 점수 = Σ 맞은 토큰 가중치
     *   수식어(fluff-words)·속성(attribute-words)·숫자 든 토큰·한 글자 토큰은 fluff-weight(0.2) 이고 머리 명사가 될 수 없다.
     *   전부 그런 토큰이면 깎지 않는다 — 그때는 그것이 검색어다
     *   머리 명사 = 위에 안 걸린 마지막 토큰. 가중치 = max(tail-boost, 나머지 합 + 0.5). 머리만 맞은 상품이 수식어 전부
     *   맞은 상품보다 항상 위다
     * 동점은 펫 아님 먼저 → 카테고리 일치 먼저 → 자리 단계(제목 낱말 4 > 제목 부분 3 > 브랜드 2 > 카테고리 1, 맞은 묶음 중
     * 가장 약한 자리) → 풀 순서
     * </pre>
     *
     * <p>4차(525)는 동점 처리만 바꿨다. 설문 검색어의 절반 넘게가 명사 하나라 후보 전부가 같은 점수이고, 그러면 풀 순서
     * (external_id — 다이소가 먼저)가 순위를 정해 딸기 스푼·계란 틀·펫 치즈 간식이 위로 왔다. 카테고리 조각이 머리 명사로
     * 끝나는 상품("과일/견과 > 딸기")을 먼저 두고, 반려동물 검색이 아니면 펫 상품을 뒤로 보낸다. 화면 상위 10개를 한 건씩
     * 판정한 자로 튜닝셋 55 → 80%, 검증셋 61 → 79%(장부 8절).
     *
     * <p>2차(A10)는 마지막 낱말에 1.5 를 줬다. 실제 호출자(Haiku)가 "우유 대용량"·"가습기 소형" 처럼 속성을 뒤에 붙여 넘기면
     * 속성이 머리가 되어 대용량 세제가 우유보다 위로 왔다 — 실제 query 251개에서 84%. 3차는 93%(장부 3.10절).
     *
     * <p>두 번 훑는다. 첫 번째로 상품마다 묶음이 어느 자리에서 맞았는지(병렬), 두 번째로 점수. 10만 건 풀에서 요청당
     * 수십 ms 를 목표로 하고 실측은 13절에 적는다.
     */
    public CatalogMessages.ItemsResponse search(String merchantId, CatalogMessages.SearchRequest request) {
        merchants.requireMall(merchantId);
        CatalogMessages.SearchRequest.Filters filters = request == null ? null : request.filters();
        List<Product> products = store.all(merchantId);
        List<SearchDoc> docs = store.searchDocs(merchantId);
        List<Term> terms = terms(request == null ? null : request.query(), docs);

        List<CatalogMessages.Item> items = new ArrayList<>();
        if (terms.isEmpty()) {
            // 빈 검색어는 전부 후보다. 순서를 매길 근거가 없어 풀 순서 그대로 상한까지 낸다(470 결정 유지).
            for (Product product : products) {
                if (items.size() == MAX_RESULTS) {
                    break;
                }
                if (onSale(product) && matchesPrice(product, filters) && matchesCurrency(product, filters)) {
                    items.add(CatalogMessages.Item.of(product));
                }
            }
            return new CatalogMessages.ItemsResponse(items);
        }

        int width = terms.size();
        byte[] tiers = new byte[products.size() * width];
        double[] weights = weights(terms);
        int headIndex = headIndex(weights);
        List<String> head = terms.get(headIndex).group();
        String query = request.query().toLowerCase(Locale.ROOT);
        boolean petQuery = tuning.isPetQuery(query);
        List<String> titleAccessories = accessoryWords(head, tuning.accessoryTitleWords());
        List<String> leafAccessories = accessoryWords(head, tuning.accessoryCategoryWords());
        // 4·5차 동점 키(펫·부속품·카테고리 일치·섞인 칸 감점)도 여기서 같이 잰다. 순차인 두 번째 훑기에 두면 후보가 수만 건인
        // 질의에서 p95 가 86 → 100~114ms 로 늘었다(13절 실측). 첫 번째 훑기는 병렬이라 거기 얹는다
        byte[] tieFlags = new byte[products.size()];
        // 1차 훑기는 상품마다 독립이라 병렬로 돈다. 10만 건 × 낱말 × 세 필드 contains 가 검색 시간의 대부분이다.
        // 쓰는 칸이 상품마다 다르므로 배열에 경쟁이 없다.
        IntStream.range(0, products.size()).parallel().forEach(index -> {
            if (!onSale(products.get(index))) {
                return;
            }
            SearchDoc doc = docs.get(index);
            boolean matched = false;
            for (int k = 0; k < width; k++) {
                byte tier = doc.tierOrNoSpace(terms.get(k).group());
                tiers[index * width + k] = tier;
                matched |= tier > 0;
            }
            if (matched) {
                boolean pet = !petQuery && tuning.isPetProduct(doc.title(), doc.category());
                boolean accessory = doc.accessory(head, query, titleAccessories, leafAccessories);
                boolean weakHead = tiers[index * width + headIndex] == 1 && doc.weakCategoryHead(head);
                tieFlags[index] = (byte) ((pet ? PET_FLAG : 0) | (doc.categoryMatch(head) ? CATEGORY_FLAG : 0)
                        | (accessory ? ACCESSORY_FLAG : 0) | (weakHead ? WEAK_HEAD_FLAG : 0));
            }
        });

        List<Candidate> candidates = new ArrayList<>();
        for (int index = 0; index < products.size(); index++) {
            Product product = products.get(index);
            if (!onSale(product)) {
                continue;
            }
            double score = 0;
            int weakest = Integer.MAX_VALUE;
            for (int k = 0; k < width; k++) {
                byte tier = tiers[index * width + k];
                if (tier > 0) {
                    score += weights[k];
                    weakest = Math.min(weakest, tier);
                }
            }
            if (weakest == Integer.MAX_VALUE
                    || !matchesPrice(product, filters) || !matchesCurrency(product, filters)) {
                continue;
            }
            byte flags = tieFlags[index];
            if ((flags & WEAK_HEAD_FLAG) != 0) {
                // 규칙 16 — 머리 명사가 섞인 칸으로만 맞았다. 머리 명사 가중치를 주지 않는다
                score -= weights[headIndex];
            }
            candidates.add(new Candidate(index, score, weakest, tiers[index * width + headIndex],
                    (flags & PET_FLAG) != 0, (flags & ACCESSORY_FLAG) != 0, (flags & CATEGORY_FLAG) != 0));
        }
        candidates.sort(Candidate.ORDER);
        for (Candidate candidate : candidates) {
            if (items.size() == MAX_RESULTS) {
                break;
            }
            items.add(CatalogMessages.Item.of(products.get(candidate.index())));
        }
        return new CatalogMessages.ItemsResponse(items);
    }

    /** 없는 ID 는 조용히 빠진다. 404 를 내지 않는다. Core 가 개수를 비교해 판단한다. */
    public CatalogMessages.ItemsResponse lookup(String merchantId, CatalogMessages.LookupRequest request) {
        merchants.requireMall(merchantId);
        if (request == null || request.productIds() == null || request.productIds().isEmpty()) {
            return new CatalogMessages.ItemsResponse(List.of());
        }
        List<CatalogMessages.Item> items = request.productIds().stream()
                .map(productId -> store.byExternalId(merchantId, productId))
                .flatMap(java.util.Optional::stream)
                .filter(CatalogService::onSale)
                .map(CatalogMessages.Item::of)
                .toList();
        return new CatalogMessages.ItemsResponse(items);
    }

    public CatalogMessages.ProductDetail product(String merchantId, CatalogMessages.ProductRequest request) {
        merchants.requireMall(merchantId);
        String productId = request == null ? null : request.productId();
        if (productId == null || productId.isBlank()) {
            throw ApiException.productNotFound("(빈 productId)");
        }
        return store.byExternalId(merchantId, productId)
                .filter(CatalogService::onSale)
                .map(CatalogMessages.ProductDetail::of)
                .orElseThrow(() -> ApiException.productNotFound(productId));
    }

    private static boolean onSale(Product product) {
        return product.status() == Product.ProductStatus.ACTIVE;
    }

    /**
     * 검색어 토큰 하나. 수식어·속성 판정은 원래 낱말({@code token}·{@code surface})로 한다 — 묶음의 다른 낱말이 아니라.
     *
     * @param token   소문자 토큰
     * @param surface 토큰이 나온 어절 원문(소문자). "추천해줘" 가 "추천" 이 되어도 수식어로 잡기 위해
     * @param group   동의어 묶음(+ 퍼지 백오프가 더한 낱말)
     * @param tail    머리 명사 후보인가. 어절 토큰은 참, 조각으로 갈린 것은 마지막 조각만 참
     * @param light   가볍게 볼 토큰인가(수식어·속성·규격·한 글자). 머리 명사가 될 수 없다
     * @param blocked 머리 명사 불가 사전(규칙 13)으로 가벼워졌다. 그래서 머리 후보가 없어지면 가볍지 않은 마지막 토큰이 머리다
     */
    record Term(String token, String surface, List<String> group, boolean tail, boolean light, boolean blocked) {
        Term(String token, String surface, List<String> group, boolean tail, boolean light) {
            this(token, surface, group, tail, light, false);
        }

        Term with(List<String> newGroup) {
            return new Term(token, surface, newGroup, tail, light, blocked);
        }

        Term lightened() {
            return new Term(token, surface, group, false, true, blocked);
        }

        Term blockedAsHead() {
            return new Term(token, surface, group, false, true, true);
        }
    }

    /**
     * @param headTier      머리 명사가 맞은 자리. 동점에서 최소 자리보다 먼저 본다(규칙 17)
     * @param pet           펫 상품이고 반려동물 검색이 아니다. 같은 점수 안에서 뒤로 간다(규칙 10)
     * @param accessory     부속품이다. 같은 점수 안에서 펫 다음으로 뒤로 간다(규칙 14)
     * @param categoryMatch 카테고리 조각이 머리 명사로 끝난다(섞인 칸이면 제목 뒷받침). 같은 점수 안에서 앞으로 온다(규칙 9·15)
     */
    private record Candidate(int index, double score, int weakestTier, int headTier, boolean pet, boolean accessory,
                             boolean categoryMatch) {
        /** 점수 내림 → 펫 아님 → 부속품 아님 → 카테고리 일치 → 머리 자리 내림 → 최소 자리 내림 → 풀 순서. 박싱 없이 비교한다. */
        static final Comparator<Candidate> ORDER = (a, b) -> {
            int c = Double.compare(b.score, a.score);
            if (c != 0) {
                return c;
            }
            if (a.pet != b.pet) {
                return a.pet ? 1 : -1;
            }
            if (a.accessory != b.accessory) {
                return a.accessory ? 1 : -1;
            }
            if (a.categoryMatch != b.categoryMatch) {
                return a.categoryMatch ? -1 : 1;
            }
            c = Integer.compare(b.headTier, a.headTier);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(b.weakestTier, a.weakestTier);
            return c != 0 ? c : Integer.compare(a.index, b.index);
        };
    }

    /**
     * 이 검색에서 볼 부속품 낱말(규칙 14). 공통 목록에 자리별 추가 낱말을 붙이고, 머리 명사가 가방 종류면 가방 낱말을 뺀다 —
     * 다이소 백팩의 말단 칸이 "가방" 이라 진짜 백팩이 부속품으로 뒤로 갔다(장부 10절).
     */
    private List<String> accessoryWords(List<String> head, List<String> extra) {
        boolean bagHead = false;
        for (String word : head) {
            for (String bag : tuning.bagHeads()) {
                if (word.endsWith(bag)) {
                    bagHead = true;
                    break;
                }
            }
        }
        List<String> out = new ArrayList<>(tuning.accessoryWords().size() + extra.size());
        for (String word : tuning.accessoryWords()) {
            if (!(bagHead && word.equals(tuning.bagWord()))) {
                out.add(word);
            }
        }
        out.addAll(extra);
        return out;
    }

    /** 가중치가 가장 큰 토큰. 같으면 앞 토큰 — 머리 명사가 있으면 그것이고, 전부 가벼우면 첫 토큰이다. */
    private static int headIndex(double[] weights) {
        int best = 0;
        for (int k = 1; k < weights.length; k++) {
            if (weights[k] > weights[best]) {
                best = k;
            }
        }
        return best;
    }

    /**
     * 검색어 → 토큰(ADR 0013 규칙 1~4·6). 빈 검색어는 빈 목록이고 그때 결과를 막는 것은 {@link #MAX_RESULTS} 하나뿐이다.
     * 패키지 공개는 파이썬 벤치와 토큰을 대조하는 테스트를 위해서다.
     */
    List<Term> terms(String query, List<SearchDoc> docs) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<Draft> drafts = new ArrayList<>();
        if (refiner != null) {
            List<QueryRefiner.Token> refined =
                    refiner.refine(query, surface -> count(docs, List.of(surface), false) > 0);
            for (QueryRefiner.Token token : refined) {
                drafts.add(new Draft(token.token(), token.surface(), token.pieces(), true));
            }
        }
        if (drafts.isEmpty()) {
            // 규칙 5 안전망 — 전부 걸러졌거나 정제를 끈 경우 공백 분리 원문
            for (String token : query.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
                if (!token.isEmpty()) {
                    drafts.add(new Draft(token, token, List.of(), true));
                }
            }
        }

        // 규칙 4a 조각 백오프 — 어절 토큰이 한 화면도 못 채울 때만, 조각이 둘 이상이고 전부 두 글자 이상일 때만
        List<Term> terms = new ArrayList<>();
        for (Draft draft : drafts) {
            List<String> group = synonyms.getOrDefault(draft.token(), List.of(draft.token()));
            List<String> pieces = draft.pieces();
            boolean splittable = pieces.size() >= 2 && pieces.stream().allMatch(piece -> piece.length() >= 2);
            if (splittable && count(docs, group, true) < PAGE) {
                // 숫자가 든 토큰(500ml·2xl)의 조각은 규격의 조각이다. 무겁게 보면 "ml"·"xl" 이 머리 명사가 된다(단위 테스트가 잡았다)
                boolean spec = hasDigit(draft.token());
                for (int i = 0; i < pieces.size(); i++) {
                    String piece = pieces.get(i);
                    terms.add(new Term(piece, piece, synonyms.getOrDefault(piece, List.of(piece)),
                            !spec && draft.tail() && i == pieces.size() - 1, spec || isFluff(piece, piece)));
                }
            } else {
                terms.add(new Term(draft.token(), draft.surface(), group, draft.tail(),
                        isFluff(draft.token(), draft.surface())));
            }
        }
        // 규칙 4b 퍼지 백오프 — 그래도 한 화면 미만이면 자모가 비슷한 제목 낱말을 동의어로. 수식어에는 안 건다
        JamoBigrams vocabulary = store.vocabulary();
        for (int k = 0; k < terms.size(); k++) {
            Term term = terms.get(k);
            if (term.light() || count(docs, term.group(), true) >= PAGE) {
                continue;
            }
            List<String> near = vocabulary.similar(term.token());
            if (!near.isEmpty()) {
                LinkedHashSet<String> widened = new LinkedHashSet<>(term.group());
                widened.addAll(near);
                terms.set(k, term.with(List.copyOf(widened)));
            }
        }
        // 규칙 6 — 토큰이 둘 이상이면 한 글자·속성·규격도 수식어처럼 본다. 전부 그런 토큰이면 깎지 않는다
        if (terms.size() > 1) {
            for (int k = 0; k < terms.size(); k++) {
                if (terms.get(k).token().length() == 1) {
                    terms.set(k, terms.get(k).lightened());
                }
            }
            boolean allAttribute = terms.stream().allMatch(term -> term.light() || isAttribute(term.token()));
            if (!allAttribute) {
                for (int k = 0; k < terms.size(); k++) {
                    if (isAttribute(terms.get(k).token())) {
                        terms.set(k, terms.get(k).lightened());
                    }
                }
            }
            // 규칙 13 — 머리 명사 불가 사전(신체 부위·대상 사람·성능 명사·"~용"). 규칙 6 뒤에 따로 본다. 합치면 "전부 가볍다"
            // 판정이 달라진다("아기 소형" 은 규칙 6 에서 소형만 깎이고 여기서는 전부 가벼워 안 건다 — 머리는 아기)
            boolean allBlocked = terms.stream().allMatch(term -> term.light() || tuning.isHeadBlocked(term.token()));
            if (!allBlocked) {
                for (int k = 0; k < terms.size(); k++) {
                    Term term = terms.get(k);
                    if (!term.light() && tuning.isHeadBlocked(term.token())) {
                        terms.set(k, term.blockedAsHead());
                    }
                }
            }
        }
        return List.copyOf(terms);
    }

    /** 정제 직후의 토큰. 아직 동의어·백오프·가중치 전. */
    private record Draft(String token, String surface, List<String> pieces, boolean tail) {
    }

    private boolean isFluff(String token, String surface) {
        return tuning.isFluff(token) || tuning.isFluff(surface);
    }

    /** 속성 낱말 또는 숫자가 든 토큰(규격 — 500ml·10kg·2xl·a4). */
    private boolean isAttribute(String token) {
        return tuning.isAttribute(token) || hasDigit(token);
    }

    private static boolean hasDigit(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (Character.isDigit(token.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 묶음의 낱말 하나라도 든 상품 수. 백오프 판정용. 한 화면(4)만 넘으면 되므로 전부 세지 않고 멈춘다.
     *
     * <p><b>앞부분만 순차로 보고 모자랄 때만 병렬로 넘어간다(2026-09-23, 472).</b> 흔한 낱말은 앞에서 네 건을
     * 채우고 끝나므로 병렬 분할 비용을 물면 오히려 손해다 — 그리고 질의 대부분이 그 경우다. 반대로 드문 낱말은
     * 없다는 것을 알려면 전량을 훑어야 하는데, 본 스캔이 22코어를 쓰는 동안 이 판정만 한 코어로 도는 것이
     * 느린 질의의 원인이었다(13절 실측: 흔한 낱말 24.6ms 대 드문 낱말 74.8ms). 그 경우에만 병렬이 값을 한다.
     */
    private static int count(List<SearchDoc> docs, List<String> group, boolean noSpace) {
        int prefix = Math.min(docs.size(), PREFIX_SCAN);
        int found = 0;
        for (int index = 0; index < prefix; index++) {
            if (docs.get(index).containsAny(group, noSpace)) {
                found++;
                if (found >= PAGE) {
                    return found;
                }
            }
        }
        if (prefix == docs.size()) {
            return found;
        }
        long rest = docs.subList(prefix, docs.size()).parallelStream()
                .unordered()
                .filter(doc -> doc.containsAny(group, noSpace))
                .limit(PAGE - (long) found)
                .count();
        return found + (int) rest;
    }

    /**
     * 토큰마다 가중치(규칙 6~7). 기본 1, 가벼운 토큰은 fluff-weight, 머리 명사는 max(tail-boost, 나머지 합 + 0.5).
     *
     * <p>토큰이 전부 가벼운 것이면 깎지 않는다 — 그때는 그것이 검색어다. 토큰이 하나면 머리 명사 가중도 뜻이 없다.
     */
    private double[] weights(List<Term> terms) {
        boolean allLight = terms.stream().allMatch(Term::light);
        double[] weights = new double[terms.size()];
        for (int k = 0; k < terms.size(); k++) {
            weights[k] = !allLight && terms.get(k).light() ? tuning.fluffWeight() : 1.0;
        }
        int head = -1;
        for (int k = 0; k < terms.size(); k++) {
            if (terms.get(k).tail() && !terms.get(k).light()) {
                head = k;
            }
        }
        if (head < 0 && terms.stream().anyMatch(Term::blocked)) {
            // 규칙 13 이 머리 후보를 다 가져갔다(핸드크림 손톱 의 손톱). 가볍지 않은 마지막 토큰이 머리다
            for (int k = 0; k < terms.size(); k++) {
                if (!terms.get(k).light()) {
                    head = k;
                }
            }
        }
        if (head >= 0 && terms.size() > 1) {
            double others = 0;
            for (int k = 0; k < weights.length; k++) {
                if (k != head) {
                    others += weights[k];
                }
            }
            weights[head] = Math.max(tuning.tailBoost(), others + HEAD_MARGIN);
        }
        return weights;
    }

    private static boolean matchesPrice(Product product, CatalogMessages.SearchRequest.Filters filters) {
        if (filters == null || filters.maxPrice() == null) {
            return true;
        }
        return product.displayPrice() <= filters.maxPrice();
    }

    private static boolean matchesCurrency(Product product, CatalogMessages.SearchRequest.Filters filters) {
        if (filters == null || filters.currency() == null || filters.currency().isBlank()) {
            return true;
        }
        return product.currency().equalsIgnoreCase(filters.currency());
    }
}
