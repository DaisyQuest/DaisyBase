package dev.daisybase.orm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class DaisyBaseQuery<T> {
    private final DaisyBaseEntityManager entityManager;
    private final DaisyBaseEntityMetadata<T> metadata;
    private final List<Filter> filters = new ArrayList<>();
    private DaisyBaseEntityMetadata.Property orderBy;
    private boolean ascending = true;
    private Integer limit;

    DaisyBaseQuery(DaisyBaseEntityManager entityManager, DaisyBaseEntityMetadata<T> metadata) {
        this.entityManager = entityManager;
        this.metadata = metadata;
    }

    public DaisyBaseQuery<T> whereEquals(String propertyName, Object value) {
        filters.add(new Filter(metadata.property(propertyName), value));
        return this;
    }

    public DaisyBaseQuery<T> orderBy(String propertyName, boolean ascending) {
        this.orderBy = metadata.property(propertyName);
        this.ascending = ascending;
        return this;
    }

    public DaisyBaseQuery<T> limit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
        return this;
    }

    public List<T> list() {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(metadata.properties().stream().map(DaisyBaseEntityMetadata.Property::columnName).reduce((left, right) -> left + ", " + right).orElseThrow())
                .append(" FROM ")
                .append(metadata.qualifiedTableName());
        List<Object> parameters = new ArrayList<>();
        if (!filters.isEmpty()) {
            sql.append(" WHERE ");
            for (int index = 0; index < filters.size(); index++) {
                if (index > 0) {
                    sql.append(" AND ");
                }
                Filter filter = filters.get(index);
                sql.append(filter.property().columnName()).append(" = ?");
                parameters.add(filter.value());
            }
        }
        if (orderBy != null) {
            sql.append(" ORDER BY ").append(orderBy.columnName()).append(ascending ? " ASC" : " DESC");
        } else {
            sql.append(" ORDER BY ").append(metadata.idProperty().columnName());
        }
        if (limit != null) {
            sql.append(" FETCH FIRST ").append(limit).append(" ROWS ONLY");
        }
        return entityManager.executeQuery(metadata, sql.toString(), parameters);
    }

    public Optional<T> first() {
        if (limit == null) {
            limit(1);
        }
        List<T> results = list();
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    record Filter(DaisyBaseEntityMetadata.Property property, Object value) {
        Filter {
            Objects.requireNonNull(property, "property");
        }
    }
}
