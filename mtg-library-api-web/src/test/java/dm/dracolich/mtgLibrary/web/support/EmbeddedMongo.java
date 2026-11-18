package dm.dracolich.mtgLibrary.web.support;

import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import de.flapdoodle.commons.reverse.TransitionWalker;
import de.flapdoodle.commons.reverse.transitions.Start;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
import de.flapdoodle.embed.process.io.ProcessOutput;
import dm.dracolich.mtgLibrary.web.config.MongoIndexes;
import dm.dracolich.mtgLibrary.web.mapper.CardMapper;
import dm.dracolich.mtgLibrary.web.repository.CardRepository;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;

/**
 * One real mongod for the whole test run, started without Docker and silent. The first run downloads
 * the MongoDB binary into ~/.embedmongo; later runs reuse it. Each context gets a fresh database, with
 * the repositories, the mappers and {@link MongoIndexes}, but not the seeders, which would download
 * Scryfall's catalogue.
 */
public final class EmbeddedMongo {

    private static TransitionWalker.ReachedState<RunningMongodProcess> process;
    private static int databaseCounter;

    private EmbeddedMongo() {
    }

    public static synchronized AnnotationConfigApplicationContext freshContext() {
        if (process == null) {
            process = Mongod.instance()
                    .withProcessOutput(Start.to(ProcessOutput.class).initializedWith(ProcessOutput.silent()))
                    .start(Version.Main.V7_0);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> process.close()));
        }
        var address = process.current().getServerAddress();
        Config.uri = "mongodb://" + address.getHost() + ":" + address.getPort();
        Config.database = "mtg_library_test_" + (++databaseCounter);
        return new AnnotationConfigApplicationContext(Config.class);
    }

    @Configuration
    @EnableReactiveMongoRepositories(basePackageClasses = CardRepository.class)
    @ComponentScan(basePackageClasses = CardMapper.class)
    static class Config {
        static String uri;
        static String database;

        @Bean(destroyMethod = "close")
        MongoClient mongoClient() {
            return MongoClients.create(uri);
        }

        @Bean
        ReactiveMongoTemplate reactiveMongoTemplate(MongoClient client) {
            return new ReactiveMongoTemplate(client, database);
        }

        @Bean
        MongoIndexes mongoIndexes(ReactiveMongoTemplate template) {
            return new MongoIndexes(template);
        }
    }
}
