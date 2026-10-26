package com.copenhagen.merchantmock.catalog;

import java.time.Instant;

/**
 * 마지막으로 적재한 풀이 무엇이었는지(DESIGN.md 5.7절, V12 {@code catalog_pool_state}).
 *
 * <p>풀 sha256·몰 목록·limit 셋이 같으면 기동 때 적재를 건너뛴다. 27만 행을 기동마다 다시 쓰지 않기 위해서다.
 * 셋 중 하나라도 다르면 다시 쓴다 — 몰이 늘었거나 limit 을 올렸으면 새 행이 필요하다.
 *
 * @param malls 쉼표로 이은 몰 id 목록. 설정 순서 그대로
 */
public record CatalogPoolState(String poolSha256, String malls, int poolLimit, int productCount, Instant loadedAt) {

    public boolean matches(String sha256, String mallList, int limit) {
        return poolSha256.equals(sha256) && malls.equals(mallList) && poolLimit == limit;
    }
}
