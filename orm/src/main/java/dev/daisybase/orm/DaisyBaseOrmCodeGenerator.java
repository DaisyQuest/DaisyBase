package dev.daisybase.orm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DaisyBaseOrmCodeGenerator {
    private DaisyBaseOrmCodeGenerator() {
    }

    public static GenerationBundle generate(DaisyBaseOrmIntrospector.SchemaModel schemaModel, String packageName) {
        Objects.requireNonNull(schemaModel, "schemaModel");
        Objects.requireNonNull(packageName, "packageName");
        Map<String, String> sources = new LinkedHashMap<>();
        for (DaisyBaseOrmIntrospector.TableModel table : schemaModel.tables()) {
            String entityName = table.className() + "Entity";
            String repositoryName = table.className() + "Repository";
            sources.put(entityName + ".java", renderEntity(packageName, entityName, table));
            sources.put(repositoryName + ".java", renderRepository(packageName, repositoryName, entityName,
                    table.idColumn().javaTypeName()));
        }
        return new GenerationBundle(packageName, sources);
    }

    private static String renderEntity(String packageName, String entityName, DaisyBaseOrmIntrospector.TableModel table) {
        List<String> lines = new ArrayList<>();
        lines.add("package " + packageName + ";");
        lines.add("");
        lines.add("import dev.daisybase.orm.Column;");
        lines.add("import dev.daisybase.orm.Entity;");
        lines.add("import dev.daisybase.orm.GeneratedValue;");
        lines.add("import dev.daisybase.orm.Id;");
        lines.add("import dev.daisybase.orm.Table;");
        for (String importName : table.imports()) {
            lines.add("import " + importName + ";");
        }
        lines.add("");
        lines.add("@Entity");
        lines.add("@Table(schema = \"" + table.schemaName() + "\", name = \"" + table.tableName() + "\")");
        lines.add("public final class " + entityName + " {");
        for (DaisyBaseOrmIntrospector.ColumnModel column : table.columns()) {
            if (column.primaryKey()) {
                lines.add("    @Id");
                if (column.autoIncrement()) {
                    lines.add("    @GeneratedValue");
                }
            }
            lines.add("    @Column(name = \"" + column.columnName() + "\", nullable = " + column.nullable() + ")");
            lines.add("    private " + column.javaTypeName() + " " + column.propertyName() + ";");
            lines.add("");
        }
        lines.add("    public " + entityName + "() {");
        lines.add("    }");
        lines.add("");
        for (DaisyBaseOrmIntrospector.ColumnModel column : table.columns()) {
            String accessor = Character.toUpperCase(column.propertyName().charAt(0)) + column.propertyName().substring(1);
            lines.add("    public " + column.javaTypeName() + " get" + accessor + "() {");
            lines.add("        return " + column.propertyName() + ";");
            lines.add("    }");
            lines.add("");
            lines.add("    public void set" + accessor + "(" + column.javaTypeName() + " " + column.propertyName() + ") {");
            lines.add("        this." + column.propertyName() + " = " + column.propertyName() + ";");
            lines.add("    }");
            lines.add("");
        }
        lines.add("}");
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private static String renderRepository(String packageName, String repositoryName, String entityName, String idTypeName) {
        return "package " + packageName + ";" + System.lineSeparator()
                + System.lineSeparator()
                + "import dev.daisybase.orm.DaisyBaseEntityManager;" + System.lineSeparator()
                + "import dev.daisybase.orm.DaisyBaseRepository;" + System.lineSeparator()
                + System.lineSeparator()
                + "public final class " + repositoryName + " extends DaisyBaseRepository<" + entityName + ", " + idTypeName + "> {" + System.lineSeparator()
                + "    public " + repositoryName + "(DaisyBaseEntityManager entityManager) {" + System.lineSeparator()
                + "        super(entityManager, " + entityName + ".class);" + System.lineSeparator()
                + "    }" + System.lineSeparator()
                + "}" + System.lineSeparator();
    }

    public record GenerationBundle(String packageName, Map<String, String> sources) {
        public GenerationBundle {
            sources = Map.copyOf(sources);
        }
    }
}
