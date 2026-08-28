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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminAuditDeletedOperatorTimelineContractTest {
    @Test
    void preservesHistoricalAuditEventWhenActorNoLongerExistsInOperatorDirectory() {
        var operators = mock(AdminOperatorRepository.class);
        var logs = mock(AdminAuditLogRepository.class);
        var admin = new AdminOperator();
        admin.id = UUID.randomUUID();
        admin.role = AdminRole.SUPER_ADMIN;
        admin.active = true;
        admin.displayName = "Admin";
        admin.email = "admin@brasildrama.com.br";
        var removedActorId = UUID.randomUUID();
        var audit = new AdminAuditLog();
        audit.id = UUID.randomUUID();
        audit.actorOperatorId = removedActorId;
        audit.action = "OPERATOR_DEACTIVATED";
        audit.entityType = "ADMIN_OPERATOR";
        audit.summary = "operador desativado";
        audit.createdAt = Instant.now();
        when(operators.findById(admin.id)).thenReturn(Optional.of(admin));
        when(logs.findAllByOrderByCreatedAtDesc(any(PageRequest.class))).thenReturn(List.of(audit));
        when(operators.findAllById(any())).thenReturn(List.of());

        Principal principal = () -> admin.id.toString();
        var response = new AdminAuditApi(operators, logs).latest(principal, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = (List<?>) response.getBody();
        assertThat(body).hasSize(1);
        var view = (AdminAuditApi.AuditView) body.getFirst();
        assertThat(view.actorOperatorId()).isEqualTo(removedActorId);
        assertThat(view.action()).isEqualTo("OPERATOR_DEACTIVATED");
    }
}
