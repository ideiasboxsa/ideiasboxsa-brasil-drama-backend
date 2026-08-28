package br.com.brasildrama.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AdminAuditAuthorizationContractTest {
    @Test
    void rejectsMissingPrincipalAcrossAuditEndpointsWithoutRepositoryAccess() {
        var operators = mock(AdminOperatorRepository.class);
        var logs = mock(AdminAuditLogRepository.class);
        var api = new AdminAuditApi(operators, logs);

        assertThat(api.latest(null, null, null).getStatusCode().value()).isEqualTo(403);
        assertThat(api.actions(null).getStatusCode().value()).isEqualTo(403);
        assertThat(api.actors(null).getStatusCode().value()).isEqualTo(403);

        verifyNoInteractions(logs);
        verifyNoInteractions(operators);
    }
}
