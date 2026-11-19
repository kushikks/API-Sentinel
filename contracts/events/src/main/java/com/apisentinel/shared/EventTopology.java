package com.apisentinel.shared;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

public final class EventTopology {
    private EventTopology() {}

    public static Declarables topology() {
        var exchange = new TopicExchange(Events.EXCHANGE, true, false);
        var dead = new TopicExchange(Events.DEAD_EXCHANGE, true, false);
        var analyzer = QueueBuilder.durable(Events.ANALYZER_QUEUE).quorum()
                .deadLetterExchange(Events.DEAD_EXCHANGE).deadLetterRoutingKey(Events.ANALYZER_QUEUE + ".dead")
                .withArgument("x-delivery-limit", 20).build();
        var analyzerDlq = QueueBuilder.durable(Events.ANALYZER_QUEUE + ".dead").quorum().build();
        var impact = QueueBuilder.durable(Events.IMPACT_QUEUE).quorum().build();
        return new Declarables(exchange, dead, analyzer, analyzerDlq, impact,
                BindingBuilder.bind(analyzer).to(exchange).with(Events.REGISTERED_ROUTE),
                BindingBuilder.bind(analyzerDlq).to(dead).with(analyzerDlq.getName()),
                BindingBuilder.bind(impact).to(exchange).with(Events.COMPLETED_ROUTE));
    }
}
