package com.copenhagen.merchantmock.catalog;

import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 연관 상품 설정(DESIGN.md 6.8.1절, ADR 0014, Jira 511). {@code MerchantProperties} 레코드에 칸을 더하지 않고 따로 둔다 —
 * 그 레코드를 만드는 테스트가 전부 흔들리지 않게.
 *
 * <p><b>기본은 꺼짐이다(사용자 결정 2026-09-26).</b> 꺼져 있으면 빈을 만들지 않아 색인도 이웃 파일도 읽지 않고 엔드포인트도 없다.
 * 켜는 것은 {@code application.yml} 기본값을 바꾸는 후속 MR 이다. 롤백 사다리는 6.8.1절.
 *
 * @param enabled       true 일 때만 연관 상품 빈을 만든다
 * @param mode          rrf3(검색 + TF-IDF + e5nb 이웃) · rrf2(검색 + TF-IDF) · search(검색만). 준비 중 실패하면 더 낮은 단계로 돈다
 * @param neighborsFile e5nb 이웃 파일. rrf3 에서만 읽는다
 * @param defaultLimit  limit 이 없을 때
 * @param maxLimit      limit 상한
 * @param maxSeeds      productIds 상한
 */
@ConfigurationProperties(prefix = "merchant.catalog.related")
public record RelatedProperties(
        boolean enabled,
        String mode,
        String neighborsFile,
        Integer defaultLimit,
        Integer maxLimit,
        Integer maxSeeds) {

    public RelatedProperties {
        mode = mode == null || mode.isBlank() ? Mode.RRF3.value() : mode.trim().toLowerCase(Locale.ROOT);
        Mode.of(mode);
        neighborsFile = neighborsFile == null ? "classpath:catalog/related-e5nb.bin.gz" : neighborsFile.trim();
        defaultLimit = defaultLimit == null ? 4 : defaultLimit;
        maxLimit = maxLimit == null ? 20 : maxLimit;
        maxSeeds = maxSeeds == null ? 20 : maxSeeds;
        if (defaultLimit < 1 || defaultLimit > maxLimit) {
            throw new IllegalArgumentException(
                    "merchant.catalog.related.default-limit 는 1 이상 max-limit 이하: " + defaultLimit);
        }
        if (maxSeeds < 1) {
            throw new IllegalArgumentException("merchant.catalog.related.max-seeds 는 1 이상: " + maxSeeds);
        }
    }

    public Mode requestedMode() {
        return Mode.of(mode);
    }

    /** 단계가 높을수록 신호가 많다. 준비 중 신호 하나를 못 만들면 한 단계 내려간다. */
    public enum Mode {
        SEARCH("search"),
        RRF2("rrf2"),
        RRF3("rrf3");

        private final String value;

        Mode(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Mode of(String value) {
            for (Mode mode : values()) {
                if (mode.value.equals(value)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("merchant.catalog.related.mode 는 rrf3·rrf2·search 중 하나: " + value);
        }

        Mode atMost(Mode other) {
            return compareTo(other) <= 0 ? this : other;
        }
    }
}
