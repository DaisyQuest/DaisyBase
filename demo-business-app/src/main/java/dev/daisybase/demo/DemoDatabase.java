package dev.daisybase.demo;

import dev.daisybase.jdbc.DaisyBaseDataSource;
import dev.daisybase.jdbc.DaisyBaseDriver;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@ApplicationScoped
public class DemoDatabase {
    private final DemoAppConfig config;
    private volatile boolean initialized;
    private DaisyBaseDataSource dataSource;

    public DemoDatabase() {
        this(DemoAppConfig.load());
    }

    DemoDatabase(DemoAppConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void postConstruct() {
        initializeIfNeeded();
    }

    public synchronized void initializeIfNeeded() {
        if (initialized) {
            return;
        }
        try {
            Class.forName(DaisyBaseDriver.class.getName());
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Failed to load DaisyBase JDBC driver", exception);
        }
        DaisyBaseDataSource created = new DaisyBaseDataSource();
        created.setUrl(config.jdbcUrl());
        if (!config.user().isBlank()) {
            created.setUser(config.user());
        }
        if (!config.password().isBlank()) {
            created.setPassword(config.password());
        }
        new SchemaBootstrapper().initialize(created);
        this.dataSource = created;
        this.initialized = true;
    }

    public Connection openConnection() throws SQLException {
        initializeIfNeeded();
        return dataSource.getConnection();
    }

    public DataSource dataSource() {
        initializeIfNeeded();
        return dataSource;
    }

    public DemoAppConfig config() {
        return config;
    }
}
