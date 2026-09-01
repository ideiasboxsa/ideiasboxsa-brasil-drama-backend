package br.com.brasildrama.catalog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Toda tabela que referencia {@code episode(id)} precisa ter um destino decidido no
 * momento da exclusão: ou o banco resolve com {@code CASCADE}, ou
 * {@link EpisodeDeletionService} resolve em SQL explícito.
 *
 * <p>Isto não é zelo abstrato. O padrão do Liquibase quando {@code onDelete} é
 * omitido é {@code NO ACTION}, e quatro das seis referências a {@code episode} foram
 * escritas assim: o primeiro episódio com histórico de reprodução transformava o
 * {@code DELETE} em violação de integridade e 500, sem nada no código de exclusão
 * apontando para a causa. O teste lê os changelogs para que a próxima tabela com
 * {@code episode_id} falhe aqui, e não em produção.
 */
class EpisodeForeignKeyCoverageContractTest {

    private static final Path CHANGELOGS = Path.of("src/main/resources/db/changelog");
    private static final Path DELETION_SERVICE = Path.of("src/main/java/br/com/brasildrama/catalog/EpisodeDeletionService.java");

    /** Estilo em linha: {@code references: episode(id)} dentro das constraints da coluna. */
    private static final Pattern INLINE_REFERENCE = Pattern.compile("references:\\s*episode\\s*\\(\\s*id\\s*\\)");
    /** Estilo em bloco: {@code addForeignKeyConstraint} com {@code referencedTableName}. */
    private static final Pattern BLOCK_REFERENCE = Pattern.compile("referencedTableName:\\s*episode\\s*$");
    private static final Pattern TABLE_NAME = Pattern.compile("\\b(?:base)?[tT]ableName:\\s*([a-z_]+)");

    @Test
    void everyForeignKeyToEpisodeIsEitherCascadeOrHandledOnDelete() {
        var references = collectReferences();

        assertThat(references)
            .as("nenhuma referência a episode(id) encontrada — o scanner deve ter deixado de casar com o formato dos changelogs")
            .isNotEmpty();

        var serviceCode = codeLinesOf(DELETION_SERVICE);
        var uncovered = new ArrayList<String>();
        references.forEach((table, cascade) -> {
            if (cascade) return;
            if (serviceCode.contains(table)) return;
            uncovered.add(table);
        });

        assertThat(uncovered)
            .as("FK para episode(id) sem CASCADE e sem tratamento em EpisodeDeletionService: o DELETE de episódio vai virar 500")
            .isEmpty();
    }

    /**
     * O contrário do teste acima: garante que o scanner realmente enxerga as seis
     * referências conhecidas. Sem isto, um regex que parasse de casar deixaria o
     * contrato passando vazio.
     */
    @Test
    void knownReferencesAreAllVisibleToTheScanner() {
        var references = collectReferences();

        assertThat(references).containsOnlyKeys(
            "playback_history", "episode_entitlement", "episode_completion",
            "rewarded_ad_session", "playback_event", "content_event"
        );
        assertThat(references.get("playback_event")).as("playback_event é CASCADE no changelog 018").isTrue();
        assertThat(references.get("content_event")).as("content_event é CASCADE no changelog 036").isTrue();
        assertThat(references.get("episode_entitlement")).as("direito de acesso pago não pode cascatear").isFalse();
    }

    /** Tabela que referencia {@code episode} -> a FK resolve sozinha via CASCADE. */
    private static Map<String, Boolean> collectReferences() {
        var references = new LinkedHashMap<String, Boolean>();
        try (Stream<Path> files = Files.walk(CHANGELOGS)) {
            files.filter(path -> path.toString().endsWith(".yaml")).sorted()
                .forEach(path -> collectFrom(path, references));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return references;
    }

    private static void collectFrom(Path path, Map<String, Boolean> references) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        // O nome da tabela vem do bloco de mudança que envolve a coluna — createTable,
        // addColumn ou addForeignKeyConstraint —, por isso a última declaração vista
        // acima da linha da FK é a tabela de origem.
        String enclosingTable = null;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            var tableMatcher = TABLE_NAME.matcher(line);
            if (tableMatcher.find()) enclosingTable = tableMatcher.group(1);

            boolean inline = INLINE_REFERENCE.matcher(line).find();
            boolean block = BLOCK_REFERENCE.matcher(line.stripTrailing()).find();
            if (!inline && !block) continue;
            if (enclosingTable == null) continue;

            // logicalAnd, não Or: se a tabela tiver duas FKs para episode e só uma
            // cascatear, a outra ainda estoura o delete — então ela precisa aparecer
            // no serviço.
            references.merge(enclosingTable, hasCascadeNear(lines, index), Boolean::logicalAnd);
        }
    }

    /**
     * {@code onDelete}/{@code deleteCascade} ficam junto da declaração da FK, antes ou
     * depois dela conforme o estilo, então a janela cobre os dois lados.
     */
    private static boolean hasCascadeNear(List<String> lines, int index) {
        int from = Math.max(0, index - 6);
        int to = Math.min(lines.size(), index + 7);
        for (int cursor = from; cursor < to; cursor++) {
            String line = lines.get(cursor);
            if (line.contains("onDelete:") && line.toUpperCase().contains("CASCADE")) return true;
            if (line.contains("deleteCascade:") && line.contains("true")) return true;
        }
        return false;
    }

    /** O código do serviço sem os comentários — a menção precisa estar em SQL, não em Javadoc. */
    private static String codeLinesOf(Path path) {
        try {
            return Files.readAllLines(path).stream()
                .map(String::trim)
                .filter(line -> !line.startsWith("*") && !line.startsWith("/*") && !line.startsWith("//"))
                .reduce("", (left, right) -> left + "\n" + right);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
