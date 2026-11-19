package dm.dracolich.mtgLibrary.web.controller;

import dm.dracolich.mtgLibrary.web.entity.SyncRunEntity;
import dm.dracolich.mtgLibrary.web.seed.SyncService;
import dm.dracolich.forge.response.DmdResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("sync")
@RequiredArgsConstructor
public class SyncController {

    private final SyncService syncService;

    /** Runs a sync now, whether or not Scryfall's files changed. 409 while another is running. */
    @PostMapping
    public Mono<DmdResponse<String>> sync() {
        return syncService.run(SyncRunEntity.Trigger.MANUAL, true)
                .map(run -> run.getOutcome() == SyncRunEntity.Outcome.SKIPPED_LOCKED
                        ? DmdResponse.of((String) null, HttpStatus.CONFLICT, "Sync already in progress.")
                        : DmdResponse.of("Scryfall sync complete.", HttpStatus.OK, "Scryfall sync complete."))
                .onErrorResume(SyncService.SyncInProgressException.class, e ->
                        Mono.just(DmdResponse.of((String) null, HttpStatus.CONFLICT, e.getMessage())));
    }
}
