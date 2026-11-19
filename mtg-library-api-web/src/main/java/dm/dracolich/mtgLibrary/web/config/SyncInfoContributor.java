package dm.dracolich.mtgLibrary.web.config;

import dm.dracolich.mtgLibrary.web.entity.SyncRunEntity;
import dm.dracolich.mtgLibrary.web.seed.SyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** The last successful Scryfall sync, under {@code scryfallSync} in the {@code info} actuator endpoint. */
@Component
@RequiredArgsConstructor
public class SyncInfoContributor implements InfoContributor {

    private final SyncService syncService;

    @Override
    public void contribute(Info.Builder builder) {
        SyncRunEntity last = syncService.lastSuccess();
        Map<String, Object> detail = new LinkedHashMap<>();
        if (last == null) {
            detail.put("lastSuccess", null);
        } else {
            detail.put("lastSuccessStartedAt", last.getStartedAt());
            detail.put("lastSuccessEndedAt", last.getEndedAt());
            detail.put("trigger", last.getTrigger());
            detail.put("cardsFile", last.getCardsFile());
            detail.put("rulingsFile", last.getRulingsFile());
            detail.put("cards", last.getCards());
            detail.put("rulings", last.getRulings());
            detail.put("cardsGoneFromScryfall", last.getCardsGoneFromScryfall());
        }
        builder.withDetail("scryfallSync", detail);
    }
}
