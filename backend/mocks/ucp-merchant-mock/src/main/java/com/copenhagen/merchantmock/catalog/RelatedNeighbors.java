package com.copenhagen.merchantmock.catalog;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;

/**
 * 연관 상품 e5nb 이웃 파일(DESIGN.md 6.8.1절). {@code tools/build_related.py} 가 만든다.
 *
 * <pre>
 * gzip( 마법 "CPHREL01"(8) · 풀 sha256 16진(64, ASCII) · n(int32) · k(int32) · n × k int32 이웃 풀 순번(-1 채움) ), 빅엔디언
 * </pre>
 *
 * <p>머리의 풀 sha 가 지금 풀과 다르면 순번이 가리키는 상품이 달라진다. 그래서 읽을 때 대조하고, 다르면 {@link Invalid} 를 던져
 * 부르는 쪽이 이웃 없이(rrf2) 돌게 한다. 앱을 죽이지 않는다.
 */
final class RelatedNeighbors {

    static final byte[] MAGIC = "CPHREL01".getBytes(StandardCharsets.US_ASCII);

    private final String sha256;
    private final int size;
    private final int width;
    private final int[] table;

    private RelatedNeighbors(String sha256, int size, int width, int[] table) {
        this.sha256 = sha256;
        this.size = size;
        this.width = width;
        this.table = table;
    }

    /** 파일을 쓸 수 없는 이유. 로그 한 줄에 그대로 싣는다. */
    static final class Invalid extends Exception {

        private static final long serialVersionUID = 1L;

        Invalid(String message) {
            super(message);
        }
    }

    /**
     * @param in        gzip 파일 바이트
     * @param poolSha   지금 풀 파일의 sha256(16진)
     * @param poolSize  지금 풀 상품 수
     */
    static RelatedNeighbors read(byte[] in, String poolSha, int poolSize) throws Invalid {
        try (InputStream gz = new GZIPInputStream(new ByteArrayInputStream(in));
             DataInputStream data = new DataInputStream(new BufferedInputStream(gz, 1 << 16))) {
            byte[] magic = new byte[MAGIC.length];
            data.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new Invalid("이웃 파일 마법이 다르다");
            }
            byte[] sha = new byte[64];
            data.readFully(sha);
            String fileSha = new String(sha, StandardCharsets.US_ASCII);
            if (!fileSha.equals(poolSha)) {
                throw new Invalid("이웃 파일의 풀 sha " + abbreviate(fileSha) + " 가 지금 풀 " + abbreviate(poolSha)
                        + " 와 다르다. tools/build_related.py 를 다시 돌린다");
            }
            int n = data.readInt();
            int k = data.readInt();
            if (n != poolSize) {
                throw new Invalid("이웃 파일 문서 수 " + n + " 가 풀 " + poolSize + " 와 다르다");
            }
            if (k <= 0 || k > 1_000) {
                throw new Invalid("이웃 파일 k 가 이상하다: " + k);
            }
            int[] table = new int[Math.multiplyExact(n, k)];
            for (int i = 0; i < table.length; i++) {
                int v = data.readInt();
                if (v < -1 || v >= n) {
                    throw new Invalid("이웃 순번이 범위 밖이다: " + v);
                }
                table[i] = v;
            }
            if (data.read() != -1) {
                throw new Invalid("이웃 파일 끝에 남는 바이트가 있다");
            }
            return new RelatedNeighbors(sha256(in), n, k, table);
        } catch (IOException e) {
            throw new Invalid("이웃 파일을 읽지 못했다: " + e.getMessage());
        }
    }

    /** 씨앗 {@code seed} 의 이웃 풀 순번. 코사인 내림차순, -1 은 뺀다. 최대 {@code k} 개. */
    int[] of(int seed, int k) {
        int base = seed * width;
        int count = 0;
        int limit = Math.min(k, width);
        while (count < limit && table[base + count] >= 0) {
            count++;
        }
        return Arrays.copyOfRange(table, base, base + count);
    }

    int size() {
        return size;
    }

    int width() {
        return width;
    }

    /** 파일 자체(gzip 바이트)의 sha256. 기동 로그용. */
    String sha256() {
        return sha256;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String abbreviate(String sha) {
        return sha.length() > 12 ? sha.substring(0, 12) + "…" : sha;
    }
}
