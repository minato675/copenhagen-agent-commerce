package com.copenhagen.merchantmock.catalog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.copenhagen.merchantmock.support.ApiException;
import com.copenhagen.merchantmock.support.Audit;
import com.copenhagen.merchantmock.support.MerchantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/**
 * 연관 상품(DESIGN.md 6.8·6.8.1절, ADR 0014, Jira 511). {@code merchant.catalog.related.enabled=true} 일 때만 빈이 생긴다.
 *
 * <p><b>준비는 기동 뒤 백그라운드다.</b> 풀 파일을 읽어 TF-IDF 색인을 만들고 이웃 파일을 읽는다. 기동 경로에 두면 켰을 때 기동이
 * 느려진다. 준비가 끝나기 전 요청은 503 {@code RELATED_WARMING_UP} 이다.
 *
 * <p><b>실패하면 한 단계씩 내려가고 앱은 죽지 않는다.</b> 이웃 파일을 못 쓰면 rrf2, 색인을 못 만들면 search. 요청 처리 중 예상 못 한
 * 예외는 그 요청만 503 {@code RELATED_UNAVAILABLE} 이다.
 */
@Service
@ConditionalOnProperty(prefix = "merchant.catalog.related", name = "enabled", havingValue = "true")
public class RelatedService {

    private static final Logger LOG = LoggerFactory.getLogger(RelatedService.class);

    private final CatalogStore store;
    private final CatalogService catalog;
    private final MerchantProperties merchants;
    private final RelatedProperties properties;
    private final CatalogPoolReader poolReader;
    private final ResourceLoader resources;
    private final AtomicReference<RelatedEngine> engine = new AtomicReference<>();

    public RelatedService(CatalogStore store, CatalogService catalog, MerchantProperties merchants,
                          RelatedProperties properties, CatalogPoolReader poolReader, ResourceLoader resources) {
        this.store = store;
        this.catalog = catalog;
        this.merchants = merchants;
        this.properties = properties;
        this.poolReader = poolReader;
        this.resources = resources;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        Thread.ofPlatform().name("related-warmup").daemon().start(this::prepare);
    }

    /** 패키지 공개는 테스트가 기다리지 않고 곧바로 준비시키기 위해서다. */
    void prepare() {
        long started = System.nanoTime();
        RelatedProperties.Mode requested = properties.requestedMode();
        List<String> ids = List.of();
        List<String> titles = List.of();
        RelatedIndex index = null;
        RelatedNeighbors neighbors = null;
        String poolSha = "-";
        String reason = "";
        try {
            MerchantProperties.Catalog config = merchants.catalog();
            if (requested != RelatedProperties.Mode.SEARCH && config.hasPoolFile()) {
                CatalogPool pool = poolReader.read(config.poolFile(), 0);
                poolSha = pool.sha256();
                ids = pool.products().stream().map(CatalogPool.PoolProduct::externalId).toList();
                titles = pool.products().stream().map(CatalogPool.PoolProduct::title).toList();
                List<String> categories = pool.products().stream().map(CatalogPool.PoolProduct::category).toList();
                try {
                    index = RelatedIndex.build(titles, categories);
                } catch (RuntimeException | OutOfMemoryError exception) {
                    reason = "TF-IDF 색인을 못 만들었다: " + exception;
                    LOG.error("연관 상품 TF-IDF 색인 실패. search 로 돈다", exception);
                }
                if (index != null && requested == RelatedProperties.Mode.RRF3) {
                    neighbors = loadNeighbors(pool);
                    if (neighbors == null) {
                        reason = "이웃 파일을 못 썼다(위 WARN)";
                    }
                }
            } else if (requested != RelatedProperties.Mode.SEARCH) {
                reason = "풀 파일이 없다(merchant.catalog.pool-file)";
            }
        } catch (RuntimeException exception) {
            reason = "풀을 못 읽었다: " + exception;
            LOG.error("연관 상품 풀 읽기 실패. search 로 돈다", exception);
            ids = List.of();
            titles = List.of();
            index = null;
        }
        RelatedEngine ready = new RelatedEngine(ids, titles, index, neighbors, requested);
        engine.set(ready);
        long millis = (System.nanoTime() - started) / 1_000_000;
        // 힙 증가는 싣지 않는다. 준비 중 GC 가 돌면 음수까지 나와 오해만 부른다 — 힙은 jcmd GC.heap_info 로 잰다(DESIGN.md 6.8.1절)
        LOG.info("연관 상품 준비 완료. 모드 {} → {}{}, 풀 {}건 sha {}, 색인 낱말 {}개·게시 {}건, 이웃 {}, {}ms",
                requested.value(), ready.mode().value(), reason.isEmpty() ? "" : " (" + reason + ")",
                ids.size(), abbreviate(poolSha),
                index == null ? 0 : index.vocabularySize(), index == null ? 0 : index.postings(),
                neighbors == null ? "없음"
                        : neighbors.size() + "×" + neighbors.width() + " sha " + abbreviate(neighbors.sha256()),
                millis);
    }

