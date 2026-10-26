package com.copenhagen.merchantmock.catalog;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelatedNeighborsTest {

    private static final String POOL_SHA = "a".repeat(64);

    /** tools/build_related.py 와 같은 형식으로 쓴다. */
    private static byte[] file(byte[] magic, String sha, int n, int k, int[] table, boolean trailing)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.write(magic);
            out.write(sha.getBytes(StandardCharsets.US_ASCII));
            out.writeInt(n);
            out.writeInt(k);
            for (int v : table) {
                out.writeInt(v);
            }
            if (trailing) {
                out.writeByte(0);
            }
        }
        return bytes.toByteArray();
    }

    @Test
    @DisplayName("형식이 맞으면 이웃을 순서대로 주고 -1 채움은 뺀다")
    void read_validFile_returnsNeighborsWithoutPadding() throws Exception {
        // given
        byte[] bytes = file(RelatedNeighbors.MAGIC, POOL_SHA, 3, 2, new int[] {1, 2, 0, -1, 1, 0}, false);

        // when
        RelatedNeighbors neighbors = RelatedNeighbors.read(bytes, POOL_SHA, 3);

        // then
        assertEquals(3, neighbors.size());
        assertEquals(2, neighbors.width());
        assertArrayEquals(new int[] {1, 2}, neighbors.of(0, 20));
        assertArrayEquals(new int[] {0}, neighbors.of(1, 20));
        assertArrayEquals(new int[] {1}, neighbors.of(2, 1));
        assertEquals(64, neighbors.sha256().length());
    }

    @Test
    @DisplayName("풀 sha 가 다르면 쓰지 않는다 — 순번이 다른 상품을 가리킨다")
    void read_poolShaMismatch_throwsInvalid() throws Exception {
        // given
        byte[] bytes = file(RelatedNeighbors.MAGIC, "b".repeat(64), 3, 2, new int[6], false);

        // when & then
        RelatedNeighbors.Invalid invalid =
                assertThrows(RelatedNeighbors.Invalid.class, () -> RelatedNeighbors.read(bytes, POOL_SHA, 3));
        assertTrue(invalid.getMessage().contains("tools/build_related.py"));
    }

    @Test
    @DisplayName("마법·문서 수·순번 범위·남는 바이트·gzip 이 아닌 파일은 전부 쓰지 않는다")
    void read_malformedFiles_throwInvalid() throws Exception {
        // given
        byte[] badMagic = file("XXXXXXXX".getBytes(StandardCharsets.US_ASCII), POOL_SHA, 3, 2, new int[6], false);
        byte[] wrongSize = file(RelatedNeighbors.MAGIC, POOL_SHA, 4, 2, new int[8], false);
        byte[] outOfRange = file(RelatedNeighbors.MAGIC, POOL_SHA, 3, 2, new int[] {1, 2, 0, 3, 1, 0}, false);
        byte[] trailing = file(RelatedNeighbors.MAGIC, POOL_SHA, 3, 2, new int[6], true);
        byte[] notGzip = "plain".getBytes(StandardCharsets.US_ASCII);

        // when & then
        for (byte[] bytes : new byte[][] {badMagic, wrongSize, outOfRange, trailing, notGzip}) {
            assertThrows(RelatedNeighbors.Invalid.class, () -> RelatedNeighbors.read(bytes, POOL_SHA, 3));
        }
    }
}
