package ledger.messaging;

import ledger.persistence.QuarantineRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentQuarantine {
    private final QuarantineRepository repository;
    public PaymentQuarantine(QuarantineRepository repository) { this.repository=repository; }
    @Transactional
    public void store(String eventId,String paymentId,String reason,String payload) {
        repository.lockGate();
        repository.store(eventId,paymentId,reason,payload);
    }
}
