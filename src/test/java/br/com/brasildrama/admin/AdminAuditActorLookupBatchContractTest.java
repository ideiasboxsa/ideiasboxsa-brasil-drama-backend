package br.com.brasildrama.admin;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminAuditActorLookupBatchContractTest {
    @Test
    void loadsManyDistinctAuditedActorsWithSingleDirectoryBatch() {
        var operators = mock(AdminOperatorRepository.class);
        var logs = mock(AdminAuditLogRepository.class);
        var admin = operator(AdminRole.SUPER_ADMIN, "Admin", "admin@brasildrama.com.br");
        var alpha = operator(AdminRole.EDITOR, "Alpha", "alpha@brasildrama.com.br");
        var beta = operator(AdminRole.SUPPORT, "Beta", "beta@brasildrama.com.br");
        var gamma = operator(AdminRole.EDITOR, "Gamma", "gamma@brasildrama.com.br");

        when(operators.findById(admin.id)).thenReturn(Optional.of(admin));
        when(logs.findAllByOrderByCreatedAtDesc(any(PageRequest.class))).thenReturn(List.of(
            audit(alpha.id), audit(beta.id), audit(gamma.id), audit(alpha.id)
        ));
        when(operators.findAllById(any())).thenReturn(List.of(alpha, beta, gamma));

        var response = new AdminAuditApi(operators, logs).actors(principal(admin.id));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat((List<?>) response.getBody()).hasSize(3);
        verify(operators, times(1)).findAllById(any());
    }

    private static Principal principal(UUID id) { return () -> id.toString(); }

    private static AdminOperator operator(AdminRole role, String name, String email) {
        var operator = new AdminOperator();
        operator.id = UUID.randomUUID();
        operator.role = role;
        operator.displayName = name;
        operator.email = email;
        operator.active = true;
        return operator;
    }

    private static AdminAuditLog audit(UUID actorId) {
        var log = new AdminAuditLog();
        log.id = UUID.randomUUID();
        log.actorOperatorId = actorId;
        log.action = "OPERATOR_ROLE_CHANGED";
        log.entityType = "ADMIN_OPERATOR";
        log.summary = "evento";
        log.createdAt = Instant.now();
        return log;
    }
}
