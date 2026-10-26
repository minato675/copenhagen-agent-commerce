package com.copenhagen.core.product;

/**
 * mcp-server {@code CatalogSchemas.CORE_SEARCH} envelope과 필드가 대응한다.
 * {@code connectionRef}는 이슈337(Agent merchant 연동)이 없어 고정값("default")을 쓴다.
 */
public record SearchEnvelope(String publicUserRef, String connectionRef, SearchData data) {
}
