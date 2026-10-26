package com.copenhagen.merchantmock;

import com.copenhagen.merchantmock.checkout.CheckoutStatus;
import com.copenhagen.merchantmock.order.OrderStatus;
import com.copenhagen.merchantmock.scenario.Scenario;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 모듈 루트 {@code openapi.yaml} 이 실제 컨트롤러와 같은지 본다(Jira 188 완료 조건).
 *
 * <p>손으로 쓴 명세라 컨트롤러를 고치고 명세를 빼먹는 일이 생긴다. 경로·메서드 집합과 상태 값 집합 셋을 대조한다.
 * 필드 모양까지는 보지 않는다 — 그건 컨트롤러 테스트가 응답 JSON 으로 이미 본다.
 */
class OpenApiContractTest {

    private static final Path SPEC = Path.of("openapi.yaml");
    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch");

    private static Map<String, Object> spec;

    @BeforeAll
    static void load() throws IOException {
        try (Reader reader = Files.newBufferedReader(SPEC, StandardCharsets.UTF_8)) {
            spec = new Yaml().load(reader);
        }
    }

    @Test
    @DisplayName("명세의 경로·메서드가 컨트롤러 매핑과 같다")
    void paths_matchControllerMappings() {
        assertEquals(controllerEndpoints(), specEndpoints());
    }

    @Test
    @DisplayName("시나리오 이름이 Scenario enum 과 같다")
    void scenarioNames_matchEnum() {
        assertEquals(names(Scenario.values()), enumOf("ScenarioName"));
    }

    @Test
    @DisplayName("체크아웃 상태가 CheckoutStatus 의 API 값과 같다")
    void checkoutStatuses_matchEnum() {
        Set<String> expected = Arrays.stream(CheckoutStatus.values())
                .map(CheckoutStatus::apiValue)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(expected, enumOf("CheckoutStatus"));
    }

    @Test
    @DisplayName("주문 상태가 OrderStatus 와 같다")
    @SuppressWarnings("unchecked")
    void orderStatuses_matchEnum() {
        Map<String, Object> properties = (Map<String, Object>) schema("Order").get("properties");
        Map<String, Object> status = (Map<String, Object>) properties.get("status");
        assertEquals(names(OrderStatus.values()), new TreeSet<>((List<String>) status.get("enum")));
    }

    private static Set<String> controllerEndpoints() {
        // 기본값으로 꺼져 있는 조건부 컨트롤러(연관 상품, DESIGN.md 6.8.1절)도 명세에는 있어야 한다. 스캐너는 @Conditional 을
        // 평가하므로 스위치를 켠 환경을 준다
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new MapPropertySource("conditional-controllers", Map.of("merchant.catalog.related.enabled", "true")));
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false, environment);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<String> endpoints = new TreeSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.copenhagen.merchantmock")) {
            Class<?> type = loadClass(candidate.getBeanClassName());
            RequestMapping root = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String[] prefixes = root == null || root.path().length == 0 ? new String[] {""} : root.path();
            for (Method method : type.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                String[] paths = mapping.path().length == 0 ? new String[] {""} : mapping.path();
                for (String prefix : prefixes) {
                    for (String path : paths) {
                        for (var verb : mapping.method()) {
                            endpoints.add(verb.name() + " " + prefix + path);
                        }
                    }
                }
            }
        }
        return endpoints;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> specEndpoints() {
        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) spec.get("paths");
        Set<String> endpoints = new TreeSet<>();
        paths.forEach((path, item) -> item.keySet().stream()
                .filter(HTTP_METHODS::contains)
                .forEach(verb -> endpoints.add(verb.toUpperCase(Locale.ROOT) + " " + path)));
        return endpoints;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(String name) {
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        return (Map<String, Object>) ((Map<String, Object>) components.get("schemas")).get(name);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> enumOf(String schemaName) {
        return new TreeSet<>((List<String>) schema(schemaName).get("enum"));
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values)
                .map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
