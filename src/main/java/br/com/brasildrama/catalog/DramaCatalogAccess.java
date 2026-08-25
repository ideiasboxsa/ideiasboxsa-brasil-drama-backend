package br.com.brasildrama.catalog;

import org.springframework.stereotype.Service;

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
}
