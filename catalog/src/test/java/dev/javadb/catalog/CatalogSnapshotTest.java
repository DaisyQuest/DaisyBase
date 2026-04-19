package dev.javadb.catalog;

import dev.javadb.common.Common;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogSnapshotTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsSequencesRoutinesAndIdentityColumnsAcrossRoundTrip() {
        Catalog.CatalogSnapshot snapshot = Catalog.bootstrap(new Common.ObjectId(1));
        snapshot = Catalog.applyChanges(snapshot, List.of(
                new Catalog.CreateTableChange(new Common.ObjectId(2),
                        new Catalog.QualifiedName("public", "orders"),
                        List.of(
                                new Catalog.ColumnDefinition(0, "id", Common.DataType.BIGINT, false, true, true, null,
                                        new Catalog.IdentityDefinition(Catalog.IdentityGeneration.ALWAYS,
                                                new Catalog.SequenceOptions(1000L, 10L, null, null, 20, false))),
                                new Catalog.ColumnDefinition(1, "customer_id", Common.DataType.BIGINT, false, false, false, null, null)
                        )),
                new Catalog.CreateSequenceChange(new Common.ObjectId(3),
                        new Catalog.QualifiedName("public", "order_seq"),
                        new Catalog.SequenceOptions(100L, 5L, null, 1000L, 32, true)),
                new Catalog.CreateRoutineChange(new Common.ObjectId(4),
                        new Catalog.QualifiedName("public", "add_one"),
                        Catalog.RoutineKind.FUNCTION,
                        List.of(new Catalog.RoutineParameter(0, "p_value", Common.DataType.BIGINT, Catalog.ParameterMode.IN)),
                        Common.DataType.BIGINT,
                        "BEGIN RETURN p_value + 1; END")
        ));

        Path snapshotPath = tempDir.resolve("catalog.snapshot");
        Catalog.writeSnapshot(snapshotPath, snapshot);
        Catalog.CatalogSnapshot reloaded = Catalog.readSnapshot(snapshotPath);

        assertNotNull(reloaded);
        assertEquals(4L, Catalog.maxObjectId(reloaded));
        assertTrue(reloaded.sequence(new Catalog.QualifiedName("public", "order_seq")).isPresent());
        assertTrue(reloaded.routine(new Catalog.QualifiedName("public", "add_one")).isPresent());

        Catalog.TableDefinition orders = reloaded.requireTable(new Catalog.QualifiedName("public", "orders"));
        Catalog.IdentityDefinition identity = orders.columns().getFirst().identityDefinition();
        assertNotNull(identity);
        assertEquals(Catalog.IdentityGeneration.ALWAYS, identity.generation());
        assertEquals(1000L, identity.options().startWith());
        assertEquals(10L, identity.options().incrementBy());

        Catalog.SequenceDefinition sequence = reloaded.sequence(new Catalog.QualifiedName("public", "order_seq")).orElseThrow();
        assertEquals(32, sequence.options().cacheSize());
        assertTrue(sequence.options().cycle());

        Catalog.RoutineDefinition routine = reloaded.routine(new Catalog.QualifiedName("public", "add_one")).orElseThrow();
        assertEquals(Catalog.RoutineKind.FUNCTION, routine.kind());
        assertEquals(Common.DataType.BIGINT, routine.returnType());
        assertEquals("BEGIN RETURN p_value + 1; END", routine.bodySql());
    }

    @Test
    void readsLegacyCatalogSnapshotsWithoutNewFields() throws Exception {
        Path snapshotPath = tempDir.resolve("catalog-v1.snapshot");
        Files.writeString(snapshotPath, String.join(System.lineSeparator(),
                "CATALOG|1|2",
                "SCHEMA|1|" + Common.Values.encodeString("public"),
                "TABLE|2|" + Common.Values.encodeString("public") + "|" + Common.Values.encodeString("users"),
                "COLUMN|2|0|" + Common.Values.encodeString("id") + "|BIGINT|false|true|true|" + Common.Values.encodeString(""),
                "INDEX|3|" + Common.Values.encodeString("users_pk") + "|2|true|" + Common.Values.encodeString("id")
        ));

        Catalog.CatalogSnapshot snapshot = Catalog.readSnapshot(snapshotPath);

        assertNotNull(snapshot);
        assertTrue(snapshot.sequencesById().isEmpty());
        assertTrue(snapshot.routinesById().isEmpty());
        Catalog.TableDefinition users = snapshot.requireTable(new Catalog.QualifiedName("public", "users"));
        assertNull(users.columns().getFirst().identityDefinition());
        assertEquals("users_pk", snapshot.indexesById().get(new Common.ObjectId(3)).name());
    }

    @Test
    void persistsUsersRolesAndPrivilegeGrantsAcrossRoundTrip() {
        Catalog.CatalogSnapshot snapshot = Catalog.bootstrap(new Common.ObjectId(1));
        snapshot = Catalog.applyChanges(snapshot, List.of(
                new Catalog.CreateUserChange(new Common.ObjectId(2), "app", Catalog.hashPassword("secret")),
                new Catalog.CreateRoleChange(new Common.ObjectId(3), "writer"),
                new Catalog.GrantRoleChange("writer", "app"),
                new Catalog.GrantPrivilegeChange(Catalog.PrincipalType.ROLE, "writer", Catalog.Privilege.ADMIN, null)
        ));

        Path snapshotPath = tempDir.resolve("catalog-auth.snapshot");
        Catalog.writeSnapshot(snapshotPath, snapshot);
        Catalog.CatalogSnapshot reloaded = Catalog.readSnapshot(snapshotPath);

        assertNotNull(reloaded);
        assertTrue(reloaded.user("app").isPresent());
        assertTrue(reloaded.rolesByName().containsKey("writer"));
        assertTrue(reloaded.roleMemberships().get("app").contains("writer"));
        assertTrue(reloaded.authenticate("app", "secret"));
        assertTrue(reloaded.hasPrivilege("app", Catalog.Privilege.ADMIN, null));
        assertEquals(3L, Catalog.maxObjectId(reloaded));
    }
}