    private RelatedNeighbors loadNeighbors(CatalogPool pool) {
        try {
            byte[] bytes = resources.getResource(properties.neighborsFile()).getContentAsByteArray();
            return RelatedNeighbors.read(bytes, pool.sha256(), pool.products().size());
        } catch (RelatedNeighbors.Invalid exception) {
            LOG.warn("연관 상품 이웃 파일을 쓰지 않는다. rrf2 로 돈다 — {}", exception.getMessage());
        } catch (IOException | RuntimeException exception) {
            LOG.warn("연관 상품 이웃 파일 {} 을 읽지 못했다. rrf2 로 돈다 — {}", properties.neighborsFile(), exception.toString());
        }
        return null;
    }

    public CatalogMessages.ItemsResponse related(String merchantId, CatalogMessages.RelatedRequest request) {
        merchants.requireMall(merchantId);
        List<String> requestedIds = request == null || request.productIds() == null ? List.of() : request.productIds();
        if (requestedIds.size() > properties.maxSeeds()) {
            throw ApiException.invalidRequest("productIds 는 " + properties.maxSeeds() + "개까지다: " + requestedIds.size());
        }
        int limit = request == null || request.limit() == null ? properties.defaultLimit() : request.limit();
        if (limit < 1 || limit > properties.maxLimit()) {
            throw ApiException.invalidRequest("limit 은 1~" + properties.maxLimit() + " 이다: " + limit);
        }
        RelatedEngine ready = engine.get();
        if (ready == null) {
            throw ApiException.relatedWarmingUp();
        }
        long started = System.nanoTime();
        try {
            // 없는 id 는 조용히 빠진다(lookup 과 같다). 같은 id 가 두 번 오면 한 번만 쓴다
            List<Product> seeds = new ArrayList<>();
            for (String id : new LinkedHashSet<>(requestedIds)) {
                if (id != null) {
                    store.byExternalId(merchantId, id).ifPresent(seeds::add);
                }
            }
            List<CatalogMessages.Item> items = new ArrayList<>();
            if (!seeds.isEmpty()) {
                List<List<String>> lists = new ArrayList<>();
                List<String> seedIds = new ArrayList<>();
                List<String> seedTitles = new ArrayList<>();
                for (Product seed : seeds) {
                    String title = ready.poolTitle(seed.externalId()).orElse(seed.title());
                    seedIds.add(seed.externalId());
                    seedTitles.add(title);
                    lists.add(ready.ranked(seed.externalId(), title, RelatedEngine.RANK_DEPTH,
                            query -> search(merchantId, query), id -> mallTitle(merchantId, id)));
                }
                List<String> picked = ready.merge(lists, seedIds, seedTitles, limit,
                        id -> store.byExternalId(merchantId, id).filter(RelatedService::sellable).isPresent(),
                        id -> mallTitle(merchantId, id));
                for (String id : picked) {
                    store.byExternalId(merchantId, id).map(CatalogMessages.Item::of).ifPresent(items::add);
                }
            }
            Audit.event("catalog.related")
                    .with("merchant", merchantId)
                    .with("seeds", seeds.size())
                    .with("limit", limit)
                    .with("returned", items.size())
                    .with("mode", ready.mode().value())
                    .with("ms", (System.nanoTime() - started) / 1_000_000)
                    .log();
            return new CatalogMessages.ItemsResponse(items);
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // 이 엔드포인트의 최상위 오류 경계다. 예상 못 한 오류라도 다른 API 에 번지지 않게 이 요청만 503 으로 끝낸다(6.8.1절)
            LOG.error("연관 상품 요청 실패 merchant={} seeds={}", merchantId, requestedIds.size(), exception);
            throw ApiException.relatedUnavailable();
        }
    }

    /** 몰의 S19 검색을 그대로 부른다. 검색 코드는 연관 상품 때문에 한 줄도 바뀌지 않는다. */
    private List<String> search(String merchantId, String query) {
        return catalog.search(merchantId, new CatalogMessages.SearchRequest(query, null)).items().stream()
                .map(CatalogMessages.Item::productId)
                .toList();
    }

    private Optional<String> mallTitle(String merchantId, String externalId) {
        return store.byExternalId(merchantId, externalId).map(Product::title);
    }

    /** 몰에 있고 판매 중이며 살 수 있는 옵션이 하나라도 있다. 품절·단종은 추천하지 않는다. */
    private static boolean sellable(Product product) {
        return product.status() == Product.ProductStatus.ACTIVE && product.inStock();
    }

    RelatedProperties.Mode mode() {
        RelatedEngine ready = engine.get();
        return ready == null ? null : ready.mode();
    }

    private static String abbreviate(String sha) {
        return sha.length() > 12 ? sha.substring(0, 12) : sha;
    }
}
