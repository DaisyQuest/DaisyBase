package dev.daisybase.orm;

import javax.sql.DataSource;
import java.util.Objects;

public final class DaisyBaseOrmSessionFactory {
    private final DataSource dataSource;

    public DaisyBaseOrmSessionFactory(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public DaisyBaseEntityManager createEntityManager() {
        return new DaisyBaseEntityManager(dataSource);
    }

    public <T, ID> DaisyBaseRepository<T, ID> createRepository(Class<T> entityType) {
        return new DaisyBaseRepository<>(createEntityManager(), entityType);
    }
}
