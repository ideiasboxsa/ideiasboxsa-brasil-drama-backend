package br.com.brasildrama.media;

import br.com.brasildrama.catalog.DramaCatalogAccess;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ciclo de vida do objeto de vídeo no bucket. Cada MP4 custa armazenamento todo mês,
 * e a única coisa que aponta para ele é a coluna do episódio: objeto sem referência é
 * despesa permanente que ninguém mais consegue localizar pela interface.
 */
class EpisodeVideoLifecycleContractTest {
    private final MediaStorageService storage = mock(MediaStorageService.class);
    private final DramaCatalogAccess catalog = mock(DramaCatalogAccess.class);
    private final AdminMediaApi api = new AdminMediaApi(storage, catalog);
    private final UUID dramaId = UUID.randomUUID();
    private final UUID episodeId = UUID.randomUUID();

    /** Trocar o MP4 deixava o anterior no bucket, sem referência e cobrado para sempre. */
    @Test
    void replacingTheVideoPurgesTheObjectItReplaced() {
        var previousKey = key("v1.mp4");
        var newKey = key("v2.mp4");
        when(catalog.episodeBelongsToDrama(dramaId, episodeId)).thenReturn(true);
        when(storage.completeEpisodeVideo(any(), any(), anyString(), anyString(), any()))
            .thenReturn(new MediaStorageService.StoredVideo(newKey, "video/mp4", 1024, "https://cdn/v2.mp4"));
        when(catalog.attachEpisodeVideo(dramaId, episodeId, newKey)).thenReturn(previousKey);

        var response = api.completeVideo(dramaId, episodeId, completeRequest(newKey));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(storage).deleteObject(previousKey);
    }

    /**
     * Reenviar o mesmo objeto não pode apagá-lo: {@code attachEpisodeVideo} devolve
     * nulo quando a chave não mudou, e {@code deleteObject} ignora nulo.
     */
    @Test
    void reuploadingTheSameKeyDoesNotDeleteTheLiveVideo() {
        var sameKey = key("v1.mp4");
        when(catalog.episodeBelongsToDrama(dramaId, episodeId)).thenReturn(true);
        when(storage.completeEpisodeVideo(any(), any(), anyString(), anyString(), any()))
            .thenReturn(new MediaStorageService.StoredVideo(sameKey, "video/mp4", 1024, "https://cdn/v1.mp4"));
        when(catalog.attachEpisodeVideo(dramaId, episodeId, sameKey)).thenReturn(null);

        api.completeVideo(dramaId, episodeId, completeRequest(sameKey));

        verify(storage).deleteObject(null);
        verify(storage, never()).deleteObject(sameKey);
    }

    /**
     * Antes só existia troca. Um MP4 defeituoso não podia ser retirado e, como a
     * publicação exige vídeo em todos os episódios, um arquivo ruim segurava a série.
     */
    @Test
    void removingTheVideoDetachesAndPurges() {
        var removedKey = key("ruim.mp4");
        when(catalog.episodeBelongsToDrama(dramaId, episodeId)).thenReturn(true);
        when(catalog.detachEpisodeVideo(dramaId, episodeId)).thenReturn(removedKey);

        var response = api.deleteVideo(dramaId, episodeId);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(storage).deleteObject(removedKey);
    }

    @Test
    void removingTheVideoOfAnotherDramaIsNotFound() {
        when(catalog.episodeBelongsToDrama(dramaId, episodeId)).thenReturn(false);

        assertThat(api.deleteVideo(dramaId, episodeId).getStatusCode().value()).isEqualTo(404);
        verify(catalog, never()).detachEpisodeVideo(any(), any());
        verify(storage, never()).deleteObject(anyString());
    }

    private String key(String name) {
        return "dramas/%s/episodes/%s/video/%s".formatted(dramaId, episodeId, name);
    }

    private static AdminMediaApi.CompleteVideoRequest completeRequest(String objectKey) {
        return new AdminMediaApi.CompleteVideoRequest("upload-1", objectKey,
            List.of(new AdminMediaApi.UploadedPartRequest(1, "\"etag-1\"")));
    }
}
