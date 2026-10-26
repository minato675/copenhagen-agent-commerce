package com.copenhagen.merchantmock.catalog;

import java.util.List;

/**
 * 상품 조회 경계. 구현은 {@link JdbcProductRepository} 다.
 *
 * <p>인터페이스로 둔 이유는 테스트다. {@link CatalogStore} 를 DB 없이 시드 목록만으로 채울 수 있다.
 */
@FunctionalInterface
public interface ProductRepository {

    List<Product> findAll();
}
