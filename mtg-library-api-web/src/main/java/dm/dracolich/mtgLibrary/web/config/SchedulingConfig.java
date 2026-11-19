package dm.dracolich.mtgLibrary.web.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on the daily Scryfall sync in {@code SyncService}. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
