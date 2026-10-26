package com.copenhagen.pay.payment.pg;

/**
 * Pay가 외부 결제 대행사에 결제를 요청하기 위한 내부 포트입니다.
 *
 * <p>구현체는 PG 응답을 Pay가 이해하는 세 가지 결과로만 변환합니다. 응답을 받지 못한
 * 경우에는 결제를 다시 보내지 않고 {@link ChargeResult.Outcome#UNKNOWN}을 반환해야 합니다.
 */
public interface PaymentGateway {

    ChargeResult charge(ChargeRequest request);

    StatusResult lookup(StatusRequest request);

    record StatusRequest(String pgTransactionId, String pgRequestId) {
    }

    record StatusResult(ChargeResult.Outcome outcome, String pgTransactionId, String responseCode) {

        public static StatusResult unknown(String code) {
            return new StatusResult(ChargeResult.Outcome.UNKNOWN, null, code);
        }
    }

    record ChargeRequest(
            String paymentId,
            String pgRequestId,
            String paymentMethodRef,
            long amountMinor,
            String currency) {
    }

    record ChargeResult(
            Outcome outcome,
            String pgTransactionId,
            String responseCode) {

        public static ChargeResult success(String pgTransactionId, String responseCode) {
            return new ChargeResult(Outcome.SUCCESS, pgTransactionId, responseCode);
        }

        public static ChargeResult failed(String pgTransactionId, String responseCode) {
            return new ChargeResult(Outcome.FAILED, pgTransactionId, responseCode);
        }

        public static ChargeResult unknown(String responseCode) {
            return new ChargeResult(Outcome.UNKNOWN, null, responseCode);
        }

        public enum Outcome {
            SUCCESS,
            FAILED,
            UNKNOWN
        }
    }
}
