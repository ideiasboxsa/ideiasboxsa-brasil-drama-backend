package br.com.brasildrama.catalog;

import br.com.brasildrama.media.MediaStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class DramaCatalogAccess {
    private final DramaRepository dramas;
    private final EpisodeRepository episodes;

    public DramaCatalogAccess(DramaRepository dramas, EpisodeRepository episodes) {
        this.dramas = dramas;
        this.episodes = episodes;
    }

    public boolean exists(UUID dramaId) {
        return dramas.existsById(dramaId);
    }

    public boolean episodeBelongsToDrama(UUID dramaId, UUID episodeId) {
        return episodes.findById(episodeId).map(e -> e.dramaId.equals(dramaId)).orElse(false);
    }

    @Transactional
    public void attachImage(UUID dramaId, MediaStorageService.ImageKind kind, String objectKey) {
        var drama = dramas.findById(dramaId).orElseThrow();
        switch (kind) {
            case POSTER -> drama.posterObjectKey = objectKey;
            case BACKDROP -> drama.backdropObjectKey = objectKey;
        }
        dramas.save(drama);
    }

    /**
     * Vincula o objeto ao episódio e devolve a chave que estava lá antes, ou
     * {@code null} se não havia nenhuma.
     *
     * <p>O retorno existe porque quem chama precisa apagar o objeto substituído.
     * Antes disto, trocar o MP4 de um episódio deixava o anterior no bucket sem
     * referência alguma — comprovado em DEV com dois objetos e só o último ligado
     * ao episódio. A remoção acontece depois do commit, nunca aqui dentro: se o
     * S3 falhasse dentro da transação, o rollback devolveria o episódio à chave
     * antiga já apagada.
     */
    @Transactional
    public String attachEpisodeVideo(UUID dramaId, UUID episodeId, String objectKey) {
        var episode = episodes.findById(episodeId).filter(e -> e.dramaId.equals(dramaId)).orElseThrow();
        var previousObjectKey = episode.videoObjectKey;
        episode.videoObjectKey = objectKey;
        episode.videoUrl = null;
        episodes.save(episode);
        return Objects.equals(previousObjectKey, objectKey) ? null : previousObjectKey;
    }

    /**
     * Desfaz o vínculo do vídeo, devolvendo o episódio ao estado "vídeo pendente".
     * Sem isto não havia como retirar um MP4 defeituoso: só trocá-lo por outro.
     */
    @Transactional
    public String detachEpisodeVideo(UUID dramaId, UUID episodeId) {
        var episode = episodes.findById(episodeId).filter(e -> e.dramaId.equals(dramaId)).orElseThrow();
        var previousObjectKey = episode.videoObjectKey;
        episode.videoObjectKey = null;
        episode.videoUrl = null;
        episodes.save(episode);
        return previousObjectKey;
    }
}
