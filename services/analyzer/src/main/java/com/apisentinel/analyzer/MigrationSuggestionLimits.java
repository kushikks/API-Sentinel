package com.apisentinel.analyzer;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class MigrationSuggestionLimits {
    private static final String PROVIDER_SCOPE = "provider:gemini";
    private final JdbcClient db;

    MigrationSuggestionLimits(JdbcClient db) {
        this.db = db;
    }

    @Transactional
    void reserve(UUID actor) {
        reserve("user:" + actor, 30, "Wait before requesting more migration suggestions.");
        reserve(PROVIDER_SCOPE, 2, "Migration suggestions are temporarily rate limited. Try again shortly.");
    }

    void deferProvider(int seconds) {
        db.sql("""
                insert into migration_suggestion_limits(scope, ready_at)
                values (?, clock_timestamp() + ? * interval '1 second')
                on conflict (scope) do update
                    set ready_at = greatest(migration_suggestion_limits.ready_at, excluded.ready_at)
                """).params(PROVIDER_SCOPE, seconds).update();
    }

    private void reserve(String scope, int seconds, String detail) {
        int accepted = db.sql("""
                insert into migration_suggestion_limits(scope, ready_at)
                values (?, clock_timestamp() + ? * interval '1 second')
                on conflict (scope) do update
                    set ready_at = clock_timestamp() + ? * interval '1 second'
                    where migration_suggestion_limits.ready_at <= clock_timestamp()
                """).params(scope, seconds, seconds).update();
        if (accepted > 0) return;

        int remaining = db.sql("""
                select greatest(1, ceil(extract(epoch from (ready_at - clock_timestamp()))))::integer
                from migration_suggestion_limits where scope = ?
                """).param(scope).query(Integer.class).single();
        throw new MigrationSuggestionFailure(HttpStatus.TOO_MANY_REQUESTS, "MIGRATION_RATE_LIMITED", detail, remaining);
    }
}
