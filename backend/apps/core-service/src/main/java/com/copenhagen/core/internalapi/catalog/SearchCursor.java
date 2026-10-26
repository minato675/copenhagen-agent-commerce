package com.copenhagen.core.internalapi.catalog;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.copenhagen.core.internalapi.error.InternalApiException;
import com.copenhagen.core.internalapi.error.InternalErrorCode;

/**
 * 검색 커서. 호출자에게는 불투명 문자열이고 내부 표현은 offset의 base64url이다(설계서 §5).
 *
 * <p>Catalog 검색은 매 호출마다 merchant를 전부 조회해 메모리에서 정렬·자르므로 offset으로 충분하다.
 */
public final class SearchCursor {

    private SearchCursor() {
    }

    public static String encode(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(offset).getBytes(StandardCharsets.US_ASCII));
    }

    public static int decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(
                    new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII));
            if (offset < 0) {
                throw new InternalApiException(InternalErrorCode.INVALID_ARGUMENT, "cursor가 올바르지 않습니다.");
            }
            return offset;
        } catch (IllegalArgumentException exception) {
            throw new InternalApiException(InternalErrorCode.INVALID_ARGUMENT, "cursor가 올바르지 않습니다.");
        }
    }
}
