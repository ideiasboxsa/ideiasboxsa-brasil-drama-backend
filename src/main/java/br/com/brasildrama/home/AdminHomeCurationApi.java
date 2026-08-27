package br.com.brasildrama.home;

import br.com.brasildrama.catalog.CatalogQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/admin/home/curation")
class AdminHomeCurationApi {
    private static final Pattern SECTION_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{1,79}");
    private final HomePlacementRepository placements;
    private final CatalogQueryService catalog;
    private final HomeController discovery;

    AdminHomeCurationApi(HomePlacementRepository placements, CatalogQueryService catalog, HomeController discovery) {
        this.placements = placements;
        this.catalog = catalog;
        this.discovery = discovery;
    }

    @GetMapping
    HomeCurationView get() {
        var available = catalog.homeDramas();
        var byId = available.stream().collect(Collectors.toMap(CatalogQueryService.HomeDrama::dramaId, item -> item));
        var rows = placements.findAllByOrderBySectionPositionAscPositionAsc();
        var selected = rows.stream().map(p -> byId.get(p.dramaId.toString())).filter(Objects::nonNull).map(this::item).toList();

        var grouped = new LinkedHashMap<String, List<HomePlacementEntity>>();
        rows.stream().filter(p -> byId.containsKey(p.dramaId.toString()))
            .forEach(p -> grouped.computeIfAbsent(p.sectionKey, ignored -> new ArrayList<>()).add(p));
        var sections = grouped.values().stream().map(sectionRows -> {
            var first = sectionRows.getFirst();
            return new HomeCurationSection(first.sectionKey, first.sectionTitle,
                sectionRows.stream().map(p -> item(byId.get(p.dramaId.toString()))).toList());
        }).toList();
        var heroDramaId = rows.stream().filter(p -> p.hero && byId.containsKey(p.dramaId.toString()))
            .map(p -> p.dramaId).findFirst().orElseGet(() -> selected.isEmpty() ? null : selected.getFirst().dramaId());

        var curatedTypes = sections.stream().map(HomeCurationSection::type).collect(Collectors.toSet());
        var automaticSections = discovery.home(null, null).sections().stream()
            .filter(section -> !curatedTypes.contains(section.type()))
            .map(section -> new HomeAutomationSection(
                section.type(),
                section.title(),
                section.items().stream()
                    .map(homeItem -> byId.get(homeItem.dramaId()))
                    .filter(Objects::nonNull)
                    .map(this::item)
                    .toList()
            ))
            .filter(section -> !section.items().isEmpty())
            .toList();
        var automation = new HomeAutomationView(
            automaticSections,
            List.of(
                "Preferências de gênero do usuário nos últimos 90 dias",
                "Reproduções únicas dos últimos 30 dias",
                "Data de publicação do catálogo",
                "Gêneros presentes nas séries publicadas"
            )
        );

        return new HomeCurationView(
            heroDramaId,
            sections,
            selected,
            available.stream().map(this::item).toList(),
            automation
        );
    }

    @PutMapping
    @Transactional
    ResponseEntity<?> update(@Valid @RequestBody UpdateHomeCurationRequest request) {
        var normalized = normalize(request);
        if (normalized.isEmpty()) return ResponseEntity.badRequest().body(Map.of("code", "HOME_SECTIONS_REQUIRED"));
        if (normalized.size() > 10) return ResponseEntity.badRequest().body(Map.of("code", "TOO_MANY_SECTIONS"));

        var allIds = normalized.stream().flatMap(section -> section.dramaIds().stream()).toList();
        if (allIds.size() > 50) return ResponseEntity.badRequest().body(Map.of("code", "TOO_MANY_DRAMAS"));
        if (new HashSet<>(allIds).size() != allIds.size()) return ResponseEntity.badRequest().body(Map.of("code", "DUPLICATE_DRAMA"));
        if (normalized.stream().map(HomeSectionRequest::type).distinct().count() != normalized.size())
            return ResponseEntity.badRequest().body(Map.of("code", "DUPLICATE_SECTION_TYPE"));
        if (normalized.stream().anyMatch(section -> !SECTION_TYPE.matcher(section.type()).matches()))
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_SECTION_TYPE"));

        var availableIds = catalog.homeDramas().stream().map(CatalogQueryService.HomeDrama::dramaId)
            .map(UUID::fromString).collect(Collectors.toSet());
        if (!availableIds.containsAll(allIds)) return ResponseEntity.badRequest().body(Map.of("code", "DRAMA_NOT_PUBLISHED"));

        var heroDramaId = request.heroDramaId() != null ? request.heroDramaId() : allIds.getFirst();
        if (!allIds.contains(heroDramaId)) return ResponseEntity.badRequest().body(Map.of("code", "HERO_NOT_IN_SECTIONS"));

        placements.deleteAllInBatch();
        var rows = new ArrayList<HomePlacementEntity>();
        for (int sectionIndex = 0; sectionIndex < normalized.size(); sectionIndex++) {
            var section = normalized.get(sectionIndex);
            for (int itemIndex = 0; itemIndex < section.dramaIds().size(); itemIndex++) {
                var dramaId = section.dramaIds().get(itemIndex);
                rows.add(new HomePlacementEntity(dramaId, itemIndex + 1, section.type(), section.title().trim(),
                    sectionIndex + 1, dramaId.equals(heroDramaId)));
            }
        }
        placements.saveAllAndFlush(rows);
        return ResponseEntity.ok(get());
    }

    private List<HomeSectionRequest> normalize(UpdateHomeCurationRequest request) {
        if (request.sections() != null && !request.sections().isEmpty()) return request.sections();
        if (request.dramaIds() == null || request.dramaIds().isEmpty()) return List.of();
        return List.of(new HomeSectionRequest("FOR_YOU", "Para você", request.dramaIds()));
    }

    private HomeCurationItem item(CatalogQueryService.HomeDrama drama) {
        return new HomeCurationItem(UUID.fromString(drama.dramaId()), drama.title(), drama.genre(), drama.coverUrl());
    }

    record UpdateHomeCurationRequest(UUID heroDramaId, List<@Valid HomeSectionRequest> sections, List<UUID> dramaIds) {}
    record HomeSectionRequest(@NotBlank @Size(max = 80) String type, @NotBlank @Size(max = 120) String title,
                              @NotNull @Size(min = 1, max = 20) List<UUID> dramaIds) {}
    record HomeCurationItem(UUID dramaId, String title, String genre, String imageUrl) {}
    record HomeCurationSection(String type, String title, List<HomeCurationItem> items) {}
    record HomeAutomationSection(String type, String title, List<HomeCurationItem> items) {}
    record HomeAutomationView(List<HomeAutomationSection> sections, List<String> signals) {}
    record HomeCurationView(UUID heroDramaId, List<HomeCurationSection> sections,
                            List<HomeCurationItem> selected, List<HomeCurationItem> available,
                            HomeAutomationView automation) {}
}
