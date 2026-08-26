package br.com.brasildrama.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

record DashboardMetrics(
    long publishedDramas,
    long availableEpisodes,
    long registeredUsers,
    long activeUsers30d,
    long validatedPurchases,
    long activeSubscriptions
) {}

record DashboardAttention(
    String code,
    String title,
    String detail,
    long count,
    String severity,
    String href
) {}

record DashboardCatalogStatus(long draft, long ready, long published, long archived) {}

record AdminDashboardView(
    DashboardMetrics metrics,
    DashboardCatalogStatus catalog,
    List<DashboardAttention> attention
) {}

@RestController
class AdminDashboardApi {
    private final JdbcTemplate jdbc;

    AdminDashboardApi(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/admin/dashboard")
    AdminDashboardView dashboard() {
        var metrics = new DashboardMetrics(
            count("select count(*) from drama where status = 'PUBLISHED'"),
            count("""
                select count(*) from episode e
                join drama d on d.id = e.drama_id
                where d.status = 'PUBLISHED'
                  and coalesce(nullif(e.video_object_key, ''), nullif(e.video_url, '')) is not null
                """),
            count("select count(*) from app_user"),
            count("select count(distinct user_id) from playback_history where updated_at >= now() - interval '30 days'"),
            count("select count(*) from google_play_purchase"),
            count("""
                select count(*) from google_play_purchase
                where product_type = 'SUBSCRIPTION' and expires_at > now()
                """)
        );

        var catalog = new DashboardCatalogStatus(
            count("select count(*) from drama where status = 'DRAFT'"),
            count("select count(*) from drama where status = 'READY'"),
            metrics.publishedDramas(),
            count("select count(*) from drama where status = 'ARCHIVED'")
        );

        var attention = new ArrayList<DashboardAttention>();
        addIfPositive(attention, new DashboardAttention(
            "DRAMA_METADATA_PENDING",
            "Séries em rascunho",
            "Revise metadados e avance o conteúdo para pronto.",
            catalog.draft(),
            "WARNING",
            "/content/dramas"
        ));
        addIfPositive(attention, new DashboardAttention(
            "DRAMA_MEDIA_PENDING",
            "Imagens editoriais pendentes",
            "Séries não arquivadas sem poster ou backdrop.",
            count("""
                select count(*) from drama
                where status <> 'ARCHIVED'
                  and (poster_object_key is null or backdrop_object_key is null)
                """),
            "WARNING",
            "/media"
        ));
        addIfPositive(attention, new DashboardAttention(
            "EPISODE_VIDEO_PENDING",
            "Vídeos de episódios pendentes",
            "Episódios sem arquivo de reprodução associado.",
            count("""
                select count(*) from episode e
                join drama d on d.id = e.drama_id
                where d.status <> 'ARCHIVED'
                  and coalesce(nullif(e.video_object_key, ''), nullif(e.video_url, '')) is null
                """),
            "CRITICAL",
            "/content/dramas"
        ));
        addIfPositive(attention, new DashboardAttention(
            "GOOGLE_ACK_PENDING",
            "Compras aguardando confirmação",
            "Recibos Google Play validados, mas ainda não reconhecidos pelo cliente.",
            count("select count(*) from google_play_purchase where acknowledged = false"),
            "CRITICAL",
            "/monetization"
        ));

        return new AdminDashboardView(metrics, catalog, attention);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private static void addIfPositive(List<DashboardAttention> items, DashboardAttention item) {
        if (item.count() > 0) items.add(item);
    }
}
