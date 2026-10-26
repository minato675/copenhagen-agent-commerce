package com.copenhagen.merchantmock.catalog;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 시드를 products 에 upsert 한다. JdbcTemplate 만 쓰고 JPA 를 쓰지 않는다(DESIGN.md 4절).
 *
 * <p>{@code (merchant_id, external_id)} 충돌에 덮어쓰기로 간다(393). 시드 생성기가 id 를 몰과 external_id 에서
 * 결정적으로 만들기 때문에 다시 돌려도 같은 행을 갱신한다(5.7절). 기동할 때마다 행이 늘지 않는다.
 *
 * <p>기존 상품을 지우지 않는다. V3 fixture 5건은 그대로 남는다. 몰 셋의 파일이 한 DB 에 겹쳐 들어가는 것이
 * 이제 정상이다 — 몰이 다르면 같은 external_id 라도 다른 행이다.
 */
@Repository
public class JdbcCatalogSeedWriter implements CatalogSeedWriter {

    private static final String UPSERT = """
            INSERT INTO products
                (id, merchant_id, external_id, title, category, brand, image_url,
                 base_price, currency, status, variants_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (merchant_id, external_id) DO UPDATE
                    SET title         = EXCLUDED.title,
                        category      = EXCLUDED.category,
                        brand         = EXCLUDED.brand,
                        image_url     = EXCLUDED.image_url,
                        base_price    = EXCLUDED.base_price,
                        currency      = EXCLUDED.currency,
                        status        = EXCLUDED.status,
                        variants_json = EXCLUDED.variants_json,
                        updated_at    = now()
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcCatalogSeedWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int upsertAll(List<CatalogSeed.Entry> entries) {
        if (entries.isEmpty()) {
            return 0;
        }
        List<Object[]> batch = new ArrayList<>(entries.size());
        for (CatalogSeed.Entry entry : entries) {
            batch.add(new Object[] {
                    entry.id(),
                    entry.merchantId(),
                    entry.externalId(),
                    entry.title(),
                    entry.category(),
                    entry.brand(),
                    entry.imageUrl(),
                    entry.basePrice(),
                    entry.currency(),
                    entry.status(),
                    entry.variantsJson(),
            });
        }
        int[] written = jdbcTemplate.batchUpdate(UPSERT, batch);
        return written.length;
    }
}
