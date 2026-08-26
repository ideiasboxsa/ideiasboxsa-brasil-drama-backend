package br.com.brasildrama.catalog;

import br.com.brasildrama.media.MediaStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class DramaCatalogAccess {
    private final DramaRepository dramas;

    public DramaCatalogAccess(DramaRepository dramas) {
        this.dramas = dramas;
    }

    public boolean exists(UUID dramaId) {
        return dramas.existsById(dramaId);
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
}
