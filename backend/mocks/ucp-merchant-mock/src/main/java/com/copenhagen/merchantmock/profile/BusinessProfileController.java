package com.copenhagen.merchantmock.profile;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Business Profile Discovery. 전체 구매 19단계 중 2단계다.
 *
 * <p>UCP 규격은 호스트 루트 {@code /.well-known/ucp} 하나지만 컨테이너 하나가 몰 셋을 서빙하므로 몰 아래로
 * 내려왔다(393, DESIGN.md 3.3절). 옛 루트 경로는 {@code LegacyPathFilter} 가 첫 몰로 forward 한다.
 */
@RestController
public class BusinessProfileController {

    private final BusinessProfileService service;

    public BusinessProfileController(BusinessProfileService service) {
        this.service = service;
    }

    @GetMapping(path = "/api/ucp/v1/{merchantId}/.well-known/ucp", produces = MediaType.APPLICATION_JSON_VALUE)
    public BusinessProfileResponse businessProfile(@PathVariable String merchantId) {
        return service.build(merchantId);
    }
}
