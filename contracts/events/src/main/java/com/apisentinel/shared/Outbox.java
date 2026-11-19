package com.apisentinel.shared;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

public class Outbox {
    private static final Logger log = LoggerFactory.getLogger(Outbox.class);

    private final JdbcClient db;
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public Outbox(JdbcClient db, RabbitTemplate rabbit, ObjectMapper json, TransactionTemplate transaction) {
        this.db = db;
        this.rabbit = rabbit;
        this.json = json;
        this.transaction = transaction;
    }

    public void append(String route, Events.Envelope<?> envelope) {
        final String body;
        try {
            body = json.writeValueAsString(envelope);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not serialize the event", error);
        }
        db.sql("insert into outbox_events (id, routing_key, body) values (?, ?, convert_to(?, 'UTF8'))")
                .params(envelope.eventId(), route, body).update();
    }

    @Scheduled(fixedDelayString = "${sentinel.outbox-delay:500}")
    public void publish() {
        for (int i = 0; i < 20; i++) {
            if (!Boolean.TRUE.equals(transaction.execute(status -> publishNext()))) return;
        }
    }

    private boolean publishNext() {
        var event = db.sql("""
                select id, routing_key, body, attempts from outbox_events
                where published_at is null and next_attempt_at <= now()
                order by next_attempt_at, id limit 1 for update skip locked
                """).query((rs, row) -> new Pending(rs.getObject("id", UUID.class),
                rs.getString("routing_key"), rs.getBytes("body"), rs.getInt("attempts"))).optional();
        if (event.isEmpty()) return false;
        var pending = event.get();
        try {
            var correlation = new CorrelationData(pending.id().toString());
            var message = MessageBuilder.withBody(pending.body()).setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setMessageId(correlation.getId()).setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
            rabbit.send(Events.EXCHANGE, pending.route(), message, correlation);
            var confirm = correlation.getFuture().get(3, TimeUnit.SECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) {
                throw new IllegalStateException("Broker did not confirm a routed message");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publishing interrupted", interrupted);
        } catch (Exception unavailable) {
            int delay = 1 << Math.min(pending.attempts(), 8);
            db.sql("""
                    update outbox_events set attempts = attempts + 1,
                    next_attempt_at = now() + (? * interval '1 second') where id = ?
                    """).params(delay, pending.id()).update();
            log.warn("Outbox event {} will retry after {} seconds ({})",
                    pending.id(), delay, unavailable.getClass().getSimpleName());
            return false;
        }
        db.sql("update outbox_events set published_at = now() where id = ?").param(pending.id()).update();
        return true;
    }

    private record Pending(UUID id, String route, byte[] body, int attempts) {}
}
