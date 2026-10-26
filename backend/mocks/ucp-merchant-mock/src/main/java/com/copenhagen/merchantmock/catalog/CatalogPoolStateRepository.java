package com.copenhagen.merchantmock.catalog;

import java.util.Optional;

/** 적재 상태 한 행을 읽고 쓰는 경계. 구현은 {@link JdbcCatalogPoolStateRepository}, 테스트는 인메모리로 바꾼다. */
public interface CatalogPoolStateRepository {

    Optional<CatalogPoolState> find();

    void save(CatalogPoolState state);
}
