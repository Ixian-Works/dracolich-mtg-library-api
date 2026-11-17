package dm.dracolich.mtgLibrary.web.repository;

import dm.dracolich.mtgLibrary.web.entity.ArtPropertyEntity;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.util.Collection;
import reactor.core.publisher.Mono;

public interface ArtPropertyRepository extends ReactiveMongoRepository<ArtPropertyEntity, String> {
    Flux<ArtPropertyEntity> findByCardId(String cardId);
    Flux<ArtPropertyEntity> findByCardIdIn(Collection<String> cardIds);
    Mono<ArtPropertyEntity> findByScryfallId(String scryfallId);
}
