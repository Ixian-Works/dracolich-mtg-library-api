package dm.dracolich.mtgLibrary.web.seed;

import com.mongodb.client.result.UpdateResult;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A lease in MongoDB, so that with several replicas only one syncs at a time. One document in
 * {@code sync_lock}: whoever holds it, and until when. Taking it is one atomic find-and-modify that
 * matches only a free or expired lease; when a replica dies mid-sync the lease simply runs out.
 */
@Component
@RequiredArgsConstructor
public class SyncLock {

    static final String COLLECTION = "sync_lock";
    static final String KEY = "scryfall";
    /** Longer than a full sync (10 to 15 minutes), so a live sync never loses it. */
    static final Duration LEASE = Duration.ofHours(2);

    private final ReactiveMongoTemplate mongoTemplate;
    /** This process, as the lock's holder. */
    private final String holder = UUID.randomUUID().toString();

    /** Takes the lease if it is free or expired. Emits whether this process now holds it. */
    public Mono<Boolean> acquire() {
        Instant now = Instant.now();
        Query freeOrExpired = Query.query(Criteria.where("_id").is(KEY)
                .orOperator(Criteria.where("until").lt(now), Criteria.where("until").exists(false)));
        Update take = new Update().set("holder", holder).set("until", now.plus(LEASE));
        return mongoTemplate.findAndModify(freeOrExpired, take,
                        FindAndModifyOptions.options().upsert(true).returnNew(true), Document.class, COLLECTION)
                .map(doc -> holder.equals(doc.getString("holder")))
                .defaultIfEmpty(false)
                // Held by someone else: the upsert tries to insert a second document with the same _id.
                .onErrorResume(DuplicateKeyException.class, e -> Mono.just(false));
    }

    /** Gives the lease back, if this process holds it. */
    public Mono<Void> release() {
        return mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(KEY).and("holder").is(holder)),
                        new Update().set("until", Instant.now()), COLLECTION)
                .map(UpdateResult::getModifiedCount)
                .then();
    }

    String holder() {
        return holder;
    }
}
