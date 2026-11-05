package ledger.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import ledger.api.Models.PaymentMessage;
import ledger.service.LedgerFailure;
import ledger.service.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.rabbitmq.client.Channel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class PaymentConsumer {
    private static final int MAX_PAYLOAD_BYTES=64*1024;
    private static final Logger log=LoggerFactory.getLogger(PaymentConsumer.class);
    private final ObjectMapper json;
    private final LedgerService ledger;
    private final PaymentQuarantine quarantine;
    private final RabbitTemplate rabbit;
    private final String queue;
    private final String instanceId;
    public PaymentConsumer(ObjectMapper json,LedgerService ledger,PaymentQuarantine quarantine,RabbitTemplate rabbit,
                           @Value("${ledger.payments.queue}") String queue,
                           @Value("${ledger.instance-id:local}") String instanceId) {
        this.json=json;this.ledger=ledger;this.quarantine=quarantine;this.rabbit=rabbit;this.queue=queue;this.instanceId=instanceId;
    }

    @RabbitListener(queues="${ledger.payments.queue}")
    public void consume(Message message,Channel channel) throws IOException {
        long tag=message.getMessageProperties().getDeliveryTag();
        byte[] raw=message.getBody();
        boolean oversized=raw.length>MAX_PAYLOAD_BYTES;
        String body=new String(oversized?java.util.Arrays.copyOf(raw,MAX_PAYLOAD_BYTES):raw,StandardCharsets.UTF_8);
        PaymentMessage payment=null;
        String eventId=header(message,"eventId"), paymentId=header(message,"paymentId");
        try {
            if(oversized)
                throw new LedgerFailure(413,"REQUEST_TOO_LARGE","Payment payload exceeds 65536 bytes",null);
            eventId=null;
            paymentId=null;
            JsonNode root=parse(body);
            eventId=readText(root,"eventId");
            paymentId=readText(root,"paymentId");
            validateJsonTypes(root);
            payment=json.treeToValue(root,PaymentMessage.class);
            LedgerService.PaymentOutcome outcome=ledger.settlePayment(payment);
            channel.basicAck(tag,false);
            log.info("ledger_payment_committed instanceId={} paymentId={} eventId={} requestId={} transactionId={} duplicate={}",
                    instanceId,payment.paymentId(),payment.eventId(),payment.requestId(),outcome.transactionId(),outcome.duplicate());
        } catch(LedgerFailure invalid) {
            quarantine.store(payment==null?eventId:payment.eventId(),
                    payment==null?paymentId:payment.paymentId(),invalid.code(),
                    body);
            channel.basicAck(tag,false);
            log.warn("ledger_payment_quarantined instanceId={} paymentId={} eventId={} code={}",
                    instanceId,logSafe(paymentId),logSafe(eventId),invalid.code());
        } catch(Exception transientFailure) {
            int attempt=attempt(message);
            if(attempt<2) {
                try {
                    Message retry=MessageBuilder.withBody(message.getBody())
                            .andProperties(message.getMessageProperties())
                            .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                            .setHeader("x-ledger-attempt",attempt+1).build();
                    CorrelationData confirmation=new CorrelationData("payment-retry-"+UUID.randomUUID());
                    rabbit.send("",queue,retry,confirmation);
                    CorrelationData.Confirm result=confirmation.getFuture().get(10,java.util.concurrent.TimeUnit.SECONDS);
                    if(!result.ack()||confirmation.getReturned()!=null)
                        throw new IllegalStateException("Broker did not confirm payment retry publication");
                    channel.basicAck(tag,false);
                } catch(Exception publishFailure) {
                    channel.basicNack(tag,false,true);
                    log.error("ledger_payment_retry_publish_failed eventId={}",logSafe(header(message,"eventId")));
                }
            } else {
                // Queue's durable DLX routes exhausted transients to ledger.payments.dlq.
                channel.basicNack(tag,false,false);
                log.error("ledger_payment_dead_lettered eventId={} attempts={}",logSafe(header(message,"eventId")),attempt+1);
            }
        }
    }
    private int attempt(Message message) {
        Object value=message.getMessageProperties().getHeaders().get("x-ledger-attempt");
        return value instanceof Number n?n.intValue():0;
    }
    private String header(Message message,String key) {
        Object value=message.getMessageProperties().getHeaders().get(key);
        return value==null?null:String.valueOf(value);
    }
    private JsonNode parse(String body) {
        try { return json.readTree(body); } catch(JsonProcessingException ex) {
            throw new LedgerFailure(400,"MALFORMED_PAYMENT","Payment is not valid JSON",null);
        }
    }
    private void validateJsonTypes(JsonNode root) {
        if(root==null||!root.isObject()) throw new LedgerFailure(400,"INVALID_PAYMENT","Payment must be a JSON object",null);
        java.util.Set<String> allowed=java.util.Set.of("eventId","paymentId","merchantAccountId","amount","currency","requestId","description");
        root.fieldNames().forEachRemaining(name->{
            if(!allowed.contains(name)) throw new LedgerFailure(400,"UNKNOWN_FIELD","Payment field is not supported",name);
        });
        for(String name:new String[]{"eventId","paymentId","merchantAccountId","amount","currency"}) {
            JsonNode field=root.get(name);
            if(field==null||!field.isTextual()) throw new LedgerFailure(400,"INVALID_PAYMENT_FIELD","Payment field must be a JSON string",name);
        }
        for(String name:new String[]{"requestId","description"}) {
            JsonNode field=root.get(name);
            if(field!=null&&!field.isNull()&&!field.isTextual())
                throw new LedgerFailure(400,"INVALID_PAYMENT_FIELD","Optional payment field must be a JSON string or null",name);
        }
    }
    private String readText(JsonNode root,String name) {
        JsonNode value=root==null?null:root.get(name);
        return value!=null&&value.isTextual()?value.textValue():null;
    }
    private String logSafe(String value) {
        if(value==null)return "-";
        String safe=value.replaceAll("[^A-Za-z0-9._:-]","_");
        return safe.length()>80?safe.substring(0,80):safe;
    }
}
