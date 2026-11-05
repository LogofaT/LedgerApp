package ledger.messaging;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class MessagingConfig {
    @Bean
    TopicExchange transactionExchange(@Value("${ledger.outbox.exchange}") String name) {
        return new TopicExchange(name,true,false);
    }
    @Bean
    Queue announcementQueue() { return QueueBuilder.durable("ledger.transactions.announcements").build(); }
    @Bean
    Binding announcementBinding(Queue announcementQueue,TopicExchange transactionExchange,
                                @Value("${ledger.outbox.routing-key}") String routing) {
        return BindingBuilder.bind(announcementQueue).to(transactionExchange).with(routing);
    }
    @Bean
    Queue paymentDeadLetterQueue(@Value("${ledger.payments.dead-letter-queue}") String name) {
        return QueueBuilder.durable(name).build();
    }
    @Bean
    Queue paymentQueue(@Value("${ledger.payments.queue}") String name,
                       @Value("${ledger.payments.dead-letter-queue}") String dead) {
        return QueueBuilder.durable(name).withArguments(Map.of("x-dead-letter-exchange","",
                "x-dead-letter-routing-key",dead)).build();
    }
    @Bean
    TopicExchange paymentExchange(@Value("${ledger.payments.exchange}") String name) {
        return new TopicExchange(name,true,false);
    }
    @Bean
    Binding paymentBinding(Queue paymentQueue,TopicExchange paymentExchange,
                           @Value("${ledger.payments.routing-key}") String routing) {
        return BindingBuilder.bind(paymentQueue).to(paymentExchange).with(routing);
    }
}
