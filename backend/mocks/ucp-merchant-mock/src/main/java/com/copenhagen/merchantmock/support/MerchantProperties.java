package com.copenhagen.merchantmock.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml 의 merchant.* 설정. 구조는 DESIGN.md 3.4절이 소유한다.
 *
 * <p>컨테이너 하나가 몰 셋을 서빙한다(393, ADR 0008). 몰은 {@code merchant.malls[]} 목록이고 경로의 두 번째
 * 세그먼트가 어느 몰인지 말한다. 서명키·통화·UCP 버전은 몰 셋이 공유한다 — 인스턴스가 하나라 키 공급자도 하나다.
 *
 * <p>개인키는 설정 파일에 저장하지 않는다(설계서 18.5). privateKeyLocation 과 privateKeyPem 은
 * 환경변수로만 채우고 기본값은 빈 문자열이다. publicKeyJwk 는 공개키라 파일에 그대로 둔다.
 */
@ConfigurationProperties(prefix = "merchant")
public record MerchantProperties(
        List<Mall> malls,
        String ucpVersion,
        String currency,
        String publicBaseUrl,
        Catalog catalog,
        Checkout checkout,
        Fulfillment fulfillment,
        Signing signing,
        Capabilities capabilities,
        PaymentHandler paymentHandler,
        List<TrustedPlatform> trustedPlatforms,
        Scenario scenario,
        Webhook webhook) {

    /** 다른 서비스(/api/core, /api/pay)와 맞춘 접두어. 몰 세그먼트가 그 뒤에 온다(DESIGN.md 3.3절). */
    public static final String API_PREFIX = "/api/ucp/v1";

    public MerchantProperties {
        if (malls == null || malls.isEmpty()) {
            throw new IllegalArgumentException("merchant.malls 가 비어 있다. 몰이 최소 하나 있어야 한다");
        }
        malls = List.copyOf(malls);
        catalog = catalog == null ? new Catalog("", 0, List.of(), null) : catalog;
    }

    /** 몰 하나의 UCP REST base endpoint. Core 는 이 값을 그 몰의 Profile 에서 읽어 간다. */
    public String restEndpoint(String merchantId) {
        return publicBaseUrl + API_PREFIX + "/" + merchantId;
    }

    public Optional<Mall> mall(String merchantId) {
        if (merchantId == null) {
            return Optional.empty();
        }
        return malls.stream().filter(mall -> mall.id().equals(merchantId)).findFirst();
    }

    /** 경로 세그먼트가 가리키는 몰. 없으면 404 다(DESIGN.md 3.3절). */
    public Mall requireMall(String merchantId) {
        return mall(merchantId).orElseThrow(() -> ApiException.merchantNotFound(merchantId));
    }

    /** 목록의 첫 몰. 옛 경로 {@code /ucp/v1/**} 가 이 몰의 별칭이다({@link LegacyPathFilter}). */
    public Mall defaultMall() {
        return malls.get(0);
    }

    /**
     * 몰 하나. {@code id} 는 Core {@code services.merchants[].id}, Pay 토큰 JSON 의 키, 경로 세그먼트와 같은
     * 문자열이어야 한다(DESIGN.md 19.5절). {@code sellerName} 은 상호다.
     */
    public record Mall(String id, String sellerName) {
        public Mall {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("merchant.malls[].id 가 비어 있다");
            }
            sellerName = sellerName == null || sellerName.isBlank() ? id : sellerName.trim();
        }
    }

    /**
     * 카탈로그 풀과 검색 조정값(DESIGN.md 5.7절·6.7절, 2026-09-21).
     *
     * <p>{@code poolFile} 이 비어 있으면 로더가 아무 일도 하지 않고 V3 fixture 5건만 남는다. 기본값이 비어 있어야
     * 테스트와 CI 가 fixture 구성을 그대로 본다. 풀은 파일 하나이고 몰 셋의 가격·재고 편차는 로더가 계산한다.
     *
     * @param poolFile  classpath: 또는 file: 경로. .json 또는 .json.gz
     * @param poolLimit 풀의 앞 몇 건만 실을지. 0 이면 전량. 앞 1,592건이 시연 필수라 그보다 작으면 시연이 성립하지 않는다
     * @param synonyms  검색 동의어 묶음(470). 한 묶음 안의 낱말은 서로를 대신한다
     * @param search    검색 점수 조정값(6.7절)
     */
    public record Catalog(String poolFile, int poolLimit, List<List<String>> synonyms, Search search) {
        public Catalog {
            poolFile = poolFile == null ? "" : poolFile.trim();
            if (poolLimit < 0) {
                throw new IllegalArgumentException("merchant.catalog.pool-limit 는 0 이상이어야 한다: " + poolLimit);
            }
            synonyms = normalizeSynonyms(synonyms);
            search = search == null ? Search.defaults() : search;
        }

        /** 소문자로 눕히고 두 낱말 미만인 묶음은 버린다. 혼자인 낱말은 확장할 것이 없다. */
        private static List<List<String>> normalizeSynonyms(List<List<String>> groups) {
            if (groups == null) {
                return List.of();
            }
            List<List<String>> cleaned = new ArrayList<>();
            for (List<String> group : groups) {
                if (group == null) {
                    continue;
                }
                List<String> words = new ArrayList<>();
                for (String word : group) {
                    if (word != null && !word.isBlank()) {
                        words.add(word.trim().toLowerCase(Locale.ROOT));
                    }
                }
                if (words.size() >= 2) {
                    cleaned.add(List.copyOf(words));
                }
            }
            return List.copyOf(cleaned);
        }

        public boolean hasPoolFile() {
            return !poolFile.isEmpty();
        }

        /**
         * 검색 점수 조정값(DESIGN.md 6.7절, ADR 0012·0013). yml 로 뺀 이유는 리허설에서 결과가 어색할 때 배포 없이
         * 만질 수 있어야 해서다. {@code attribute-words} 를 비우고 {@code tail-boost} 1.5 면 2차(A10)의 점수 규칙이고,
         * {@code nori} 를 끄면 검색어 정제도 빠져 A10 그 자체다.
         *
         * @param fluffWords     정보가 없는 수식어. "맛있는·신선한·저렴한·추천해줘·이하" 같은 것. 소문자로 비교한다
         * @param fluffWeight    수식어·속성·규격·한 글자 토큰의 가중치 배율. 0 초과 1 이하. 기본 0.2
         * @param tailBoost      머리 명사 가중치의 최솟값. 3차부터 머리 명사 가중치는 max(tailBoost, 나머지 합 + 0.5). 1 이상. 기본 1.5
         * @param attributeWords 속성 낱말(ADR 0013 규칙 6). 대용량·소형·접이식·자동·무선·화이트 처럼 상품군이 아니라 규격·형태·용도·
         *                       색상을 말하는 낱말. 검색어 뒤에 와도 머리 명사가 되지 않는다. 소문자로 비교한다
         * @param nori           검색어 정제(nori) 켜기. 기본 true. 끄면 공백 분리만 한다
         * @param petQueryWords     검색어에 이 중 하나가 부분문자열로 있으면 반려동물 검색이다(4차 규칙 10). 소문자로 비교한다
         * @param petQueryTokens    검색어를 공백으로 가른 낱말 하나가 이 중 하나와 같으면 반려동물 검색이다. "개" 처럼 부분문자열로
         *                          보면 "10개" 에도 걸리는 낱말
         * @param petProductMarkers 제목이나 카테고리에 이 중 하나가 있으면 펫 상품이다. 반려동물 검색이 아니면 같은 점수 안에서 뒤로
         *                          간다. 세 목록이 다 비면 규칙이 꺼진다
         * @param headBlockedWords  머리 명사가 될 수 없는 낱말(5차 규칙 13). 신체 부위·대상 사람·성능·효과 명사. attribute-words 와 따로
         *                          판정한다. 세 글자 이상이고 "용" 으로 끝나는 토큰도 같은 취급이다(코드)
         * @param accessoryWords    부속품 낱말(5차 규칙 14). 제목 낱말이나 카테고리 말단 조각이 이 낱말로 끝나면 부속품 후보다
         * @param accessoryTitleWords    제목에서만 더 보는 부속품 낱말(건조대)
         * @param accessoryCategoryWords 카테고리 말단에서만 더 보는 낱말(소품)
         * @param bagHeads          머리 명사가 이 중 하나로 끝나면 {@code bagWord} 를 부속품 낱말에서 뺀다
         * @param bagWord           가방 종류 검색에서 부속품으로 보지 않을 낱말. 비면 예외가 없다
         */
        public record Search(List<String> fluffWords, Double fluffWeight, Double tailBoost, List<String> attributeWords,
                             Boolean nori, List<String> petQueryWords, List<String> petQueryTokens,
                             List<String> petProductMarkers, List<String> headBlockedWords, List<String> accessoryWords,
                             List<String> accessoryTitleWords, List<String> accessoryCategoryWords,
                             List<String> bagHeads, String bagWord) {
            public Search {
                fluffWords = lowerWords(fluffWords);
                attributeWords = lowerWords(attributeWords);
                petQueryWords = lowerWords(petQueryWords);
                petQueryTokens = lowerWords(petQueryTokens);
                petProductMarkers = lowerWords(petProductMarkers);
                headBlockedWords = lowerWords(headBlockedWords);
                accessoryWords = lowerWords(accessoryWords);
                accessoryTitleWords = lowerWords(accessoryTitleWords);
                accessoryCategoryWords = lowerWords(accessoryCategoryWords);
                bagHeads = lowerWords(bagHeads);
                bagWord = bagWord == null ? "" : bagWord.trim().toLowerCase(Locale.ROOT);
                fluffWeight = fluffWeight == null ? 0.2 : fluffWeight;
                tailBoost = tailBoost == null ? 1.5 : tailBoost;
                nori = nori == null || nori;
                if (fluffWeight <= 0 || fluffWeight > 1) {
                    throw new IllegalArgumentException("merchant.catalog.search.fluff-weight 는 0 초과 1 이하: "
                            + fluffWeight);
                }
                if (tailBoost < 1) {
                    throw new IllegalArgumentException("merchant.catalog.search.tail-boost 는 1 이상: " + tailBoost);
                }
            }

            private static List<String> lowerWords(List<String> raw) {
                List<String> words = new ArrayList<>();
                if (raw != null) {
                    for (String word : raw) {
                        if (word != null && !word.isBlank()) {
                            words.add(word.trim().toLowerCase(Locale.ROOT));
                        }
                    }
                }
                return List.copyOf(words);
            }

            public static Search defaults() {
                return new Search(List.of(), null, null, List.of(), null, List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of(), null);
            }

            public boolean isFluff(String token) {
                return fluffWords.contains(token);
            }

            public boolean isAttribute(String token) {
                return attributeWords.contains(token);
            }

            /** 머리 명사가 될 수 없는 토큰인가(5차 규칙 13). 사전 낱말이거나 세 글자 이상이고 "용" 으로 끝난다(캠핑용·피부용). */
            public boolean isHeadBlocked(String token) {
                return headBlockedWords.contains(token) || (token.length() >= 3 && token.endsWith("용"));
            }

            /** 반려동물 검색인가. {@code query} 는 소문자 검색어 원문이다. */
            public boolean isPetQuery(String query) {
                for (String word : petQueryWords) {
                    if (query.contains(word)) {
                        return true;
                    }
                }
                if (!petQueryTokens.isEmpty()) {
                    for (String token : query.trim().split("\\s+")) {
                        if (petQueryTokens.contains(token)) {
                            return true;
                        }
                    }
                }
                return false;
            }

            /** 펫 상품인가. {@code title}·{@code category} 는 소문자다. */
            public boolean isPetProduct(String title, String category) {
                for (String marker : petProductMarkers) {
                    if (title.contains(marker) || category.contains(marker)) {
                        return true;
                    }
                }
                return false;
            }
        }
    }

    /**
     * @param ttl           ready_for_complete 판정 시각 + 이 값이 expires_at 이다(설계서 D-03)
     * @param scenarioDelay COMPLETE_TIMEOUT_BEFORE_PROCESS·AFTER_ORDER 가 켜졌을 때 Complete 가 기다리는 시간(9.3절).
     *                      Core 의 요청 타임아웃보다 길어야 응답 유실이 재현된다. 시나리오가 꺼져 있으면 읽지 않는다
     */
    public record Checkout(Duration ttl, Duration scenarioDelay) {
        public Checkout {
            scenarioDelay = scenarioDelay == null ? Duration.ofSeconds(10) : scenarioDelay;
        }
    }

    /**
     * 배송비(DESIGN.md 7.1절, ADR 0009).
     *
     * @param flatFee     배송 가능한 모든 주소에 붙는 기본 배송비
     * @param remoteAreas 도서산간 추가금 구간. 목록 순서대로 보고 먼저 걸리는 하나만 쓴다. 비면 추가금이 없다
     */
    public record Fulfillment(long flatFee, List<RemoteArea> remoteAreas) {

        public Fulfillment {
            remoteAreas = remoteAreas == null ? List.of() : List.copyOf(remoteAreas);
        }

        /**
         * 배송지가 걸리는 구간. 없으면 일반 지역이다.
         *
         * <p>우편번호도 주소도 판정에 못 쓰면 일반 지역으로 본다. 판단할 수 없을 때 돈을 더 받는 쪽으로
         * 추측하지 않는다. 목록 순서대로 보고 먼저 걸리는 하나를 쓴다.
         */
        public Optional<RemoteArea> remoteAreaOf(String postalCode, String... addressLines) {
            String digits = postalCode == null ? "" : postalCode.replaceAll("\\D", "");
            StringBuilder address = new StringBuilder();
            for (String line : addressLines) {
                if (line != null) {
                    address.append(line).append(' ');
                }
            }
            String joined = address.toString();
            if (digits.isEmpty() && joined.isBlank()) {
                return Optional.empty();
            }
            return remoteAreas.stream().filter(area -> area.matches(digits, joined)).findFirst();
        }

        /**
         * 도서산간 구간 하나(DESIGN.md 7.1절, ADR 0009).
         *
         * <p>우체국의 "우편물 송달기준 적용 곤란지역" 은 우편번호가 아니라 군·면·섬 이름으로 적혀 있다.
         * 그래서 판정 키를 둘 둔다. 제주처럼 대역이 뚜렷한 곳은 우편번호 접두어로, 나머지 섬들은
         * 주소 낱말로 잡는다. 둘 중 하나만 걸려도 그 구간이다.
         *
         * @param name            시연·메시지에 보이는 구간 이름
         * @param surcharge       기본 배송비에 더할 금액. minor unit
         * @param postalPrefixes  이 구간으로 볼 우편번호 접두어들
         * @param addressKeywords 주소에 이 낱말이 있으면 이 구간이다
         * @param excludeKeywords 위에 걸려도 이 낱말이 있으면 이 구간이 아니다. 우체국 목록의 "~를 제외한"
         */
        public record RemoteArea(String name, long surcharge, List<String> postalPrefixes,
                List<String> addressKeywords, List<String> excludeKeywords) {

            public RemoteArea {
                postalPrefixes = postalPrefixes == null ? List.of() : List.copyOf(postalPrefixes);
                addressKeywords = addressKeywords == null ? List.of() : List.copyOf(addressKeywords);
                excludeKeywords = excludeKeywords == null ? List.of() : List.copyOf(excludeKeywords);
            }

            boolean matches(String postalDigits, String address) {
                if (excludeKeywords.stream().anyMatch(address::contains)) {
                    return false;
                }
                boolean byPostal = !postalDigits.isEmpty()
                        && postalPrefixes.stream().anyMatch(postalDigits::startsWith);
                return byPostal || addressKeywords.stream().anyMatch(address::contains);
            }
        }
    }

    public record Signing(
            String kid,
            String algorithm,
            String privateKeyLocation,
            String privateKeyPem,
            String publicKeyJwk) {
    }

    public record Capabilities(Ap2Like ap2Like) {
        /**
         * AP2-like Capability 광고 여부. 네임스페이스가 Core·Pay 와 합의되기 전까지 끈다.
         * 합의되면 enabled 만 true 로 바꾸면 된다(DESIGN.md 11절).
         */
        public record Ap2Like(boolean enabled, String namespace) {
        }
    }

    public record PaymentHandler(String namespace, String id, String version) {
    }

    public record TrustedPlatform(String id, String profileUrl) {
    }

    public record Scenario(String initial) {
    }

    /**
     * 웹훅 재시도 정책(DESIGN.md 7.4절). 실패 n회 뒤 다음 시도까지 {@code retryInitialDelay × 2^(n-1)}.
     * {@code retryMaxAttempts} 를 넘기면 184 워커가 FAILED 로 닫는다.
     *
     * <p>{@code scenarioDelay} 는 시나리오 {@code WEBHOOK_DELAYED} 가 켜졌을 때 보내기 전에 기다리는 시간이다(9.3절).
     * 시나리오가 꺼져 있으면 읽지 않는다.
     */
    public record Webhook(Duration retryInitialDelay, int retryMaxAttempts, Duration scenarioDelay) {
        public Webhook {
            retryInitialDelay = retryInitialDelay == null ? Duration.ofSeconds(10) : retryInitialDelay;
            retryMaxAttempts = retryMaxAttempts <= 0 ? 8 : retryMaxAttempts;
            scenarioDelay = scenarioDelay == null ? Duration.ofSeconds(5) : scenarioDelay;
        }
    }
}
