package br.com.brasildrama.catalog;

import br.com.brasildrama.media.MediaStorageService;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class CatalogQueryService {
    public record HomeDrama(String dramaId, String title, String firstEpisodeId, String genre, String coverUrl) {}
    public record EpisodeAccess(String episodeId, int coinPrice, boolean free) {}

    private final DramaRepository dramas;
    private final EpisodeRepository episodes;
    private final MediaStorageService media;

    public CatalogQueryService(DramaRepository dramas, EpisodeRepository episodes, MediaStorageService media) {
        this.dramas = dramas;
        this.episodes = episodes;
        this.media = media;
    }

    public List<HomeDrama> homeDramas() {
        return dramas.findByStatusOrderByTitleAsc(DramaStatus.PUBLISHED).stream().map(d -> new HomeDrama(
            d.id.toString(),
            d.title,
            episodes.findByDramaIdOrderByNumberAsc(d.id).stream().findFirst().map(e -> e.id.toString()).orElse(null),
            d.genre,
            d.posterObjectKey == null || d.posterObjectKey.isBlank() ? d.coverUrl : media.readUrl(d.posterObjectKey)
        )).toList();
    }

    public Optional<EpisodeAccess> episodeAccess(UUID episodeId) {
        return episodes.findById(episodeId)
            .map(e -> new EpisodeAccess(e.id.toString(), e.coinPrice, e.free));
    }
}
