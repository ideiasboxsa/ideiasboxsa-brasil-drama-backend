package br.com.brasildrama.home;

import br.com.brasildrama.catalog.CatalogQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/v1/admin/home/curation")
class AdminHomeCurationApi {
    private final HomePlacementRepository placements;
    private final CatalogQueryService catalog;

    AdminHomeCurationApi(HomePlacementRepository placements, CatalogQueryService catalog) {
        this.placements = placements;
        this.catalog = catalog;
    }

    @GetMapping
    HomeCurationView get() {
        var available = catalog.homeDramas();
        var byId = available.stream().collect(java.util.stream.Collectors.toMap(CatalogQueryService.HomeDrama::dramaId, item -> item));
        var selected = placements.findAllByOrderByPositionAsc().stream()
            .map(p -> byId.get(p.dramaId.toString())).filter(Objects::nonNull).map(this::item).toList();
        return new HomeCurationView(selected, available.stream().map(this::item).toList());
    }

    @PutMapping
    @Transactional
    ResponseEntity<?> update(@Valid @RequestBody UpdateHomeCurationRequest request) {
        var ids = request.dramaIds();
        if (new HashSet<>(ids).size() != ids.size()) return ResponseEntity.badRequest().body(Map.of("code", "DUPLICATE_DRAMA"));
        var availableIds = catalog.homeDramas().stream().map(CatalogQueryService.HomeDrama::dramaId).collect(java.util.stream.Collectors.toSet());
        if (!availableIds.containsAll(ids.stream().map(UUID::toString).toList())) return ResponseEntity.badRequest().body(Map.of("code", "DRAMA_NOT_PUBLISHED"));

        placements.deleteAllInBatch();
        var rows = new ArrayList<HomePlacementEntity>();
        for (int index = 0; index < ids.size(); index++) rows.add(new HomePlacementEntity(ids.get(index), index + 1));
        placements.saveAllAndFlush(rows);
        return ResponseEntity.ok(get());
    }

    private HomeCurationItem item(CatalogQueryService.HomeDrama drama) {
        return new HomeCurationItem(UUID.fromString(drama.dramaId()), drama.title(), drama.genre(), drama.coverUrl());
    }

    record UpdateHomeCurationRequest(@NotNull @Size(max = 50) List<UUID> dramaIds) {}
    record HomeCurationItem(UUID dramaId, String title, String genre, String imageUrl) {}
    record HomeCurationView(List<HomeCurationItem> selected, List<HomeCurationItem> available) {}
}
