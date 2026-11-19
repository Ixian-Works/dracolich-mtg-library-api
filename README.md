# dracolich-mtg-library-api

A reactive REST API for Magic: The Gathering card data, powered by Scryfall bulk data. Provides card search with filters, set browsing, format legality, symbols, rulings, and art properties for 90,000+ cards.

## Prerequisites

- Java 25
- Maven 3.9+
- `~/.m2/settings-personal.xml` with GitHub Packages credentials (for `dm.dracolich.*` artifacts)
- MongoDB — only if you intend to *run* it

## Build

```bash
mvn clean install -s ~/.m2/settings-personal.xml
```

## Running

The service is deployed to the `dracolich-dev` cluster and reached through
`https://dev.dracolich.app/dracolich/mtg-library/api/v0/`. Backend changes reach it through the CI
pipeline (see the workspace `CLAUDE.md`), so `mvn clean install` is the verification step for code
work.

Running it locally is expected when developing a feature or chasing a bug here — just avoid standing
up the whole ecosystem when only a couple of services are involved. Create an uncommitted
`mtg-library-api-web/src/main/resources/application-local.yml` and run with
`SPRING_PROFILES_ACTIVE=local`. It is gitignored and must stay that way — never commit local config.
There is no template for this service, and `spring.mongodb.uri`, `spring.mongodb.database` and
`cors.allowed-origins` have no defaults.

Swagger UI: `http://<host>/dracolich/mtg-library/api/v0/swagger-ui.html`.

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8080` | Server port. Actuator listens separately on `7980`. |
| `MONGODB_URI` | _(required)_ | MongoDB connection URI |
| `MONGODB_DATABASE` | _(required)_ | Database name |
| `CORS_ALLOWED_ORIGINS` | _(required)_ | Allowed CORS origins |
| `SPRING_PROFILES_ACTIVE` | `dev` | Profile name only — no profile-specific config file exists |

## API Endpoints

Base path: `/dracolich/mtg-library/api/v0/`

All responses are wrapped in the standard `DmdResponse` envelope:

```json
{
  "success": true,
  "httpStatus": "200 OK",
  "message": "Request processed successfully",
  "payload": { ... },
  "errors": null
}
```

### Cards

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/cards/{id}` | Fetch card by ID |
| `GET` | `/cards?name={name}` | Fetch card by exact name |
| `POST` | `/cards/search?name={name}&page=0&size=10` | Search cards with filters (paginated) |
| `POST` | `/cards/collection` | Look up to 100 cards at once, by id or exact name (`{"identifiers": [{"id": ...}, {"name": ...}]}`). Returns `cards` in request order and `not_found`; 400 `DMD047` for an empty or oversized list, or an identifier with neither or both |
| `POST` | `/cards/random` | Fetch a random card matching filters |

**Search request body:**

```json
{
  "color_identity": ["W", "U"],
  "color_identity_match": "AT_MOST",
  "types": ["CREATURE"],
  "keywords": ["flying"],
  "legal_in": "COMMANDER",
  "min_mana_value": 2.0,
  "max_mana_value": 5.0
}
```

All fields are optional, and the field names are snake_case. Combine with `?name=` query param for
name filtering.

**Colour filters.** `colors` and `color_identity` each take a match mode, in `colors_match` and
`color_identity_match`. With white and blue selected:

| Mode | Matches | Use |
|------|---------|-----|
| `INCLUDES` (the default) | Cards with white and blue, and any other colour: white-blue, white-blue-black | "Has these colours" |
| `EXACTLY` | White-blue cards only | "Is these colours" |
| `AT_MOST` | Cards with no colour outside the two: white, blue, white-blue and colourless | What fits a Commander deck, against the commander's `color_identity` |

- With `EXACTLY` or `AT_MOST`, an empty list means colourless. `C` in a list means the same.
- A card has one colour identity whatever its number of faces, stored on its default face. Colours
  belong to a face: `INCLUDES` and `EXACTLY` match a card by either face, and `AT_MOST` needs every
  face inside the selection.
- **Two-faced cards imported before this change have no colour identity stored.** `AT_MOST` on
  `color_identity` leaves them out until the next sync writes it.

**Paging.** `page` defaults to 0 and `size` to 20, on card, set and subtype searches. `size` runs
from 1 to 100 and `page` from 0; anything else is a 400, `DMD046`. A page loads its cards' art in one query.

**Order.** `sort=<key>[,asc|desc]`, repeatable, with the keys `name`, `first_release_date`,
`mana_value` and `edhrec_rank` (lower is more played; unranked cards come last). Any other value is a
400, `DMD045`. Without `sort`, a search with a name lists the best name matches first (the whole name,
then a whole word of it, the start of the name, the start of a word, anywhere), then the most played.
Without either, cards come in storage order. Every order ends on the card's id, so paging never repeats
or skips a card.

**Commanders.** `can_be_commander: true` in the search body keeps only the cards that can be a
Commander deck's commander. `beside_commander: "<card id>"` keeps only the cards that may be chosen
beside that commander as the deck's second one (404 when the id is no card). Each card answers with
`can_be_commander` and `pairings`. Both are worked out at import, so cards imported before them need
a `POST /sync`.

