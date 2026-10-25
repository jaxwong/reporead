// Owns the two ways the backend runs a transaction: the default, and the read-only snapshot for reads over many tables.

package com.reporead;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class TransactionConfiguration {
    /**
     * The default transaction. Spring Boot's own template exists only while no other is declared, so it is declared
     * here.
     */
    @Bean
    @Primary
    TransactionTemplate transaction(PlatformTransactionManager transactions) {
        return new TransactionTemplate(transactions);
    }

    /**
     * One read-only REPEATABLE READ transaction: PostgreSQL takes its snapshot at the first query, so every later query
     * sees the database as that one did, and a write committed meanwhile is wholly in the next read, never partly in
     * this one. For an answer read from several tables (the library, a document's content).
     */
    @Bean
    TransactionTemplate snapshotRead(PlatformTransactionManager transactions) {
        var snapshot = new TransactionTemplate(transactions);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        return snapshot;
    }
}
