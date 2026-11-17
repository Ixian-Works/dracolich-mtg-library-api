package dm.dracolich.mtgLibrary.web.repository.custom;

import dm.dracolich.mtgLibrary.dto.records.CardSearchRecord;
import dm.dracolich.mtgLibrary.web.entity.CardEntity;
import org.springframework.data.domain.Page;
import reactor.core.publisher.Mono;

import java.util.List;

public interface CardCustomRepository {
    Mono<Page<CardEntity>> findByFilters(String name, CardSearchRecord cardSearchRecord, List<String> sort,
                                         int page, int size);
    Mono<CardEntity> findRandom(CardSearchRecord cardSearchRecord);
}
