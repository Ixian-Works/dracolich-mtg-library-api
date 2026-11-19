package dm.dracolich.mtgLibrary.web.entity;

import dm.dracolich.mtgLibrary.dto.enums.Format;
import dm.dracolich.mtgLibrary.dto.enums.Legality;
import dm.dracolich.mtgLibrary.dto.enums.Rarity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Document(collection = "cards")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CardEntity {
    public static final String NAME_KEYS = "name_keys";

    @Id
    private String id;
    @Indexed(unique = true)
    @Field("oracle_id")
    private String oracleId;
    private String name;
    private Set<String> keywords;
    private Map<Format, Legality> legalities;
    private String layout; // Reference to CardLayoutEntity.id (e.g. "normal", "transform", "case").
    private Boolean reserved;
    @Field("first_release_date")
    private LocalDate firstReleaseDate;
    @Field("edhrec_rank")
    private Integer edhrecRank;
    @Field("penny_rank")
    private Integer pennyRank;
    // The imported printing's rarity and set, copied from its art record so that search can filter
    // on them. The import reads Scryfall's oracle_cards, one printing per card. The set code is
    // stored lower-cased, as Scryfall writes it.
    private Rarity rarity;
    @Field("set_code")
    private String setCode;
    // Whether the card can be a Commander deck's commander, and the tokens of what it can be paired
    // with (see seed/CommanderRules). Worked out at import; null on cards imported before they
    // existed, until the next sync.
    @Field("can_be_commander")
    private Boolean canBeCommander;
    private Set<String> pairings;
    // The card's name and each face's name, lower-cased, for an exact-name lookup that an index can
    // serve. Null on cards imported before it existed, until the next sync.
    @Field(NAME_KEYS)
    private Set<String> nameKeys;
    private Boolean multiface;
    @Field("default_face")
    private CardFaceEntity defaultFace;
    @Field("flipped_face")
    private CardFaceEntity flippedFace;
    @Field("all_parts")
    private List<RelatedCardPartEntity> allParts;
}
