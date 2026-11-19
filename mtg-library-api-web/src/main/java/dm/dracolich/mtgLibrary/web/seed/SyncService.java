package dm.dracolich.mtgLibrary.web.seed;

import dm.dracolich.mtgLibrary.integration.client.ScryfallClient;
import dm.dracolich.mtgLibrary.integration.dto.ScryfallBulkDataDto;
import dm.dracolich.mtgLibrary.web.config.MongoIndexes;
import dm.dracolich.mtgLibrary.web.entity.SyncRunEntity;
import dm.dracolich.mtgLibrary.web.entity.SyncRunEntity.Outcome;
import dm.dracolich.mtgLibrary.web.entity.SyncRunEntity.Trigger;
import dm.dracolich.mtgLibrary.web.repository.CardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * When the library syncs from Scryfall (Ixian-Works/dracolich-mtg-library-api#13).
 *
 * <ul>
 *   <li>At startup, when there are no cards yet.</li>
 *   <li>Every day, on {@code dracolich.sync.cron} (04:30 UTC unless set; {@code "-"} turns it off).
 *       A scheduled run is skipped, and says so in the log, when Scryfall's cards and rulings files
 *       have the same {@code updated_at} as at the last successful run.</li>
 *   <li>On {@code POST /sync}, which always runs.</li>
 * </ul>
 *
 * <p>Every run holds {@link SyncLock}, so with several replicas only one syncs. Each run, and each
 * skip, is recorded in {@code sync_runs}. Whether anything changed is judged against the last success
 * in {@code sync_runs}, read once the lease is held, so it counts every replica's runs. The last
 * success shows in the {@code info} actuator endpoint, refreshed from the database every five
 * minutes. A run cut off part way is marked failed by the next run.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncService {

    private final ScryfallSeeder seeder;
    private final ScryfallClient scryfallClient;
    private final SyncLock lock;
    private final ReactiveMongoTemplate mongoTemplate;
    private final CardRepository cardRepository;
    private final MongoIndexes mongoIndexes;

    private final AtomicReference<SyncRunEntity> lastSuccess = new AtomicReference<>();

    /** Thrown for a manual run while another is going on, here or in another replica. */
    public static class SyncInProgressException extends IllegalStateException {
        public SyncInProgressException(String message) {
            super(message);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        // Indexes first: the seed's upserts look up every card and art record by its key.
        mongoIndexes.ensureAll()
                .then(loadLastSuccess())
                .then(cardRepository.count())
                .flatMap(count -> {
                    if (count > 0) {
                        log.info("Database already seeded ({} cards). Skipping the startup sync.", count);
                        return Mono.empty();
                    }
                    log.info("Database empty. Starting Scryfall seed...");
                    return run(Trigger.STARTUP, true);
                })
                .subscribe(unused -> {}, error -> log.error("Startup sync failed", error));
    }

    /** Keeps the {@code info} endpoint's copy current with runs made by other replicas. */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT5M")
    public void refreshLastSuccess() {
        loadLastSuccess().subscribe(unused -> {}, error -> log.warn("Could not read the last sync: {}", error.getMessage()));
    }

    @Scheduled(cron = "${dracolich.sync.cron:0 30 4 * * *}", zone = "UTC")
    public void onSchedule() {
        run(Trigger.SCHEDULE, false)
                .subscribe(unused -> {}, error -> log.error("Scheduled sync failed", error));
    }

    /**
     * Runs a sync unless another is running anywhere. With {@code force} false it is also skipped
     * when Scryfall's files haven't changed since the last success. Emits the run's record.
     */
    public Mono<SyncRunEntity> run(Trigger trigger, boolean force) {
        // The lease is given back before the run's record is emitted, whatever the outcome.
        return Mono.usingWhen(lock.acquire(),
                held -> held ? runHolding(trigger, force) : skippedLocked(trigger),
                held -> held ? lock.release() : Mono.empty(),
                (held, error) -> held ? lock.release() : Mono.empty(),
                held -> held ? lock.release() : Mono.empty());
    }

    private Mono<SyncRunEntity> skippedLocked(Trigger trigger) {
        log.info("A Scryfall sync is already running in another replica; this {} run does nothing.", trigger);
        return record(skipped(trigger, Outcome.SKIPPED_LOCKED, null, null));
    }

    private Mono<SyncRunEntity> runHolding(Trigger trigger, boolean force) {
        // With the lease held, nothing else is running: a RUNNING record left behind was cut off.
        // And the last success is read from the database, since another replica may have run since
        // this one last looked.
        return markCutOffRuns()
                .then(loadLastSuccess())
                .then(files())
                .flatMap(files -> {
            if (!force && unchanged(files)) {
                log.info("Scryfall's files haven't changed since the last sync (cards {}, rulings {}); this {} run does nothing.",
                        files.cards(), files.rulings(), trigger);
                return record(skipped(trigger, Outcome.SKIPPED_UNCHANGED, files.cards(), files.rulings()));
            }
            return syncAndRecord(trigger, files);
        });
    }

    /** The last successful run, for the {@code info} endpoint; null before the first. */
    public SyncRunEntity lastSuccess() {
        return lastSuccess.get();
    }

    record Files(String cards, String rulings) { }

    private Mono<Files> files() {
        return scryfallClient.getBulkDataList().map(list -> {
            List<ScryfallBulkDataDto> data = list.data() != null ? list.data() : List.of();
            return new Files(updatedAt(data, "oracle_cards"), updatedAt(data, "rulings"));
        });
    }

    private static String updatedAt(List<ScryfallBulkDataDto> data, String type) {
        return data.stream().filter(bd -> type.equals(bd.type())).map(ScryfallBulkDataDto::updatedAt)
                .findFirst().orElse(null);
    }

    private boolean unchanged(Files files) {
        SyncRunEntity last = lastSuccess.get();
        return last != null && files.cards() != null && files.cards().equals(last.getCardsFile())
                && files.rulings() != null && files.rulings().equals(last.getRulingsFile());
    }

    private Mono<SyncRunEntity> syncAndRecord(Trigger trigger, Files files) {
        SyncRunEntity run = SyncRunEntity.builder()
                .trigger(trigger).outcome(Outcome.RUNNING).startedAt(Instant.now())
                .cardsFile(files.cards()).rulingsFile(files.rulings())
                .build();
        return mongoTemplate.insert(run)
                .flatMap(started -> seeder.sync()
                        .flatMap(counts -> {
                            started.setOutcome(Outcome.SUCCEEDED);
                            started.setEndedAt(Instant.now());
                            started.setSets(counts.sets());
                            started.setSymbols(counts.symbols());
                            started.setCards(counts.cards());
                            started.setRulings(counts.rulings());
                            started.setCardsGoneFromScryfall(counts.cardsGoneFromScryfall());
                            return mongoTemplate.save(started).doOnNext(lastSuccess::set);
                        })
                        .onErrorResume(error -> {
                            if (error instanceof ScryfallSeeder.AlreadyRunningException) {
                                // Another sync is running in this process.
                                return mongoTemplate.remove(started).then(Mono.error(
                                        new SyncInProgressException("Sync already in progress.")));
                            }
                            started.setOutcome(Outcome.FAILED);
                            started.setEndedAt(Instant.now());
                            started.setError(error.getMessage());
                            return mongoTemplate.save(started).then(Mono.error(error));
                        }));
    }

    private static SyncRunEntity skipped(Trigger trigger, Outcome outcome, String cardsFile, String rulingsFile) {
        Instant now = Instant.now();
        return SyncRunEntity.builder().trigger(trigger).outcome(outcome).startedAt(now).endedAt(now)
                .cardsFile(cardsFile).rulingsFile(rulingsFile).build();
    }

    private Mono<SyncRunEntity> record(SyncRunEntity run) {
        return mongoTemplate.insert(run);
    }

    /** Records left RUNNING by a run that was cut off (its process died or its caller went away). */
    Mono<Void> markCutOffRuns() {
        return mongoTemplate.updateMulti(Query.query(Criteria.where("outcome").is(Outcome.RUNNING)),
                        new Update()
                                .set("outcome", Outcome.FAILED)
                                .set("ended_at", Instant.now())
                                .set("error", "Cut off before it finished."),
                        SyncRunEntity.class)
                .doOnNext(result -> {
                    if (result.getModifiedCount() > 0) {
                        log.warn("Marked {} sync runs that were cut off as failed.", result.getModifiedCount());
                    }
                })
                .then();
    }

    Mono<Void> loadLastSuccess() {
        Query last = Query.query(Criteria.where("outcome").is(Outcome.SUCCEEDED))
                .with(Sort.by(Sort.Direction.DESC, "ended_at")).limit(1);
        return mongoTemplate.findOne(last, SyncRunEntity.class).doOnNext(lastSuccess::set).then();
    }
}
