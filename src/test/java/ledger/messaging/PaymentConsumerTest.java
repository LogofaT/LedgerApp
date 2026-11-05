package ledger.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import ledger.api.Models.PaymentMessage;
import ledger.service.LedgerFailure;
import ledger.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentConsumerTest {
    private final ObjectMapper json=new ObjectMapper();
    private LedgerService ledger;
    private PaymentQuarantine quarantine;
    private RabbitTemplate rabbit;
    private Channel channel;
    private PaymentConsumer consumer;

    @BeforeEach void setup() {
        ledger=mock(LedgerService.class);quarantine=mock(PaymentQuarantine.class);
        rabbit=mock(RabbitTemplate.class);channel=mock(Channel.class);
        consumer=new PaymentConsumer(json,ledger,quarantine,rabbit,"ledger.payments","test");
        when(ledger.settlePayment(any())).thenReturn(new LedgerService.PaymentOutcome("SETTLED","tx",false));
    }

    @Test void acceptsOnlyStringAmountAndRejectsLegacyPostingsBeforeAcknowledging() throws Exception {
        for(Object amount:Arrays.asList(7,7.5,true,null,List.of("7"),Map.of("value","7"))) {
            Map<String,Object> fields=payment();fields.put("amount",amount);
            consumer.consume(message(fields),channel);
        }
        var legacy=payment();legacy.remove("merchantAccountId");legacy.remove("amount");
        legacy.put("entries",List.of(Map.of("accountId","merchant","direction","CREDIT","amount","7")));
        consumer.consume(message(legacy),channel);
        verifyNoInteractions(ledger);
        verify(quarantine,times(6)).store(eq("event"),eq("payment"),eq("INVALID_PAYMENT_FIELD"),anyString());
        verify(quarantine).store(eq("event"),eq("payment"),eq("UNKNOWN_FIELD"),anyString());
        verify(channel,times(7)).basicAck(42,false);
        var order=inOrder(quarantine,channel);
        for(int i=0;i<7;i++) {
            order.verify(quarantine).store(anyString(),anyString(),anyString(),anyString());
            order.verify(channel).basicAck(42,false);
        }
    }

    @Test void optionalMetadataTypesAreStrictAndValidPaymentFollowsPoison() throws Exception {
        for(String name:List.of("description","requestId")) {
            var invalid=payment();invalid.put(name,17);
            consumer.consume(message(invalid),channel);
        }
        var valid=payment();valid.put("description",null);valid.put("requestId",null);
        consumer.consume(message(valid),channel);
        verify(ledger).settlePayment(new PaymentMessage("event","payment","merchant","7","JPY",null,null));
        var order=inOrder(quarantine,ledger,channel);
        for(int i=0;i<2;i++) {
            order.verify(quarantine).store(eq("event"),eq("payment"),eq("INVALID_PAYMENT_FIELD"),anyString());
            order.verify(channel).basicAck(42,false);
        }
        order.verify(ledger).settlePayment(any());
        order.verify(channel).basicAck(42,false);
    }

    @Test void businessRefusalIsQuarantinedBeforeAck() throws Exception {
        when(ledger.settlePayment(any())).thenThrow(new LedgerFailure(422,"CURRENCY_MISMATCH","Wrong merchant currency","merchantAccountId"));
        consumer.consume(message(payment()),channel);
        var order=inOrder(ledger,quarantine,channel);
        order.verify(ledger).settlePayment(any());
        order.verify(quarantine).store(eq("event"),eq("payment"),eq("CURRENCY_MISMATCH"),anyString());
        order.verify(channel).basicAck(42,false);
    }

    @Test void failedQuarantineCommitNeverAcknowledgesInput() throws Exception {
        var invalid=payment();invalid.put("amount",7);
        doThrow(new IllegalStateException("database unavailable")).when(quarantine).store(anyString(),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->consumer.consume(message(invalid),channel));
        verifyNoInteractions(channel,rabbit);
    }

    @Test void parsesBodyOnceAndConvertsTheValidatedTree() throws Exception {
        ObjectMapper mapper=spy(new ObjectMapper());
        consumer=new PaymentConsumer(mapper,ledger,quarantine,rabbit,"ledger.payments","test");
        Message input=message(payment());

        consumer.consume(input,channel);

        verify(mapper,times(1)).readTree(new String(input.getBody(),StandardCharsets.UTF_8));
        verify(mapper).treeToValue(any(com.fasterxml.jackson.databind.JsonNode.class),eq(PaymentMessage.class));
        verify(ledger).settlePayment(new PaymentMessage("event","payment","merchant","7","JPY",null,null));
        verify(channel).basicAck(42,false);
        verifyNoInteractions(quarantine,rabbit);
    }

    @Test void malformedEmptyAndWrongRootPayloadsAreQuarantinedBeforeAck() throws Exception {
        for(String body:List.of("{","","null","[]","7")) {
            MessageProperties properties=new MessageProperties();
            properties.setDeliveryTag(42);
            consumer.consume(new Message(body.getBytes(StandardCharsets.UTF_8),properties),channel);
        }
        var order=inOrder(quarantine,channel);
        order.verify(quarantine).store(isNull(),isNull(),eq("MALFORMED_PAYMENT"),eq("{"));
        order.verify(channel).basicAck(42,false);
        for(String body:List.of("","null","[]","7")) {
            order.verify(quarantine).store(isNull(),isNull(),eq("INVALID_PAYMENT"),eq(body));
            order.verify(channel).basicAck(42,false);
        }
        verifyNoInteractions(ledger,rabbit);
    }

    private Map<String,Object> payment() {
        Map<String,Object> fields=new LinkedHashMap<>();
        fields.put("eventId","event");fields.put("paymentId","payment");fields.put("merchantAccountId","merchant");
        fields.put("amount","7");fields.put("currency","JPY");return fields;
    }
    private Message message(Map<String,Object> fields) throws Exception {
        MessageProperties properties=new MessageProperties();properties.setDeliveryTag(42);
        return new Message(json.writeValueAsString(fields).getBytes(StandardCharsets.UTF_8),properties);
    }
}
