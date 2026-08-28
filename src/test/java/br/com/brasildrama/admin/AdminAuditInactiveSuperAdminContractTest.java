package br.com.brasildrama.admin;

import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AdminAuditInactiveSuperAdminContractTest {
    @Test
    void rejectsInactiveSuperAdminAcrossAuditEndpoints() {
        var operators = mock(AdminOperatorRepository.class);
        var logs = mock(AdminAuditLogRepository.class);
        var admin = new AdminOperator();
        admin.id = UUID.randomUUID();
        admin.role = AdminRole.SUPER_ADMIN;
        admin.active = false;
        admin.displayName = "Admin Inativo";
        admin.email = "inactive@brasildrama.com.br";
        when(operators.findById(admin.id)).thenReturn(Optional.of(admin));
        Principal principal = () -> admin.id.toString();
        var api = new AdminAuditApi(operators, logs);

        assertThat(api.latest(principal, null, null).getStatusCode().value()).isEqualTo(403);
        assertThat(api.actions(principal).getStatusCode().value()).isEqualTo(403);
        assertThat(api.actors(principal).getStatusCode().value()).isEqualTo(403);

        verify(operators, times(3)).findById(admin.id);
        verifyNoInteractions(logs);
    }
}
