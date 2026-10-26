package com.copenhagen.merchantmock.catalog;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.copenhagen.merchantmock.support.MerchantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 카탈로그 풀을 몰 셋의 products 행으로 적재한다(DESIGN.md 5.7절 2026-09-21 개정, ADR 0011).
 *
 * <p>풀 파일 하나를 읽고 몰마다 {@link MallVariation} 으로 id·가격·품절을 계산해 upsert 한다. 풀 sha256·몰 목록·
 * limit 이 지난 적재({@link CatalogPoolState})와 같으면 건너뛴다 — 27만 행을 기동마다 다시 쓰지 않기 위해서다.
 *
 * <p>{@code merchant.catalog.pool-file} 이 비어 있으면 아무 일도 하지 않는다. 테스트와 CI 는 V3 fixture 5건만
 * 본다. 파일이 설정됐는데 없거나 깨졌으면 기동을 세운다 — 시연에 상품이 없는 채로 뜨는 것보다 낫다.
 *
 * <p>줄어든 상품은 지우지 않는다. {@code order_items.product_id} FK 가 걸려 있고 풀은 늘어나기만 한다.
 */
@Component
public class CatalogSeedLoader {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogSeedLoader.class);

    /** 한 번에 upsert 하는 행 수. pgjdbc 의 reWriteBatchedInserts 가 이 묶음을 다중 VALUES 하나로 다시 쓴다. */
    static final int BATCH_SIZE = 1000;

    private final MerchantProperties properties;
    private final CatalogPoolReader reader;
    private final MallVariation variation;
    private final CatalogSeedWriter writer;
    private final CatalogPoolStateRepository stateRepository;

    public CatalogSeedLoader(
            MerchantProperties properties,
            CatalogPoolReader reader,
            MallVariation variation,
            CatalogSeedWriter writer,
            CatalogPoolStateRepository stateRepository) {
        this.properties = properties;
        this.reader = reader;
        this.variation = variation;
        this.writer = writer;
        this.stateRepository = stateRepository;
    }

    /** 설정돼 있고 바뀐 것이 있으면 적재하고 쓴 행 수를, 아니면 0 을 돌려준다. */
    public int loadIfConfigured() {
        MerchantProperties.Catalog catalog = properties.catalog();
        if (catalog == null || !catalog.hasPoolFile()) {
            LOG.info("카탈로그 풀 없음. merchant.catalog.pool-file 이 비어 있다");
            return 0;
        }
        Instant started = Instant.now();
        CatalogPool pool = reader.read(catalog.poolFile(), catalog.poolLimit());
        String malls = mallList();
        Optional<CatalogPoolState> previous = stateRepository.find();
        if (previous.isPresent() && previous.get().matches(pool.sha256(), malls, catalog.poolLimit())) {
            LOG.info("카탈로그 풀 적재 건너뜀. 같은 풀({}…)·몰({})·limit({}) 으로 {} 에 적재됐다",
                    pool.sha256().substring(0, 12), malls, catalog.poolLimit(), previous.get().loadedAt());
            return 0;
        }

        int total = 0;
        for (MerchantProperties.Mall mall : properties.malls()) {
            total += write(mall.id(), pool.products());
        }
        stateRepository.save(new CatalogPoolState(
                pool.sha256(), malls, catalog.poolLimit(), pool.products().size(), Instant.now()));
        LOG.info("카탈로그 풀 적재 완료. 풀 {}건(파일 {}건, limit {}), 몰 {}개, 행 {}건, {}ms, 출처 {}",
                pool.products().size(), pool.totalCount(), catalog.poolLimit(), properties.malls().size(), total,
                Duration.between(started, Instant.now()).toMillis(), catalog.poolFile());
        return total;
    }

    private int write(String mall, List<CatalogPool.PoolProduct> products) {
        int written = 0;
        List<CatalogSeed.Entry> batch = new ArrayList<>(BATCH_SIZE);
        for (CatalogPool.PoolProduct product : products) {
            batch.add(variation.derive(mall, product));
            if (batch.size() == BATCH_SIZE) {
                written += writer.upsertAll(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            written += writer.upsertAll(batch);
        }
        return written;
    }

    private String mallList() {
        StringBuilder joined = new StringBuilder();
        for (MerchantProperties.Mall mall : properties.malls()) {
            if (!joined.isEmpty()) {
                joined.append(',');
            }
            joined.append(mall.id());
        }
        return joined.toString();
    }
}
