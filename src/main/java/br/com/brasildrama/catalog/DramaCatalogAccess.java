package br.com.brasildrama.catalog;

import br.com.brasildrama.media.MediaStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional
    public void attachEpisodeVideo(UUID dramaId, UUID episodeId, String objectKey) {
        var episode = episodes.findById(episodeId).filter(e -> e.dramaId.equals(dramaId)).orElseThrow();
        episode.videoObjectKey = objectKey;
        episode.videoUrl = null;
        episodes.save(episode);
    }
}
