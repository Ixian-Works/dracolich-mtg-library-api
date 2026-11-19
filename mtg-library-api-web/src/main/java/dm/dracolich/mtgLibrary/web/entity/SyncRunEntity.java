package dm.dracolich.mtgLibrary.web.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * One Scryfall sync, or one that was skipped: when, why it ran, what it read and wrote, and how it
 * ended. {@code cards_file} and {@code rulings_file} are Scryfall's {@code updated_at} for the two bulk
 * files, so the next scheduled run can tell whether there is anything new.
 */
@Document(collection = "sync_runs")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SyncRunEntity {

    public enum Trigger { STARTUP, SCHEDULE, MANUAL }

    public enum Outcome { RUNNING, SUCCEEDED, FAILED, SKIPPED_UNCHANGED, SKIPPED_LOCKED }

    @Id
    private String id;
    private Trigger trigger;
    private Outcome outcome;
    @Field("started_at")
    private Instant startedAt;
    @Field("ended_at")
    private Instant endedAt;
    @Field("cards_file")
    private String cardsFile;
    @Field("rulings_file")
    private String rulingsFile;
    private Long sets;
    private Long symbols;
    private Long cards;
    private Long rulings;
    @Field("cards_gone_from_scryfall")
    private Long cardsGoneFromScryfall;
    private String error;
}
