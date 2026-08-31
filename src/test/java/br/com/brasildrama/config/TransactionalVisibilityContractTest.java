package br.com.brasildrama.config;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O Spring, na configuração padrão baseada em proxy, aplica {@code @Transactional}
 * apenas a métodos <b>públicos</b>: {@code AnnotationTransactionAttributeSource}
 * é construído com {@code publicMethodsOnly = true}. Em método package-private a
 * anotação é silenciosamente ignorada — não há aviso, log ou falha de contexto.
 *
 * <p>Isso não é teórico neste repositório. {@code WalletService.unlock} era
 * package-private e chama {@code ledger.lockUser(userId)}, um lock pessimista que
 * sem transação é liberado no instante seguinte: dois desbloqueios simultâneos
 * podiam debitar duas vezes ou gravar direito de acesso sem débito.
 *
 * <p>Este teste lê o código-fonte em vez de reflexão porque o alvo é a intenção
 * escrita, não o bytecode: queremos falhar quando alguém <i>escrever</i> uma
 * anotação que não vai valer, e não descobrir em produção.
 */
class TransactionalVisibilityContractTest {

    /**
     * Domínios já auditados. Os demais estão registrados como dívida conhecida e
     * entram aqui conforme forem cobertos por teste — habilitar uma transação que
     * nunca funcionou muda comportamento de rollback e precisa de rede antes.
     */
    private static final List<String> AUDITED = List.of(
        "wallet", "rewards", "monetization"
    );

    @Test
    void transactionalMethodsInAuditedDomainsArePublic() {
        var offenders = new ArrayList<String>();

        for (String domain : AUDITED) {
            Path root = Path.of("src/main/java/br/com/brasildrama", domain);
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> collectOffenders(path, offenders));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        assertThat(offenders)
            .as("@Transactional em método não-público é ignorado pelo Spring; torne o método público")
            .isEmpty();
    }

    private static void collectOffenders(Path path, List<String> offenders) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        for (int i = 0; i < lines.size() - 1; i++) {
            if (!lines.get(i).trim().equals("@Transactional")) continue;
            String signature = lines.get(i + 1).trim();
            if (signature.startsWith("public ")) continue;
            offenders.add(path.getFileName() + ":" + (i + 2) + " -> " + signature);
        }
    }

    /** Garante que a premissa do teste continua verdadeira se o Spring mudar. */
    @Test
    void springStillRestrictsTransactionalToPublicMethods() {
        var source = new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource();
        var method = ProbeService.class.getDeclaredMethods()[0];
        assertThat(Modifier.isPublic(method.getModifiers())).isFalse();
        assertThat(source.getTransactionAttribute(method, ProbeService.class))
            .as("Se isto deixar de ser nulo, o Spring passou a aceitar método não-público e este contrato pode ser relaxado")
            .isNull();
    }

    static class ProbeService {
        @Transactional
        void packagePrivateMethod() {}
    }
}
