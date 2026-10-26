package com.copenhagen.merchantmock.catalog;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 기동이 끝난 뒤 카탈로그를 메모리에 올린다.
 *
 * <p>Flyway 마이그레이션이 먼저 끝나야 하므로 생성자나 {@code @PostConstruct} 가 아니라
 * ApplicationReadyEvent 에서 부른다. V3 시드가 들어간 뒤여야 상품이 보인다.
 *
 * <p>순서는 셋이다(DESIGN.md 5.7절 2026-09-21 개정). (1) DB 에 있는 것을 먼저 올린다 — 이전 기동의 상품으로
 * 곧바로 검색이 된다. (2) 풀이 바뀌었으면 적재한다. 27만 행 첫 적재는 분 단위일 수 있고 그동안 검색은 이전
 * 데이터로 답한다. (3) 적재했으면 스냅샷을 다시 올린다. 셋의 순서를 이벤트 리스너 우선순위에 맡기지 않고
 * 한 메서드 안에서 고정한다.
 */
@Component
public class CatalogWarmUp {

    private final CatalogSeedLoader seedLoader;
    private final CatalogStore store;

    public CatalogWarmUp(CatalogSeedLoader seedLoader, CatalogStore store) {
        this.seedLoader = seedLoader;
        this.store = store;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadCatalog() {
        store.load();
        int written = seedLoader.loadIfConfigured();
        if (written > 0) {
            store.reload();
        }
    }
}
