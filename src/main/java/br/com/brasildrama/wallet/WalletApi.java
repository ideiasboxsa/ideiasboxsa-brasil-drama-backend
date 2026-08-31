package br.com.brasildrama.wallet;

import br.com.brasildrama.catalog.CatalogQueryService;
import br.com.brasildrama.rewards.VipAccessService;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

record WalletDto(int balance) {}
record EntitlementDto(String episodeId) {}
record UnlockRequest(String operationKey) {}
record UnlockResponse(int balance, String episodeId, boolean unlocked) {}

@Service
class WalletService {
    private final WalletLedgerRepository ledger;
    private final EpisodeEntitlementRepository entitlements;
    private final CatalogQueryService catalog;
    private final VipAccessService vipAccess;

    WalletService(
        WalletLedgerRepository ledger,
        EpisodeEntitlementRepository entitlements,
        CatalogQueryService catalog,
        VipAccessService vipAccess
    ) {
        this.ledger = ledger;
        this.entitlements = entitlements;
        this.catalog = catalog;
        this.vipAccess = vipAccess;
    }

    int balance(UUID userId) {
        return Math.toIntExact(ledger.balance(userId));
    }

    List<EntitlementDto> entitlements(UUID userId) {
        return entitlements.findAllByIdUserIdOrderByGrantedAtAsc(userId).stream()
            .map(e -> new EntitlementDto(e.id.episodeId.toString()))
            .toList();
    }

    @Transactional
    public UnlockResponse unlock(UUID userId, UUID episodeId, String operationKey) {
        if (operationKey == null || operationKey.isBlank() || operationKey.length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "operationKey inválido");
        }

        var episode = catalog.episodeAccess(episodeId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Episódio não encontrado"));

        if (episode.free() || vipAccess.activeUntil(userId).isPresent()) {
            return new UnlockResponse(balance(userId), episode.episodeId(), true);
        }
        if (episode.coinPrice() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Preço premium inválido");
        }

        ledger.lockUser(userId);

        var entitlementId = new EpisodeEntitlementId(userId, episodeId);
        if (entitlements.existsById(entitlementId)) {
            return new UnlockResponse(balance(userId), episode.episodeId(), true);
        }

        entitlements.findByIdUserIdAndOperationKey(userId, operationKey).ifPresent(existing -> {
            if (!existing.id.episodeId.equals(episodeId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "operationKey já utilizado em outro entitlement");
            }
        });

        ledger.findByUserIdAndOperationKey(userId, operationKey).ifPresent(existing -> {
            if (!"EPISODE".equals(existing.referenceType) || !episode.episodeId().equals(existing.referenceId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "operationKey já utilizado em outra operação");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Operação financeira já registrada sem entitlement");
        });

        int currentBalance = balance(userId);
        if (currentBalance < episode.coinPrice()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Saldo insuficiente");
        }

        ledger.save(new WalletLedgerEntry(
            userId,
            operationKey,
            "EPISODE_UNLOCK",
            -episode.coinPrice(),
            "EPISODE",
            episode.episodeId()
        ));
        entitlements.save(new EpisodeEntitlement(userId, episodeId, "COINS", operationKey));

        return new UnlockResponse(currentBalance - episode.coinPrice(), episode.episodeId(), true);
    }
}

@RestController
class WalletController {
    private final WalletService wallet;

    WalletController(WalletService wallet) {
        this.wallet = wallet;
    }

    @GetMapping("/v1/wallet")
    WalletDto wallet(Authentication authentication) {
        return new WalletDto(wallet.balance(userId(authentication)));
    }

    @GetMapping("/v1/entitlements")
    List<EntitlementDto> entitlements(Authentication authentication) {
        return wallet.entitlements(userId(authentication));
    }

    @PostMapping("/v1/episodes/{episodeId}/unlock")
    UnlockResponse unlock(
        Authentication authentication,
        @PathVariable UUID episodeId,
        @RequestBody UnlockRequest request
    ) {
        return wallet.unlock(userId(authentication), episodeId, request.operationKey());
    }

    private static UUID userId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
