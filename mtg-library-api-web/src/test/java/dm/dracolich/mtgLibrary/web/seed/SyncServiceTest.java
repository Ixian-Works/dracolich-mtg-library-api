package dm.dracolich.mtgLibrary.web.seed;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dm.dracolich.mtgLibrary.integration.client.ScryfallClient;
import dm.dracolich.mtgLibrary.integration.dto.ScryfallBulkDataDto;
import dm.dracolich.mtgLibrary.integration.dto.ScryfallCardDto;
import dm.dracolich.mtgLibrary.integration.dto.ScryfallListDto;
import dm.dracolich.mtgLibrary.integration.dto.ScryfallRulingDto;
import dm.dracolich.mtgLibrary.integration.dto.ScryfallSetDto;
import dm.dracolich.mtgLibrary.web.config.MongoIndexes;
import dm.dracolich.mtgLibrary.web.config.SyncInfoContributor;
import dm.dracolich.mtgLibrary.web.entity.CardEntity;
import dm.dracolich.mtgLibrary.web.entity.CollectionSetEntity;
import dm.dracolich.mtgLibrary.web.entity.SyncRunEntity;
import dm.dracolich.mtgLibrary.web.entity.SyncRunEntity.Outcome;
import dm.dracolich.mtgLibrary.web.entity.SyncRunEntity.Trigger;
import dm.dracolich.mtgLibrary.web.repository.*;
import dm.dracolich.mtgLibrary.web.support.EmbeddedMongo;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.info.Info;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The scheduled sync, its skip, its lock, its records and the rulings swap, against a real MongoDB. */
class SyncServiceTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AnnotationConfigApplicationContext context;
    private ReactiveMongoTemplate template;
    private final ScryfallClient scryfall = mock(ScryfallClient.class);
    private ScryfallSeeder seeder;
    private SyncService service;

    /** What the fake Scryfall serves; tests change them between runs. */
    private final AtomicReference<String> filesVersion = new AtomicReference<>("2026-11-19T09:00:00+00:00");
    private final List<ScryfallSetDto> sets = new ArrayList<>();
    private final List<ScryfallCardDto> cards = new ArrayList<>();
    private final AtomicReference<Flux<ScryfallRulingDto>> rulings = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        context = EmbeddedMongo.freshContext();
        template = context.getBean(ReactiveMongoTemplate.class);
        seeder = new ScryfallSeeder(scryfall, template,
                context.getBean(CollectionSetRepository.class), context.getBean(SymbolRepository.class),
                context.getBean(CardRepository.class), context.getBean(ArtPropertyRepository.class),
                context.getBean(RulingRepository.class), context.getBean(CardLayoutRepository.class),
                context.getBean(FrameEffectRepository.class), context.getBean(SubtypeRepository.class));
        service = new SyncService(seeder, scryfall, new SyncLock(template), template,
                context.getBean(CardRepository.class), context.getBean(MongoIndexes.class));
        context.getBean(MongoIndexes.class).ensureAll().block(WAIT);

        sets.add(set("s-mh3", "mh3", "Modern Horizons 3", "2024-06-14"));
        cards.add(card("o-bolt", "Lightning Bolt", "s-mh3", "mh3"));
        rulings.set(Flux.just(ruling("o-bolt", "Deals 3 damage.")));

        when(scryfall.getBulkDataList()).thenAnswer(i -> Mono.just(new ScryfallListDto<>("list", false, null, null, List.of(
                bulk("oracle_cards", "https://example.test/cards.json"),
                bulk("rulings", "https://example.test/rulings.json")))));
        when(scryfall.getSets()).thenAnswer(i -> Mono.just(new ScryfallListDto<>("list", false, null, null, List.copyOf(sets))));
        when(scryfall.getSymbology()).thenAnswer(i -> Mono.just(new ScryfallListDto<>("list", false, null, null, List.of())));
        when(scryfall.streamBulkCards(anyString())).thenAnswer(i -> Flux.fromIterable(List.copyOf(cards)));
        when(scryfall.streamBulkRulings(anyString())).thenAnswer(i -> rulings.get());
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private ScryfallBulkDataDto bulk(String type, String uri) {
        return new ScryfallBulkDataDto("b-" + type, type, type, null, uri, filesVersion.get(), 1L, "application/json", "gzip");
    }

    private static ScryfallSetDto set(String id, String code, String name, String released) {
        return new ScryfallSetDto(id, code, name, "expansion", released, null, null, null, 300, false, false, false, null);
    }

    private static ScryfallCardDto card(String oracleId, String name, String setId, String setCode) {
        return JSON.readValue("""
                {"id": "p-%s", "oracle_id": "%s", "name": "%s", "layout": "normal", "type_line": "Instant",
                 "set_id": "%s", "set": "%s", "rarity": "common", "collector_number": "1", "lang": "en"}
                """.formatted(oracleId, oracleId, name, setId, setCode), ScryfallCardDto.class);
    }

    private static ScryfallRulingDto ruling(String oracleId, String comment) {
        return new ScryfallRulingDto(oracleId, "wotc", "2026-01-01", comment);
    }

    private long count(String collection) {
        return template.count(new Query(), collection).block(WAIT);
    }

    private List<String> rulingComments() {
        return template.findAll(Document.class, "rulings").map(d -> d.getString("comment")).collectList().block(WAIT);
    }

    @Test
    void aSetReleasedAfterTheLastImportAppearsOnTheScheduleWithoutAnEndpoint() {
        assertThat(service.run(Trigger.STARTUP, true).block(WAIT).getOutcome()).isEqualTo(Outcome.SUCCEEDED);

        // Scryfall publishes new files with a new set in them.
        filesVersion.set("2026-11-20T09:00:00+00:00");
        sets.add(set("s-eoe", "eoe", "Edge of Eternities", "2025-08-01"));
        SyncRunEntity scheduled = service.run(Trigger.SCHEDULE, false).block(WAIT);

        assertThat(scheduled.getOutcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(scheduled.getSets()).isEqualTo(2);
        assertThat(template.findAll(CollectionSetEntity.class).map(CollectionSetEntity::getCode).collectList().block(WAIT))
                .contains("eoe");
    }

    @Test
    void aSecondScheduledRunOnTheSameFilesDoesNothingAndSaysSo() {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(SyncService.class)).addAppender(logs);

        service.run(Trigger.SCHEDULE, false).block(WAIT);
        SyncRunEntity second = service.run(Trigger.SCHEDULE, false).block(WAIT);

        assertThat(second.getOutcome()).isEqualTo(Outcome.SKIPPED_UNCHANGED);
        verify(scryfall, times(1)).getSets();
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.startsWith("Scryfall's files haven't changed since the last sync"));
        assertThat(template.findAll(SyncRunEntity.class).map(SyncRunEntity::getOutcome).collectList().block(WAIT))
                .containsExactlyInAnyOrder(Outcome.SUCCEEDED, Outcome.SKIPPED_UNCHANGED);
    }

    @Test
    void aManualRunAlwaysRuns() {
        service.run(Trigger.SCHEDULE, false).block(WAIT);

        assertThat(service.run(Trigger.MANUAL, true).block(WAIT).getOutcome()).isEqualTo(Outcome.SUCCEEDED);
        verify(scryfall, times(2)).getSets();
    }

    @Test
    void rulingsStayAvailableDuringARunAndAreReplacedAtTheEnd() {
        service.run(Trigger.STARTUP, true).block(WAIT);
        assertThat(rulingComments()).containsExactly("Deals 3 damage.");

        // The next run's rulings arrive only when the test says so.
        Sinks.One<ScryfallRulingDto> gate = Sinks.one();
        java.util.concurrent.atomic.AtomicBoolean downloading = new java.util.concurrent.atomic.AtomicBoolean();
        rulings.set(gate.asMono().flux().doOnSubscribe(subscription -> downloading.set(true)));
        filesVersion.set("2026-11-20T09:00:00+00:00");
        var running = service.run(Trigger.SCHEDULE, false).toFuture();

        // Wait until the run is in its rulings step, downloading the new ones.
        await().atMost(WAIT).untilTrue(downloading);
        // Mid-run: the old rulings are still there to read.
        assertThat(rulingComments()).containsExactly("Deals 3 damage.");

        gate.tryEmitValue(ruling("o-bolt", "Deals 3 damage to any target."));
        assertThat(running.join().getOutcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(rulingComments()).containsExactly("Deals 3 damage to any target.");
        assertThat(template.collectionExists("rulings_next").block(WAIT)).isFalse();
    }

    @Test
    void aRunThatFailsPartWayKeepsTheOldRulingsAndRecordsTheFailure() {
        service.run(Trigger.STARTUP, true).block(WAIT);
        rulings.set(Flux.error(new IllegalStateException("Scryfall went away")));
        filesVersion.set("2026-11-20T09:00:00+00:00");

        Throwable error = catchThrowable(() -> service.run(Trigger.SCHEDULE, false).block(WAIT));

        assertThat(error).hasMessageContaining("Scryfall went away");
        assertThat(rulingComments()).containsExactly("Deals 3 damage.");
        assertThat(template.findAll(SyncRunEntity.class).filter(r -> r.getOutcome() == Outcome.FAILED).single().block(WAIT).getError())
                .contains("Scryfall went away");
    }

    @Test
    void anotherReplicaHoldingTheLockMeansThisRunDoesNothing() {
        template.insert(new Document("_id", SyncLock.KEY).append("holder", "other-replica")
                .append("until", Instant.now().plus(Duration.ofHours(1))), SyncLock.COLLECTION).block(WAIT);

        SyncRunEntity run = service.run(Trigger.SCHEDULE, false).block(WAIT);

        assertThat(run.getOutcome()).isEqualTo(Outcome.SKIPPED_LOCKED);
        verify(scryfall, never()).getSets();
    }

    @Test
    void anExpiredLeaseIsTakenOver() {
        template.insert(new Document("_id", SyncLock.KEY).append("holder", "dead-replica")
                .append("until", Instant.now().minus(Duration.ofMinutes(1))), SyncLock.COLLECTION).block(WAIT);

        assertThat(service.run(Trigger.SCHEDULE, false).block(WAIT).getOutcome()).isEqualTo(Outcome.SUCCEEDED);
    }

    @Test
    void theLockGoesToOneHolderAtATime() {
        SyncLock first = new SyncLock(template);
        SyncLock second = new SyncLock(template);

        assertThat(first.acquire().block(WAIT)).isTrue();
        assertThat(second.acquire().block(WAIT)).isFalse();
        first.release().block(WAIT);
        assertThat(second.acquire().block(WAIT)).isTrue();
    }

    @Test
    void twoSyncsInOneProcessCantBothStart() {
        Sinks.One<ScryfallRulingDto> gate = Sinks.one();
        rulings.set(gate.asMono().flux());
        var first = seeder.sync().toFuture();
        await().atMost(WAIT).until(seeder::isSyncing);

        Throwable second = catchThrowable(() -> seeder.sync().block(WAIT));

        assertThat(second).isInstanceOf(IllegalStateException.class).hasMessage("Sync already in progress.");
        gate.tryEmitValue(ruling("o-bolt", "x"));
        first.join();
        assertThat(seeder.isSyncing()).isFalse();
    }

    @Test
    void cardsScryfallNoLongerHasAreCountedAndKept() {
        template.insert(CardEntity.builder().oracleId("o-gone").name("Removed Card").build()).block(WAIT);

        SyncRunEntity run = service.run(Trigger.MANUAL, true).block(WAIT);

        assertThat(run.getCardsGoneFromScryfall()).isEqualTo(1);
        assertThat(template.findAll(CardEntity.class).map(CardEntity::getName).collectList().block(WAIT))
                .contains("Removed Card", "Lightning Bolt");
    }

    @Test
    void theInfoEndpointShowsTheLastSuccessfulRun() {
        Info.Builder before = new Info.Builder();
        new SyncInfoContributor(service).contribute(before);
        assertThat(details(before)).containsEntry("lastSuccess", null);

        service.run(Trigger.MANUAL, true).block(WAIT);
        Info.Builder after = new Info.Builder();
        new SyncInfoContributor(service).contribute(after);

        assertThat(details(after))
                .containsEntry("trigger", Trigger.MANUAL)
                .containsEntry("cardsFile", "2026-11-19T09:00:00+00:00")
                .containsEntry("cards", 1L)
                .containsKey("lastSuccessEndedAt");
    }

    @Test
    void aReplicaThatDidntRunTheLastSyncStillSkipsWhenNothingChanged() {
        SyncService other = new SyncService(seeder, scryfall, new SyncLock(template), template,
                context.getBean(CardRepository.class), context.getBean(MongoIndexes.class));
        // Both replicas start with no success in memory.
        service.loadLastSuccess().block(WAIT);
        other.loadLastSuccess().block(WAIT);

        assertThat(service.run(Trigger.SCHEDULE, false).block(WAIT).getOutcome()).isEqualTo(Outcome.SUCCEEDED);
        // The other replica takes the lease next, on the same files.
        assertThat(other.run(Trigger.SCHEDULE, false).block(WAIT).getOutcome()).isEqualTo(Outcome.SKIPPED_UNCHANGED);
        verify(scryfall, times(1)).getSets();
        // And its info shows the success it didn't run itself.
        assertThat(other.lastSuccess().getCardsFile()).isEqualTo("2026-11-19T09:00:00+00:00");
    }

    @Test
    void aRunLeftRunningIsMarkedFailedByTheNextRun() {
        template.insert(SyncRunEntity.builder().trigger(Trigger.SCHEDULE).outcome(Outcome.RUNNING)
                .startedAt(Instant.now().minus(Duration.ofHours(3))).build()).block(WAIT);

        service.run(Trigger.MANUAL, true).block(WAIT);

        assertThat(template.findAll(SyncRunEntity.class).collectList().block(WAIT))
                .extracting(SyncRunEntity::getOutcome).containsExactlyInAnyOrder(Outcome.FAILED, Outcome.SUCCEEDED);
        assertThat(template.findAll(SyncRunEntity.class).filter(r -> r.getOutcome() == Outcome.FAILED).single().block(WAIT).getError())
                .isEqualTo("Cut off before it finished.");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(Info.Builder builder) {
        return (Map<String, Object>) builder.build().get("scryfallSync");
    }

    @Test
    void theLastSuccessIsReadBackAtStartup() {
        service.run(Trigger.MANUAL, true).block(WAIT);
        SyncService restarted = new SyncService(seeder, scryfall, new SyncLock(template), template,
                context.getBean(CardRepository.class), context.getBean(MongoIndexes.class));

        restarted.loadLastSuccess().block(WAIT);

        assertThat(restarted.lastSuccess().getCardsFile()).isEqualTo("2026-11-19T09:00:00+00:00");
        assertThat(restarted.run(Trigger.SCHEDULE, false).block(WAIT).getOutcome()).isEqualTo(Outcome.SKIPPED_UNCHANGED);
    }
}
