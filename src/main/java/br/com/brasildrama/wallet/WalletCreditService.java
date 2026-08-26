package br.com.brasildrama.wallet;

import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class WalletCreditService {
    private final WalletLedgerRepository ledger;

    WalletCreditService(WalletLedgerRepository ledger) {
        this.ledger = ledger;
    }

    @Transactional
    public int creditOnce(UUID userId, String operationKey, int amount, String referenceType, String referenceId) {
        if (amount <= 0) throw new IllegalArgumentException("amount deve ser positivo");
        ledger.lockUser(userId);
        if (ledger.findByUserIdAndOperationKey(userId, operationKey).isEmpty()) {
            ledger.save(new WalletLedgerEntry(
                userId,
                operationKey,
                "GOOGLE_PLAY_COIN_PURCHASE",
                amount,
                referenceType,
                referenceId
            ));
        }
        return Math.toIntExact(ledger.balance(userId));
    }

    public int balance(UUID userId) {
        return Math.toIntExact(ledger.balance(userId));
    }
}
