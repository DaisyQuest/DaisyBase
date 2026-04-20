package dev.daisybase.orm;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class DaisyBaseEntityMetadata<T> {
    private final Class<T> entityType;
    private final Constructor<T> constructor;
    private final String schemaName;
    private final String tableName;
    private final List<Property> properties;
    private final Property idProperty;
    private final Map<String, Property> propertyByName;

    private DaisyBaseEntityMetadata(Class<T> entityType,
                                 Constructor<T> constructor,
                                 String schemaName,
                                 String tableName,
                                 List<Property> properties,
                                 Property idProperty,
                                 Map<String, Property> propertyByName) {
        this.entityType = entityType;
        this.constructor = constructor;
        this.schemaName = schemaName;
        this.tableName = tableName;
        this.properties = properties;
        this.idProperty = idProperty;
        this.propertyByName = propertyByName;
    }

    static <T> DaisyBaseEntityMetadata<T> inspect(Class<T> entityType) {
        Objects.requireNonNull(entityType, "entityType");
        if (entityType.getAnnotation(Entity.class) == null) {
            throw new DaisyBaseOrmException("Entity type must be annotated with @Entity: " + entityType.getName());
        }
        Constructor<T> constructor;
        try {
            constructor = entityType.getDeclaredConstructor();
            constructor.setAccessible(true);
        } catch (ReflectiveOperationException exception) {
            throw new DaisyBaseOrmException("Entity type requires an accessible no-arg constructor: " + entityType.getName(), exception);
        }

        Table table = entityType.getAnnotation(Table.class);
        String schemaName = table == null || table.schema().isBlank() ? "public" : table.schema().trim();
        String tableName = table == null || table.name().isBlank()
                ? toSnakeCase(entityType.getSimpleName())
                : table.name().trim();

        List<Property> properties = new ArrayList<>();
        Map<String, Property> propertyByName = new LinkedHashMap<>();
        Property idProperty = null;
        for (Field field : entityType.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                continue;
            }
            if (field.getAnnotation(Transient.class) != null) {
                continue;
            }
            field.setAccessible(true);
            Column column = field.getAnnotation(Column.class);
            boolean id = field.getAnnotation(Id.class) != null;
            GeneratedValue generatedValue = field.getAnnotation(GeneratedValue.class);
            String columnName = column == null || column.name().isBlank() ? toSnakeCase(field.getName()) : column.name().trim();
            Property property = new Property(
                    field,
                    field.getName(),
                    columnName,
                    field.getType(),
                    id,
                    generatedValue != null,
                    generatedValue == null ? null : generatedValue.strategy(),
                    column == null || column.insertable(),
                    column == null || column.updatable(),
                    column == null || column.nullable()
            );
            properties.add(property);
            propertyByName.put(property.name(), property);
            if (id) {
                if (idProperty != null) {
                    throw new DaisyBaseOrmException("Composite identifiers are not supported for " + entityType.getName());
                }
                idProperty = property;
            }
        }
        if (idProperty == null) {
            throw new DaisyBaseOrmException("Entity type must declare one @Id field: " + entityType.getName());
        }
        return new DaisyBaseEntityMetadata<>(entityType, constructor, schemaName, tableName, List.copyOf(properties), idProperty,
                Map.copyOf(propertyByName));
    }

    Class<T> entityType() {
        return entityType;
    }

    String schemaName() {
        return schemaName;
    }

    String tableName() {
        return tableName;
    }

    String qualifiedTableName() {
        return schemaName + "." + tableName;
    }

    List<Property> properties() {
        return properties;
    }

    Property idProperty() {
        return idProperty;
    }

    Property property(String name) {
        Property property = propertyByName.get(name);
        if (property == null) {
            throw new DaisyBaseOrmException("Unknown mapped property '" + name + "' on " + entityType.getName());
        }
        return property;
    }

    T newInstance() {
        try {
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new DaisyBaseOrmException("Failed to instantiate entity " + entityType.getName(), exception);
        }
    }

    static String toSnakeCase(String value) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isUpperCase(character) && index > 0) {
                builder.append('_');
            }
            builder.append(Character.toLowerCase(character));
        }
        return builder.toString();
    }

    static String toClassCase(String value) {
        StringBuilder builder = new StringBuilder();
        boolean upperNext = true;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '_' || character == '-' || character == ' ') {
                upperNext = true;
                continue;
            }
            builder.append(upperNext ? Character.toUpperCase(character) : Character.toLowerCase(character));
            upperNext = false;
        }
        return builder.toString();
    }

    static String toPropertyCase(String value) {
        String classCase = toClassCase(value);
        return classCase.isEmpty() ? classCase : classCase.substring(0, 1).toLowerCase(Locale.ROOT) + classCase.substring(1);
    }

    record Property(Field field, String name, String columnName, Class<?> javaType,
                    boolean id, boolean generated, GenerationType generationType,
                    boolean insertable, boolean updatable, boolean nullable) {
        Object read(Object instance) {
            try {
                return field.get(instance);
            } catch (IllegalAccessException exception) {
                throw new DaisyBaseOrmException("Failed to read field " + field.getName(), exception);
            }
        }

        void write(Object instance, Object value) {
            try {
                if (value == null && field.getType().isPrimitive()) {
                    if (field.getType() == boolean.class) {
                        field.setBoolean(instance, false);
                    } else if (field.getType() == byte.class) {
                        field.setByte(instance, (byte) 0);
                    } else if (field.getType() == short.class) {
                        field.setShort(instance, (short) 0);
                    } else if (field.getType() == int.class) {
                        field.setInt(instance, 0);
                    } else if (field.getType() == long.class) {
                        field.setLong(instance, 0L);
                    } else if (field.getType() == float.class) {
                        field.setFloat(instance, 0f);
                    } else if (field.getType() == double.class) {
                        field.setDouble(instance, 0d);
                    } else if (field.getType() == char.class) {
                        field.setChar(instance, '\0');
                    }
                    return;
                }
                field.set(instance, value);
            } catch (IllegalAccessException exception) {
                throw new DaisyBaseOrmException("Failed to write field " + field.getName(), exception);
            }
        }
    }
}
