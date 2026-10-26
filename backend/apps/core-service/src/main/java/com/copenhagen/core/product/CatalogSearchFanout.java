package com.copenhagen.core.product;

import com.copenhagen.core.common.logging.CoreLogContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.MDC;

/** 내부 카탈로그 조회만 병렬화한다. 변경 요청과 앱 API에는 적용하지 않는다. */
public final class CatalogSearchFanout implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final Duration budget;

    public CatalogSearchFanout(int concurrency, int queueCapacity, Duration budget) {
        if (concurrency < 1 || queueCapacity < 1 || budget.isNegative() || budget.isZero()) {
            throw new IllegalArgumentException("Catalog search limits must be positive");
        }
        this.budget = budget;
        this.executor = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().name("catalog-search-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    public CatalogSearchResult search(List<MerchantCatalogClient> clients, String query, Long min, Long max) {
        long deadline = System.nanoTime() + budget.toNanos();
        String requestId = CoreLogContext.requestIdOrNew();
        List<Future<List<MerchantCatalogClient.CatalogItem>>> futures = new ArrayList<>();
        List<CatalogSearchItem> items = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        try {
            for (MerchantCatalogClient client : clients) {
                try {
                    futures.add(executor.submit(() -> {
                        MDC.put(CoreLogContext.REQUEST_ID, requestId);
                        try {
                            return client.search(query, max, "KRW");
                        } finally {
                            MDC.remove(CoreLogContext.REQUEST_ID);
                        }
                    }));
                } catch (RejectedExecutionException exception) {
                    futures.add(null);
                }
            }
            for (int i = 0; i < clients.size(); i++) {
                MerchantCatalogClient client = clients.get(i);
                Future<List<MerchantCatalogClient.CatalogItem>> future = futures.get(i);
                if (future == null) {
                    failed.add(client.merchantId());
                    continue;
                }
                try {
                    List<MerchantCatalogClient.CatalogItem> result = future.get(
                            Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    for (MerchantCatalogClient.CatalogItem item : result) {
                        if ("KRW".equals(item.price().currency()) && (min == null || item.price().amount() >= min)) {
                            items.add(new CatalogSearchItem(client.merchantId(), client.merchantName(),
                                    item.productId(), item.title(), item.price().amount(), "KRW",
                                    item.availability(), item.imageUrl()));
                        }
                    }
                } catch (ExecutionException | TimeoutException exception) {
                    failed.add(client.merchantId());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    failed.add(client.merchantId());
                }
            }
            return new CatalogSearchResult(List.copyOf(items), List.copyOf(failed));
        } finally {
            for (Future<?> future : futures) {
                if (future != null && !future.isDone()) {
                    future.cancel(true);
                }
            }
            executor.purge();
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
