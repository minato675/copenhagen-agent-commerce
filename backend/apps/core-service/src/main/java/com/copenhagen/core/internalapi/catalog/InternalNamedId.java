package com.copenhagen.core.internalapi.catalog;

/**
 * mcp-server가 {@code {id, name}} 두 필드만 읽는 자리에 쓴다(카테고리·판매처 필터·옵션 그룹·옵션 값).
 * {@code id}는 {@code [A-Za-z0-9_:-]{1,96}}를 만족해야 한다.
 */
public record InternalNamedId(String id, String name) {
}
