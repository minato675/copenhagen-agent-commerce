package com.copenhagen.mcp.qa;

import com.copenhagen.mcp.cart.CartEditSchemas;
import com.copenhagen.mcp.cart.CartSchemas;
import com.copenhagen.mcp.catalog.ProductDetailRequest;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 로컬 QA에서만 Core 원장을 대체한다. 운영 jar에 포함되지 않는 합성 데이터 저장소. */
final class QaCartStore implements AutoCloseable {

    private final QaCatalogStore catalog;
    private final Path file;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private Map<String, Object> users;

    QaCartStore(QaCatalogStore catalog, Path file) throws IOException {
        this.catalog = catalog;
        this.file = file.toAbsolutePath();
        Files.createDirectories(this.file.getParent());
        lockChannel = FileChannel.open(this.file.resolveSibling(this.file.getFileName() + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
            if (lock == null) {
                throw new IOException("QA cart ledger already open");
            }
            if (Files.exists(this.file)) {
                if (Files.size(this.file) > 30_000_000) {
                    throw new IOException("QA cart ledger exceeds limit");
                }
                Map<String, Object> saved = McpJsonDefaults.getMapper()
                        .readValue(Files.readAllBytes(this.file), new TypeRef<>() { });
                if (!Integer.valueOf(1).equals(saved.get("format")) || !"QA".equals(saved.get("mode"))) {
                    throw new IOException("Invalid QA cart ledger format");
                }
                users = map(saved.get("users"));
                for (Object value : users.values()) {
                    if (!McpJsonDefaults.getSchemaValidator()
                            .validate(CartSchemas.CART, map(value).get("cart")).valid()) {
                        throw new IOException("Invalid QA cart snapshot");
                    }
                }
            } else {
                users = new LinkedHashMap<>();
            }
        } catch (IOException | RuntimeException exception) {
            lockChannel.close();
            throw exception;
        }
    }

    synchronized Map<String, Object> get(String user) throws IOException {
        if (!users.containsKey(user)) {
            Map<String, Object> next = copy(users);
            next.put(user, Map.of("cart", Map.of("cartId", "cart-" + UUID.randomUUID(), "version", 0,
                    "lines", List.of(), "approvalState", "REVALIDATION_REQUIRED"), "receipts", Map.of()));
            save(next);
        }
        return copy(map(map(users.get(user)).get("cart")));
    }

    synchronized Map<String, Object> view(String user) throws IOException {
        Map<String, Object> cart = get(user);
        for (Map<String, Object> line : QaCatalogStore.maps(cart.get("lines"))) {
            line.put("merchantName", QaCatalogStore.merchantName((String) line.get("merchantId"),
                    (String) line.get("merchantName")));
        }
        return QaCartQuote.view(cart, catalog.failedMerchants());
    }

    synchronized Map<String, Object> prepareHandoff(String user, String connection,
            Map<String, Object> request, Instant now) throws IOException {
        if (!McpJsonDefaults.getSchemaValidator()
                .validate(com.copenhagen.mcp.handoff.HandoffSchemas.INPUT, request).valid()) {
            throw new IllegalArgumentException("Invalid handoff request");
        }
        Map<String, Object> cart = get(user);
        if (!cart.get("cartId").equals(request.get("cartId"))) {
            throw new SecurityException("Cart not owned by authenticated user");
        }
        Map<String, Object> next = copy(users);
        Map<String, Object> account = map(next.get(user));
        Map<String, Object> receipts = map(account.computeIfAbsent("handoffs", unused -> new LinkedHashMap<>()));
        Map<String, Object> output = QaPurchaseHandoff.prepare(cart, receipts, connection, normalized(request), now);
        if (output.get("status").equals("READY")) {
            map(account.computeIfAbsent("purchaseSnapshots", unused -> new LinkedHashMap<>()))
                    .put(output.get("purchaseRef").toString(), cart);
        }
        save(next);
        return copy(output);
    }

    synchronized Map<String, Object> add(String user, Map<String, Object> request) throws IOException {
        return change(user, "ADD", request);
    }

    synchronized Map<String, Object> change(String user, String action, Map<String, Object> request)
            throws IOException {
        Map<String, Object> schema = switch (action) {
            case "ADD" -> CartSchemas.ADD_INPUT;
            case "UPDATE" -> CartEditSchemas.UPDATE_INPUT;
            case "DELETE" -> CartEditSchemas.DELETE_INPUT;
            default -> throw new IllegalArgumentException("Invalid QA cart action");
        };
        if (!McpJsonDefaults.getSchemaValidator().validate(schema, request).valid()) {
            throw new IllegalArgumentException("Invalid QA cart request");
        }
        Map<String, Object> cart = get(user);
        if (!cart.get("cartId").equals(request.get("cartId"))) {
            throw new SecurityException("Cart not owned by authenticated user");
        }
        Map<String, Object> account = map(users.get(user));
        Map<String, Object> receipts = map(account.get("receipts"));
        String key = (String) request.get("idempotencyKey");
        if (receipts.containsKey(key)) {
            Map<String, Object> receipt = map(receipts.get(key));
            return normalized(map(receipt.get("request"))).equals(normalized(request))
                    && action.equals(map(receipt.get("result")).get("action"))
                    ? copy(map(receipt.get("result")))
                    : result(action, request, cart, "CONFLICT", "KEY_CONFLICT", null);
        }
        if (receipts.size() >= 1000) {
            return result(action, request, cart, "REJECTED", "LIMIT_EXCEEDED", null);
        }
        Map<String, Object> response;
        if (number(request.get("expectedVersion")) != number(cart.get("version"))) {
            response = result(action, request, cart, "CONFLICT", "VERSION_CONFLICT", null);
        } else {
            response = mutate(action, request, cart);
        }
        Map<String, Object> next = copy(users);
        Map<String, Object> updated = map(next.get(user));
        updated.put("cart", response.get("cart"));
        map(updated.get("receipts")).put(key, Map.of("request", request, "result", response));
        save(next);
        return copy(response);
    }

    private Map<String, Object> mutate(String action, Map<String, Object> request, Map<String, Object> cart) {
        if (number(cart.get("version")) >= CartSchemas.MAX_VERSION) {
            return result(action, request, cart, "REJECTED", "LIMIT_EXCEEDED", null);
        }
        List<Map<String, Object>> lines = new ArrayList<>(QaCatalogStore.maps(cart.get("lines")));
        Map<String, Object> target = lines.stream()
                .filter(line -> line.get("lineId").equals(request.get("lineId"))).findFirst().orElse(null);
        if (!action.equals("ADD") && target == null) {
            return result(action, request, cart, "REJECTED", "NOT_FOUND", null);
        }
        if (action.equals("DELETE")) {
            lines.remove(target);
            cart.put("lines", lines);
            cart.put("version", number(cart.get("version")) + 1);
            cart.put("approvalState", "REVALIDATION_REQUIRED");
            return result(action, request, cart, "APPLIED", "NONE", (String) request.get("lineId"));
        }
        Map<String, Object> selection = new LinkedHashMap<>(request);
        if (target != null) {
            selection.put("productId", target.get("productId"));
            selection.put("merchantId", target.get("merchantId"));
        }
        return addLine(action, request, selection, cart, target);
    }

    private Map<String, Object> addLine(String action, Map<String, Object> request,
            Map<String, Object> selectionRequest,
            Map<String, Object> cart, Map<String, Object> target) {
        Map<String, Object> detail;
        try {
            detail = catalog.detail(new ProductDetailRequest((String) selectionRequest.get("productId"),
                    (String) selectionRequest.get("merchantId"), (String) request.get("optionId")));
        } catch (RuntimeException exception) {
            return result(action, request, cart, "REJECTED", "UNAVAILABLE", null);
        }
        if (detail.get("product") == null) {
            return result(action, request, cart, "REJECTED", "NOT_FOUND", null);
        }
        Map<String, Object> product = map(detail.get("product"));
        Map<String, Object> offer = map(product.get("offer"));
        String selection = (String) product.get("selectionStatus");
        if (selection.equals("REQUIRED") || selection.equals("INVALID")) {
            return result(action, request, cart, "REJECTED",
                    selection.equals("REQUIRED") ? "OPTION_REQUIRED" : "INVALID_OPTION", null);
        }
        Map<String, Object> variant = QaCatalogStore.maps(product.get("variants")).stream()
                .filter(value -> value.get("optionId").equals(request.get("optionId"))).findFirst().orElse(Map.of());
        if (List.of("OUT_OF_STOCK", "DISCONTINUED").contains(offer.get("availability"))
                || Boolean.FALSE.equals(variant.get("purchaseAllowed"))) {
            return result(action, request, cart, "REJECTED", "UNAVAILABLE", null);
        }
        List<Map<String, Object>> lines = new ArrayList<>(QaCatalogStore.maps(cart.get("lines")));
        Map<String, Object> previous = lines.stream()
                .filter(value -> value.get("productId").equals(selectionRequest.get("productId"))
                && value.get("merchantId").equals(selectionRequest.get("merchantId"))
                && value.get("optionId").equals(request.get("optionId"))).findFirst().orElse(null);
        if (target != null && previous != null && !target.get("lineId").equals(previous.get("lineId"))) {
            return result(action, request, cart, "REJECTED", "DUPLICATE_SELECTION", null);
        }
        if (target != null) {
            previous = target;
        }
        long quantity = number(request.get("quantity"))
                + (previous == null || action.equals("UPDATE") ? 0 : number(previous.get("quantity")));
        if (quantity > 99 || (previous == null && lines.size() >= 100)
                || number(cart.get("version")) >= CartSchemas.MAX_VERSION) {
            return result(action, request, cart, "REJECTED", "LIMIT_EXCEEDED", null);
        }
        if (product.get("stockQuantity") != null && quantity > number(product.get("stockQuantity"))) {
            return result(action, request, cart, "REJECTED", "INSUFFICIENT_STOCK", null);
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("lineId", previous == null ? "line-" + UUID.randomUUID() : previous.get("lineId"));
        for (String field : List.of("productId", "name", "imageUrl")) {
            line.put(field, product.get(field));
        }
        line.put("merchantId", selectionRequest.get("merchantId"));
        line.put("merchantName", offer.get("merchantName"));
        line.put("optionId", request.get("optionId"));
        line.put("optionLabel", variant.getOrDefault("label", ""));
        line.put("quantity", quantity);
        line.put("unitPrice", offer.get("price"));
        line.put("lineTotal", offer.get("price") == null ? null : number(offer.get("price")) * quantity);
        line.put("currency", offer.get("currency"));
        line.put("priceStatus", offer.get("price") == null ? "UNKNOWN" : "ESTIMATED");
        if (line.get("lineTotal") != null && number(line.get("lineTotal")) > CartSchemas.MAX_AMOUNT) {
            return result(action, request, cart, "REJECTED", "LIMIT_EXCEEDED", null);
        }
        lines.remove(previous);
        lines.add(line);
        cart.put("lines", lines);
        cart.put("version", number(cart.get("version")) + 1);
        cart.put("approvalState", "REVALIDATION_REQUIRED");
        return result(action, request, cart, "APPLIED", "NONE", (String) line.get("lineId"));
    }

    private static Map<String, Object> result(String action, Map<String, Object> request, Map<String, Object> cart,
            String status, String reason, String lineId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "cart.mutation");
        result.put("mode", "QA");
        result.put("action", action);
        result.put("request", request);
        result.put("status", status);
        result.put("reason", reason);
        result.put("cart", cart);
        result.put("affectedLineId", lineId);
        result.put("checkedAt", Instant.now().toString());
        return result;
    }

    private void save(Map<String, Object> next) throws IOException {
        byte[] bytes = McpJsonDefaults.getMapper().writeValueAsBytes(Map.of("format", 1, "mode", "QA", "users", next));
        if (bytes.length > 30_000_000) {
            throw new IOException("QA cart ledger exceeds limit");
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".pending");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        users = next;
    }

    private static Map<String, Object> normalized(Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        input.forEach((key, value) -> result.put(key, value instanceof Number n ? n.longValue() : value));
        return result;
    }

    private static Map<String, Object> copy(Map<String, Object> source) throws IOException {
        return McpJsonDefaults.getMapper().readValue(McpJsonDefaults.getMapper().writeValueAsBytes(source),
                new TypeRef<>() { });
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @Override
    public synchronized void close() throws IOException {
        lock.release();
        lockChannel.close();
    }
}
