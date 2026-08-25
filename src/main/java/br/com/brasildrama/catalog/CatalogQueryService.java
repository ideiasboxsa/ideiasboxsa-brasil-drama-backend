package br.com.brasildrama.catalog;

import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class CatalogQueryService {
    public record HomeDrama(String dramaId, String firstEpisodeId, String genre, String coverUrl) {}

    private final DramaRepository dramas;
    private final EpisodeRepository episodes;

    public CatalogQueryService(DramaRepository dramas, EpisodeRepository episodes) {
        this.dramas = dramas;
        this.episodes = episodes;
    }

    public List<HomeDrama> homeDramas() {
        return dramas.findAllByOrderByTitleAsc().stream().map(d -> new HomeDrama(
            d.id.toString(),
            episodes.findByDramaIdOrderByNumberAsc(d.id).stream().findFirst().map(e -> e.id.toString()).orElse(null),
            d.genre,
            d.coverUrl
        )).toList();
    }
}
