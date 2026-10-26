package com.copenhagen.merchantmock.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 카탈로그 인메모리 스냅샷. 기동 때 전량을 올리고 몰별로 색인한다(DESIGN.md 13절).
 *
 * <p>스냅샷은 통째로 바꾼다. 요청은 자기가 잡은 스냅샷 하나만 보므로 적재 중에도 반쪽을 보지 않는다.
 *
 * <p>2026-09-21 개정. 풀이 몰당 최대 9만 건이라 검색용 사전 계산({@link SearchDoc})을 여기서 한 번 만든다.
 * 요청마다 소문자 변환과 낱말 분리를 하면 그것이 검색 시간의 대부분이다.
 *
 * <p>2026-09-22 개정(ADR 0013). 퍼지 백오프용 제목 낱말 사전({@link JamoBigrams})도 여기서 한 번 만든다. 몰 셋이 같은
 * 상품 본문을 나눠 쓰므로 사전도 한 벌이다.
 */
@Component
public class CatalogStore {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogStore.class);

    private final ProductRepository repository;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());

    public CatalogStore(ProductRepository repository) {
        this.repository = repository;
    }

    public void load() {
        reload();
    }

    public void reload() {
        List<Product> products = repository.findAll();
        Snapshot next = Snapshot.of(products);
        snapshot.set(next);
        LOG.info("카탈로그 로딩 완료. 몰 {}개, 상품 {}건, SKU {}건, 제목 낱말 사전 {}개",
                next.malls().size(), products.size(), next.skuCount(), next.vocabulary().size());
    }

    public List<Product> all(String merchantId) {
        return snapshot.get().mall(merchantId).ordered();
    }

    /** {@link #all} 과 같은 순서·같은 길이. i 번째 상품의 검색 사전 계산이 i 번째다. */
    public List<SearchDoc> searchDocs(String merchantId) {
        return snapshot.get().mall(merchantId).docs();
    }

    /** 제목 낱말 사전(두 글자 이상). 몰 공용. 퍼지 백오프가 쓴다. */
    public JamoBigrams vocabulary() {
        return snapshot.get().vocabulary();
    }

    public Optional<Product> byExternalId(String merchantId, String externalId) {
        return Optional.ofNullable(snapshot.get().mall(merchantId).byExternalId().get(externalId));
    }

    public Optional<Product.Variant> bySku(String merchantId, String sku) {
        return Optional.ofNullable(snapshot.get().mall(merchantId).bySku().get(sku));
    }

    public int size() {
        return snapshot.get().malls().values().stream().mapToInt(index -> index.ordered().size()).sum();
    }

    private record Snapshot(Map<String, MallIndex> malls, JamoBigrams vocabulary) {

        static Snapshot empty() {
            return new Snapshot(Map.of(), JamoBigrams.empty());
        }

        static Snapshot of(List<Product> products) {
            Map<String, List<Product>> grouped = new LinkedHashMap<>();
            for (Product product : products) {
                grouped.computeIfAbsent(product.merchantId(), key -> new ArrayList<>()).add(product);
            }
            // 같은 상품은 몰이 달라도 제목·브랜드·카테고리가 같다(externalId 가 열쇠). 검색 사전 계산을 한 번만 만들어
            // 몰 셋이 나눠 쓴다 — 27만 행에서 소문자 복사본 셋을 하나로 줄인다(13절).
            Map<String, SearchDoc> sharedDocs = new HashMap<>();
            Map<String, MallIndex> malls = new LinkedHashMap<>();
            grouped.forEach((merchantId, list) -> malls.put(merchantId, MallIndex.of(list, sharedDocs)));
            Set<String> words = new HashSet<>();
            for (SearchDoc doc : sharedDocs.values()) {
                words.addAll(doc.titleWords());
            }
            return new Snapshot(Map.copyOf(malls), JamoBigrams.of(words));
        }

        MallIndex mall(String merchantId) {
            return malls.getOrDefault(merchantId, MallIndex.EMPTY);
        }

        int skuCount() {
            return malls.values().stream().mapToInt(index -> index.bySku().size()).sum();
        }
    }

    private record MallIndex(
            List<Product> ordered,
            List<SearchDoc> docs,
            Map<String, Product> byExternalId,
            Map<String, Product.Variant> bySku) {

        static final MallIndex EMPTY = new MallIndex(List.of(), List.of(), Map.of(), Map.of());

        static MallIndex of(List<Product> products, Map<String, SearchDoc> sharedDocs) {
            List<SearchDoc> docs = new ArrayList<>(products.size());
            Map<String, Product> byExternalId = new LinkedHashMap<>();
            Map<String, Product.Variant> bySku = new LinkedHashMap<>();
            for (Product product : products) {
                docs.add(sharedDocs.computeIfAbsent(product.externalId(), key -> SearchDoc.of(product)));
                byExternalId.put(product.externalId(), product);
                for (Product.Variant variant : product.variants()) {
                    bySku.put(variant.sku(), variant);
                }
            }
            return new MallIndex(List.copyOf(products), List.copyOf(docs), Map.copyOf(byExternalId),
                    Map.copyOf(bySku));
        }
    }
}
