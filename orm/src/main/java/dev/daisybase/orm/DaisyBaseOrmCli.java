package dev.daisybase.orm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.daisybase.jdbc.DaisyBaseDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class DaisyBaseOrmCli {
    private DaisyBaseOrmCli() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        String mode = options.getOrDefault("mode", "schema-json");
        String url = require(options, "url");
        String packageName = options.getOrDefault("package", "dev.daisybase.generated");
        DaisyBaseDataSource dataSource = new DaisyBaseDataSource();
        dataSource.setUrl(url);
        if (options.containsKey("user")) {
            dataSource.setUser(options.get("user"));
        }
        if (options.containsKey("password")) {
            dataSource.setPassword(options.get("password"));
        }
        DaisyBaseOrmIntrospector.SchemaModel schemaModel = DaisyBaseOrmIntrospector.inspect(
                dataSource,
                options.getOrDefault("schema-pattern", "%"),
                options.getOrDefault("table-pattern", "%")
        );
        ObjectMapper objectMapper = new ObjectMapper();
        switch (mode) {
            case "schema-json" -> System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(schemaModel));
            case "generate-json" -> {
                DaisyBaseOrmCodeGenerator.GenerationBundle bundle = DaisyBaseOrmCodeGenerator.generate(schemaModel, packageName);
                System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(bundle));
            }
            case "write-files" -> {
                DaisyBaseOrmCodeGenerator.GenerationBundle bundle = DaisyBaseOrmCodeGenerator.generate(schemaModel, packageName);
                Path outputDir = Path.of(require(options, "output-dir"));
                Files.createDirectories(outputDir);
                for (Map.Entry<String, String> entry : bundle.sources().entrySet()) {
                    Files.writeString(outputDir.resolve(entry.getKey()), entry.getValue());
                }
                System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(bundle.sources().keySet()));
            }
            default -> throw new IllegalArgumentException("Unsupported mode: " + mode);
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> values = new HashMap<>();
        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + arg);
            }
            String key = arg.substring(2);
            if (index + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for --" + key);
            }
            values.put(key, args[++index]);
        }
        return values;
    }

    private static String require(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required option --" + key);
        }
        return value;
    }
}
