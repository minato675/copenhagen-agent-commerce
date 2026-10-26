package com.copenhagen.core.product;

import com.copenhagen.core.common.logging.CoreLogContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogSearchFanoutTest {
    @Test
    void search_independentMerchants_startTogetherAndPreserveOrderAndRequestId() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        List<MerchantCatalogClient> clients = List.of(client("a"), client("b"));
        for (MerchantCatalogClient client : clients) {
            when(client.search("query", null, "KRW")).thenAnswer(invocation -> {
                entered.countDown();
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                assertEquals("test-request", MDC.get(CoreLogContext.REQUEST_ID));
                return List.of(item(client.merchantId()));
            });
        }
        MDC.put(CoreLogContext.REQUEST_ID, "test-request");
        try (CatalogSearchFanout fanout = new CatalogSearchFanout(2, 2, Duration.ofSeconds(2))) {
            CatalogSearchResult result = fanout.search(clients, "query", null, null);
            assertEquals(List.of("a", "b"), result.items().stream().map(CatalogSearchItem::merchantId).toList());
            assertEquals(List.of(), result.failedMerchants());
        } finally {
            MDC.remove(CoreLogContext.REQUEST_ID);
        }
    }

    @Test
    void search_slowMerchant_keepsCompletedResultsAtGlobalDeadline() throws Exception {
        MerchantCatalogClient slow = client("slow");
        MerchantCatalogClient fast = client("fast");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(slow.search("query", null, "KRW")).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return List.of(item("slow"));
        });
        when(fast.search("query", null, "KRW")).thenAnswer(invocation -> {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            return List.of(item("fast"));
        });
        try (CatalogSearchFanout fanout = new CatalogSearchFanout(2, 2, Duration.ofMillis(200))) {
            CatalogSearchResult result = fanout.search(List.of(slow, fast), "query", null, null);
            assertEquals(List.of("slow"), result.failedMerchants());
            assertEquals("fast", result.items().getFirst().merchantId());
        } finally {
            release.countDown();
        }
    }

    @Test
    void search_closedPool_returnsPartialFailureWithoutCallerThreadExecution() {
        CatalogSearchFanout fanout = new CatalogSearchFanout(1, 1, Duration.ofSeconds(1));
        fanout.close();
        CatalogSearchResult result = fanout.search(List.of(client("a")), "query", null, null);
        assertTrue(result.items().isEmpty());
        assertEquals(List.of("a"), result.failedMerchants());
    }

    @Test
    void search_controlledLatency_reportsSequentialAndParallelSamples() throws Exception {
        List<MerchantCatalogClient> clients = List.of(client("a"), client("b"), client("c"));
        for (MerchantCatalogClient client : clients) {
            when(client.search("query", null, "KRW")).thenAnswer(invocation -> {
                // 벤치마크용 동일 지연. 동시성의 정합성은 latch 테스트에서 검증한다.
                Thread.sleep(25);
                return List.of(item(client.merchantId()));
            });
        }
        List<Long> sequential = new ArrayList<>();
        List<Long> parallel = new ArrayList<>();
        try (CatalogSearchFanout fanout = new CatalogSearchFanout(3, 3, Duration.ofSeconds(2))) {
            for (int i = 0; i < 31; i++) {
                long start = System.nanoTime();
                for (MerchantCatalogClient client : clients) {
                    client.search("query", null, "KRW");
                }
                long serialMs = (System.nanoTime() - start) / 1_000_000;
                start = System.nanoTime();
                assertEquals(3, fanout.search(clients, "query", null, null).items().size());
                long parallelMs = (System.nanoTime() - start) / 1_000_000;
                if (i == 0) {
                    System.out.printf("FIXTURE cold sequentialMs=%d parallelMs=%d%n", serialMs, parallelMs);
                } else {
                    sequential.add(serialMs);
                    parallel.add(parallelMs);
                }
            }
        }
        Collections.sort(sequential);
        Collections.sort(parallel);
        System.out.printf("FIXTURE warm n=30 sequential p50=%d p95=%d; parallel p50=%d p95=%d%n",
                sequential.get(14), sequential.get(28), parallel.get(14), parallel.get(28));
    }

    private MerchantCatalogClient client(String id) {
        MerchantCatalogClient client = mock(MerchantCatalogClient.class);
        when(client.merchantId()).thenReturn(id);
        when(client.merchantName()).thenReturn(id);
        return client;
    }

    private MerchantCatalogClient.CatalogItem item(String id) {
        return new MerchantCatalogClient.CatalogItem(id, id,
                new MerchantCatalogClient.CatalogPrice(100, "KRW"), "IN_STOCK");
    }
}
