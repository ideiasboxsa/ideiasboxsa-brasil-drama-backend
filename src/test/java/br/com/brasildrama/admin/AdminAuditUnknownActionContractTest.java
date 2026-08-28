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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAuditUnknownActionContractTest {
    @Test
    void unknownActionReturnsEmptyListWithoutBreakingAuditEndpoint() {
        var operators = mock(AdminOperatorRepository.class);
        var logs = mock(AdminAuditLogRepository.class);
        var admin = new AdminOperator();
        admin.id = UUID.randomUUID();
        admin.role = AdminRole.SUPER_ADMIN;
        admin.active = true;
        when(operators.findById(admin.id)).thenReturn(Optional.of(admin));

        var event = new AdminAuditLog();
        event.id = UUID.randomUUID();
        event.actorOperatorId = admin.id;
        event.action = "OPERATOR_INVITED";
        event.entityType = "ADMIN_OPERATOR";
        event.summary = "evento";
        event.createdAt = Instant.now();
        when(logs.findAllByOrderByCreatedAtDesc(any(PageRequest.class))).thenReturn(List.of(event));

        Principal principal = () -> admin.id.toString();
        var response = new AdminAuditApi(operators, logs).latest(principal, "ACTION_THAT_DOES_NOT_EXIST", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat((List<?>) response.getBody()).isEmpty();
        verify(logs).findAllByOrderByCreatedAtDesc(any(PageRequest.class));
    }
}
