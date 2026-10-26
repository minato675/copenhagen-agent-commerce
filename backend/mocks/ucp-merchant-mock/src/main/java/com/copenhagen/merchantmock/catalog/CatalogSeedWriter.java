package com.copenhagen.merchantmock.catalog;

import java.util.List;

/**
 * 시연 카탈로그를 products 에 쓰는 경계. 구현은 {@link JdbcCatalogSeedWriter} 다.
 *
 * <p>{@link ProductRepository} 와 나눠 둔 이유는 읽기와 쓰기의 수명이 다르기 때문이다.
 * 읽기는 매 요청 경로에 있고 쓰기는 기동 때 한 번뿐이다. 그리고 ProductRepository 는
 * 메서드가 하나여야 테스트가 람다로 CatalogStore 를 채울 수 있다.
 */
@FunctionalInterface
public interface CatalogSeedWriter {

    /** 시드를 upsert 하고 쓴 건수를 돌려준다. external_id 가 같으면 덮어쓴다. */
    int upsertAll(List<CatalogSeed.Entry> entries);
}