A card **can be a commander** when the front face is a legendary creature, or its rules text says it
"can be your commander" (such as Grist, the Hunger Tide). A card banned or not legal in Commander
can't be. **Not covered yet:** legendary Vehicles and Spacecraft, which the rules don't make
commanders by type alone; and the Brawl and Oathbreaker variants.

**What may go beside a commander**, by its `pairings`:

| The commander has | Beside it |
|---|---|
| `partner` (plain Partner) | any other plain Partner commander |
| `partner_with:<name>` ("Partner with Toothy, Imaginary Friend") | only that card |
| `partner_group:<name>` ("Partner—Survivors") | any other commander with the same named Partner |
| `friends_forever` | any other Friends forever commander |
| `choose_a_background` | any Background (`background`: a legendary Background enchantment, never a commander itself) |
| `background` (asked from a Background) | any commander that says "Choose a Background" |
| `doctors_companion` | any Time Lord Doctor commander (`doctor`) |
| `doctor` | any Doctor's companion commander |
| none | nothing |

The kinds are read from the card's keywords, from "Partner with" and "Partner—" lines in the front
face's text, and from its type line. Not covered yet: rules that limit a pairing by colour, and cards
that grant a pairing to others.

**Rarity and set** filter the printing the import keeps for each card (Scryfall's `oracle_cards`, one
printing per card), so a set search finds the cards whose kept printing is in that set. `set_code` is
case-insensitive. Cards imported before these filters existed need a `POST /sync` to gain the fields.

### Sets

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/sets/{id}` | Fetch set by ID |
| `GET` | `/sets?code={code}` | Fetch set by code (e.g., `MH3`) |
| `POST` | `/sets/search?page=0&size=10` | Search sets with filters (paginated) |

### Formats

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/formats/` | Fetch all game formats |

### Symbols

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/symbols/{id}` | Fetch symbol by ID |
| `GET` | `/symbols?symbol={symbol}` | Fetch symbol by notation (e.g., `{W}`, `{U}`) |

### Subtypes

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/subtypes/{id}` | Fetch subtype by ID |
| `GET` | `/subtypes?code={code}` | Fetch subtype by code |
| `GET` | `/subtypes/search?page=0&size=20` | Browse subtypes (paginated) |

### Art Properties

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/art-properties/{id}` | Fetch art property by ID |
| `GET` | `/art-properties?card_id={cardId}` | Fetch all printings/art for a card |

### Rulings

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/rulings/{id}` | Fetch ruling by ID |

### Sync

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/sync` | Trigger Scryfall bulk data sync |

## Data Source

Card data is sourced from [Scryfall](https://scryfall.com/docs/api) bulk data exports. The sync endpoint triggers a full import of:

- Cards (90,000+)
- Sets
- Rulings
- Symbols
- Card layouts
- Subtypes
- Game formats
- Art properties

## Module Structure

| Module | Purpose |
|--------|---------|
| `mtg-library-api-web` | Spring Boot app, controllers, services, entities, repositories |
| `mtg-library-api-integration` | Scryfall API client and DTOs for external data import |
| `mtg-library-api-dto` | Public DTOs for API consumers (published to GitHub Packages) |

## Project Structure

```
dracolich-mtg-library-api/
├── mtg-library-api-dto/           # Public DTOs (consumed by dracolich-ai-api)
│   └── src/main/java/
│       └── dm/dracolich/mtgLibrary/dto/
│           ├── enums/             # Color, CardType, Format, Legality, etc.
│           ├── records/           # CardSearchRecord, PageRecord
│           └── *.java             # CardDto, CardFaceDto, GameFormatDto, etc.
│
├── mtg-library-api-integration/   # Scryfall sync client
│   └── src/main/java/
│       └── dm/dracolich/mtgLibrary/integration/
│           ├── client/            # Scryfall API client
│           ├── config/            # Client configuration
│           └── dto/               # Scryfall-specific response DTOs
│
└── mtg-library-api-web/           # Main application
    └── src/main/java/
        └── dm/dracolich/mtgLibrary/web/
            ├── config/            # CORS, MongoDB, seed config
            ├── controller/        # REST controllers (9)
            ├── entity/            # MongoDB document entities
            ├── mapper/            # MapStruct entity-DTO mappers
            ├── repository/        # Reactive MongoDB repositories
            └── service/           # Business logic
```

## Tech Stack

- **Java 25** with preview features
- **Spring Boot 4.0** with WebFlux (reactive)
- **MongoDB** with Reactive Streams
- **MapStruct 1.6.3** for entity-DTO mapping
- **SpringDoc OpenAPI 3.0.1** for Swagger UI
- **Lombok** for boilerplate reduction
- **forge:common** for DmdResponse envelope, error handling

## Known Gaps

Tracked because they block the frontend:

- **No `GET /cards/{id}/arts`** (or an `arts: []` array on the card detail response). Only
  `defaultArt` ships, which blocks the art-version scroller in `dracolich-mtg-ui`.
- **`GET /cards/{id}` swallows `/cards/search`** on some matches — the path variable route needs a
  regex constraint or to be declared after the literal routes.

## Running Tests

There are none yet — 108 main classes, 0 test classes. Test dependencies are declared but unused.

---

Part of the [Dracolich](https://github.com/orgs/Ixian-Works/repositories?q=dracolich) platform. For the
cross-repo picture — service topology, release pipeline, shared conventions — see the workspace guide
at `~/Dev/Dracolich/CLAUDE.md`.
