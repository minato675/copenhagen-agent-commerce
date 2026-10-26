package com.copenhagen.mcp.core;

import com.copenhagen.mcp.tool.ToolErrorCode;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** 헤더의 Content-Length와 무관하게 실제 수신 바이트 수를 제한한다. */
public final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

    private final int limit;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private Flow.Subscription subscription;

    public LimitedBodySubscriber(int limit) {
        this.limit = limit;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription incoming) {
        if (subscription != null) {
            incoming.cancel();
            return;
        }
        subscription = incoming;
        subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        for (ByteBuffer buffer : buffers) {
            if (buffer.remaining() > limit - output.size()) {
                subscription.cancel();
                body.completeExceptionally(new CoreClientException(ToolErrorCode.CORE_INVALID_RESPONSE));
                return;
            }
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            output.writeBytes(bytes);
        }
        subscription.request(1);
    }

    @Override
    public void onError(Throwable throwable) {
        body.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
        body.complete(output.toByteArray());
    }
}
