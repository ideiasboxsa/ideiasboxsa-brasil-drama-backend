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

class AdminAuditDeletedOperatorFilterContractTest {
    @Test void historicalActorFilterWorksEvenWhenOperatorWasDeleted() {
        var operators=mock(AdminOperatorRepository.class);var logs=mock(AdminAuditLogRepository.class);
        var admin=new AdminOperator();admin.id=UUID.randomUUID();admin.role=AdminRole.SUPER_ADMIN;admin.active=true;admin.displayName="Admin";admin.email="admin@brasildrama.com.br";
        var removedId=UUID.randomUUID();var otherId=UUID.randomUUID();
        when(operators.findById(admin.id)).thenReturn(Optional.of(admin));
        when(logs.findAllByOrderByCreatedAtDesc(any(PageRequest.class))).thenReturn(List.of(audit(removedId),audit(otherId)));
        when(operators.findAllById(any())).thenReturn(List.of());
        Principal principal=()->admin.id.toString();
        var response=new AdminAuditApi(operators,logs).latest(principal,null,removedId);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body=(List<?>)response.getBody();assertThat(body).hasSize(1);
        assertThat(((AdminAuditApi.AuditView)body.getFirst()).actorOperatorId()).isEqualTo(removedId);
    }
    private static AdminAuditLog audit(UUID actorId){var log=new AdminAuditLog();log.id=UUID.randomUUID();log.actorOperatorId=actorId;log.action="OPERATOR_DEACTIVATED";log.entityType="ADMIN_OPERATOR";log.summary="evento histórico";log.createdAt=Instant.now();return log;}
}
