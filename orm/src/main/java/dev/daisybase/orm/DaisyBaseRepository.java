package dev.daisybase.orm;

import java.util.List;
import java.util.Optional;

public class DaisyBaseRepository<T, ID> {
    private final DaisyBaseEntityManager entityManager;
    private final Class<T> entityType;

    public DaisyBaseRepository(DaisyBaseEntityManager entityManager, Class<T> entityType) {
        this.entityManager = entityManager;
        this.entityType = entityType;
    }

    public T insert(T entity) {
        return entityManager.insert(entity);
    }

    public T update(T entity) {
        return entityManager.update(entity);
    }

    public Optional<T> findById(ID id) {
        return entityManager.findById(entityType, id);
    }

    public List<T> findAll() {
        return entityManager.findAll(entityType);
    }

    public boolean deleteById(ID id) {
        return entityManager.delete(entityType, id);
    }

    public DaisyBaseQuery<T> query() {
        return entityManager.query(entityType);
    }
}
