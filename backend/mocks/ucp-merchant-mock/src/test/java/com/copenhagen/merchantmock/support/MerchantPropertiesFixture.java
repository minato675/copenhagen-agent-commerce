package com.copenhagen.merchantmock.support;

import java.time.Duration;
import java.util.List;

/** 테스트에서 쓰는 merchant.* 설정 기본값. 실제 application.yml 과 같은 모양으로 맞춘다. */
public final class MerchantPropertiesFixture {

    /** 몰 셋(393). 첫 몰 merchant-a 가 V3 fixture 5건의 몰이고 옛 경로의 별칭이다. */
    public static final String MERCHANT_A = "merchant-a";
    public static final String MERCHANT_B = "merchant-b";
    public static final String MERCHANT_C = "merchant-c";
    public static final List<MerchantProperties.Mall> MALLS = List.of(
            new MerchantProperties.Mall(MERCHANT_A, "한강마켓"),
            new MerchantProperties.Mall(MERCHANT_B, "노을상점"),
            new MerchantProperties.Mall(MERCHANT_C, "별빛몰"));

    /**
     * 검색 동의어 묶음(470). application.yml 과 같은 모양이고 묶음 수만 줄였다.
     *
     * <p>묶음은 좁아야 한다. 상위어를 넣으면 재현율 대신 정밀도가 무너진다 — 실측으로 "오일" 을 넣었더니
     * "올리브유" 검색에 클렌징 오일과 립 오일이 48건 걸렸다(DESIGN.md 5.7절).
     */
    public static final List<List<String>> SYNONYMS = List.of(
            List.of("신발", "운동화", "러닝화", "스니커즈", "워킹화", "스포츠화"),
            List.of("올리브유", "올리브오일"),
            List.of("milk", "우유"),
            List.of("계란", "달걀"));

    /**
     * 도서산간 추가금 구간(427). 판정 규칙은 application.yml 과 같은 모양이고 낱말 목록만 줄였다.
     *
     * <p>기본 배송비는 여기서 3000 이다. 실제 설정은 0(일반 지역 무료)이지만 픽스처까지 0 으로 두면
     * "기본 + 추가금" 덧셈이 어느 테스트에서도 안 드러난다. 0 인 설정은 그 자체로 시험한다
     * ({@code CheckoutServiceTest.create_freeShippingConfig_chargesOnlyRemoteAreaSurcharge}).
     */
    public static final List<MerchantProperties.Fulfillment.RemoteArea> REMOTE_AREAS = List.of(
            new MerchantProperties.Fulfillment.RemoteArea("도서산간", 5000L, List.of("402"),
                    List.of("울릉군", "옹진군", "추자"), List.of("영흥면")),
            new MerchantProperties.Fulfillment.RemoteArea("제주", 3000L, List.of("63"),
                    List.of(), List.of()));

    /** 검색 수식어 목록(6.7절). application.yml 과 같은 모양이고 낱말만 줄였다. */
    public static final MerchantProperties.Catalog.Search SEARCH = new MerchantProperties.Catalog.Search(
            List.of("맛있는", "신선한", "저렴한", "가벼운", "추천해줘", "이하", "만원"), 0.2, 1.5,
            List.of("대용량", "소형", "대형", "무선", "세트", "4인용", "화이트"), true,
            List.of("펫", "강아지", "고양이", "사료"), List.of("개"), List.of("[펫]", "강아지", "고양이"),
            List.of("손톱", "아기", "색상"), List.of("끈", "캡", "정리대", "가방"), List.of("건조대"), List.of("소품"),
            List.of("가방", "백팩"), "가방");

    public static final String PUBLIC_JWK = """
            {"kty":"EC","crv":"P-256",\
            "x":"Kv4fWw_TEzEQ2EOMOgsbIyH9314FhRdQknrvaPY5Q3Q",\
            "y":"d989gH2qhu_9cnv2pmrZs_rlT3kso94QTC_VXvkzEmc",\
            "use":"sig","alg":"ES256","kid":"merchant-a-es256-1"}""";

    private MerchantPropertiesFixture() {
    }

    public static MerchantProperties defaults() {
        return withSigning(new MerchantProperties.Signing(
                "merchant-a-es256-1", "ES256", "", "", PUBLIC_JWK));
    }

    public static MerchantProperties withSigning(MerchantProperties.Signing signing) {
        return new MerchantProperties(
                MALLS,
                "2026-08-25",
                "KRW",
                "http://ucp-merchant-mock:8080",
                new MerchantProperties.Catalog("", 0, SYNONYMS, SEARCH),
                new MerchantProperties.Checkout(Duration.ofMinutes(5), Duration.ofSeconds(5)),
                new MerchantProperties.Fulfillment(3000L, REMOTE_AREAS),
                signing,
                new MerchantProperties.Capabilities(
                        new MerchantProperties.Capabilities.Ap2Like(false, "com.copenhagen.payment.ap2_like")),
                new MerchantProperties.PaymentHandler("com.copenhagen.pay", "copenhagen-pay-handler", "1"),
                List.of(new MerchantProperties.TrustedPlatform(
                        "core", "http://core-service:8080/.well-known/ucp")),
                new MerchantProperties.Scenario("NORMAL"),
                new MerchantProperties.Webhook(Duration.ofSeconds(10), 8, Duration.ofSeconds(5)));
    }

    /** 카탈로그 풀 경로와 limit 만 바꾼 설정. 로더·풀 파일 테스트가 쓴다(DESIGN.md 5.7절). */
    public static MerchantProperties withCatalogPool(String poolFile, int poolLimit) {
        return withCatalog(new MerchantProperties.Catalog(poolFile, poolLimit, SYNONYMS, SEARCH));
    }

    /** 카탈로그 설정만 바꾼 설정. 파이썬 벤치 대조 테스트가 application.yml 의 동의어·수식어·속성 목록으로 만든다. */
    public static MerchantProperties withCatalog(MerchantProperties.Catalog catalog) {
        MerchantProperties base = defaults();
        return new MerchantProperties(
                base.malls(),
                base.ucpVersion(),
                base.currency(),
                base.publicBaseUrl(),
                catalog,
                base.checkout(),
                base.fulfillment(),
                base.signing(),
                base.capabilities(),
                base.paymentHandler(),
                base.trustedPlatforms(),
                base.scenario(),
                base.webhook());
    }

    public static MerchantProperties withAp2LikeEnabled() {
        MerchantProperties base = defaults();
        return new MerchantProperties(
                base.malls(),
                base.ucpVersion(),
                base.currency(),
                base.publicBaseUrl(),
                base.catalog(),
                base.checkout(),
                base.fulfillment(),
                base.signing(),
                new MerchantProperties.Capabilities(
                        new MerchantProperties.Capabilities.Ap2Like(true, "com.copenhagen.payment.ap2_like")),
                base.paymentHandler(),
                base.trustedPlatforms(),
                base.scenario(),
                base.webhook());
    }
}
