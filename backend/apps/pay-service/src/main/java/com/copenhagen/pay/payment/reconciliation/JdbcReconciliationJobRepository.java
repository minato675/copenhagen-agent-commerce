package com.copenhagen.pay.payment.reconciliation;

import com.copenhagen.pay.payment.policy.ReconciliationJobStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReconciliationJobRepository implements ReconciliationJobRepository {

    private static final String CLAIM_NEXT_SQL = """
            WITH candidate AS (
                SELECT job.id, payment.status AS payment_status
                FROM payment_reconciliation_jobs job
                JOIN payment_attempts attempt ON attempt.id = job.payment_attempt_id
                JOIN payments payment ON payment.id = attempt.payment_id
                WHERE ((job.status IN ('PENDING', 'FAILED')
                        AND job.next_run_at <= CAST(? AS TIMESTAMPTZ))
                   OR (job.status = 'RUNNING'
                        AND job.lease_expires_at <= CAST(? AS TIMESTAMPTZ)))
                  AND NOT EXISTS (
                      SELECT 1 FROM payment_reconciliation_jobs other
                      JOIN payment_attempts other_attempt ON other_attempt.id = other.payment_attempt_id
                      WHERE other_attempt.payment_id = payment.id AND other.id <> job.id
                        AND other.status = 'RUNNING'
                        AND other.lease_expires_at > CAST(? AS TIMESTAMPTZ)
                  )
                ORDER BY job.next_run_at, job.id
                FOR UPDATE OF job, payment SKIP LOCKED
                LIMIT 1
            )
            UPDATE payment_reconciliation_jobs job
            SET status = CASE WHEN candidate.payment_status <> 'UNKNOWN' THEN 'CANCELLED'
                              WHEN job.attempt_count >= 5 THEN 'EXHAUSTED' ELSE 'RUNNING' END,
                lease_expires_at = CASE WHEN candidate.payment_status = 'UNKNOWN'
                                            AND job.attempt_count < 5
                                      THEN CAST(? AS TIMESTAMPTZ) ELSE NULL END,
                attempt_count = job.attempt_count + CASE WHEN candidate.payment_status = 'UNKNOWN'
                                            AND job.attempt_count < 5 THEN 1 ELSE 0 END,
                last_run_at = CASE WHEN candidate.payment_status = 'UNKNOWN'
                                            AND job.attempt_count < 5
                                      THEN CAST(? AS TIMESTAMPTZ) ELSE job.last_run_at END,
                last_error = CASE WHEN candidate.payment_status <> 'UNKNOWN' THEN 'PAYMENT_NOT_UNKNOWN'
                                  WHEN job.attempt_count >= 5 THEN 'PG_LOOKUP_EXHAUSTED' ELSE NULL END
            FROM candidate
            WHERE job.id = candidate.id
            RETURNING job.id, job.payment_attempt_id, job.request_id, job.status,
                      job.attempt_count, job.lease_expires_at
            """;

    private static final String LOCK_OWNED_SQL = """
            SELECT id FROM payment_reconciliation_jobs
            WHERE id = ? AND status = 'RUNNING' AND attempt_count = ?
              AND lease_expires_at = CAST(? AS TIMESTAMPTZ)
              AND lease_expires_at > CAST(? AS TIMESTAMPTZ)
            FOR UPDATE
            """;

    private static final String COMPLETE_SQL = """
            UPDATE payment_reconciliation_jobs
            SET status = ?, next_run_at = CAST(? AS TIMESTAMPTZ),
                lease_expires_at = NULL, last_error = ?
            WHERE id = ? AND status = 'RUNNING' AND attempt_count = ?
              AND lease_expires_at = CAST(? AS TIMESTAMPTZ)
              AND lease_expires_at > CAST(? AS TIMESTAMPTZ)
            """;

    private static final RowMapper<ReconciliationJob> ROW_MAPPER = JdbcReconciliationJobRepository::mapJob;

    private final JdbcTemplate jdbcTemplate;

    public JdbcReconciliationJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<ReconciliationJob> claimNext(Instant now, Instant leaseExpiresAt) {
        Timestamp timestamp = Timestamp.from(now);
        List<ReconciliationJob> jobs = jdbcTemplate.query(CLAIM_NEXT_SQL, ROW_MAPPER,
                timestamp, timestamp, timestamp, Timestamp.from(leaseExpiresAt), timestamp);
        return jobs.stream().findFirst();
    }

    @Override
    public boolean lockOwned(ReconciliationJob job, Instant now) {
        if (job.leaseExpiresAt() == null) {
            return false;
        }
        return !jdbcTemplate.queryForList(LOCK_OWNED_SQL, String.class,
                job.id(), job.attemptCount(), Timestamp.from(job.leaseExpiresAt()), Timestamp.from(now)).isEmpty();
    }

    @Override
    public boolean complete(
            ReconciliationJob job,
            ReconciliationJobStatus targetStatus,
            Instant nextRunAt,
            Instant completedAt,
            String lastError) {
        if (job.leaseExpiresAt() == null) {
            return false;
        }
        return jdbcTemplate.update(COMPLETE_SQL,
                targetStatus.name(), Timestamp.from(nextRunAt), lastError, job.id(), job.attemptCount(),
                Timestamp.from(job.leaseExpiresAt()), Timestamp.from(completedAt)) == 1;
    }

    private static ReconciliationJob mapJob(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp leaseTimestamp = resultSet.getTimestamp("lease_expires_at");
        return new ReconciliationJob(
                resultSet.getString("id"),
                resultSet.getString("payment_attempt_id"),
                resultSet.getString("request_id"),
                ReconciliationJobStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempt_count"),
                leaseTimestamp == null ? null : leaseTimestamp.toInstant());
    }
}
