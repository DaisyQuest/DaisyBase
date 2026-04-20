package dev.daisybase.engine;

import dev.daisybase.catalog.Catalog;
import dev.daisybase.common.Common;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SequenceStateStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsSequenceAndIdentityStateAcrossRestartAndExplicitObservations() {
        Catalog.CatalogSnapshot snapshot = Catalog.applyChanges(Catalog.bootstrap(new Common.ObjectId(1)), List.of(
                new Catalog.CreateSequenceChange(new Common.ObjectId(10),
                        new Catalog.QualifiedName("public", "order_seq"),
                        new Catalog.SequenceOptions(10L, 2L, null, null, 20, false)),
                new Catalog.CreateTableChange(new Common.ObjectId(20),
                        new Catalog.QualifiedName("public", "tickets"),
                        List.of(
                                new Catalog.ColumnDefinition(0, "id", Common.DataType.BIGINT, false, true, true, null,
                                        new Catalog.IdentityDefinition(Catalog.IdentityGeneration.BY_DEFAULT,
                                                new Catalog.SequenceOptions(1L, 1L, null, null, 20, false))),
                                new Catalog.ColumnDefinition(1, "note", Common.DataType.TEXT, false, false, false, null, null)
                        ))
        ));
        Catalog.SequenceDefinition orderSequence = snapshot.sequence(new Catalog.QualifiedName("public", "order_seq")).orElseThrow();
        Catalog.TableDefinition tickets = snapshot.requireTable(new Catalog.QualifiedName("public", "tickets"));
        Catalog.ColumnDefinition identityColumn = tickets.columns().getFirst();

        SequenceStateStore store = new SequenceStateStore(tempDir);
        store.ensureDefinitions(snapshot);
        store.ensureDefinitions(snapshot);

        assertEquals(10L, store.nextValue(orderSequence));
        assertEquals(12L, store.nextValue(orderSequence));
        assertEquals(1L, store.nextIdentityValue(tickets, identityColumn));

        store.observeIdentityValue(tickets, identityColumn, Common.Value.bigint(50L));
        store.observeIdentityValue(tickets, identityColumn, Common.Value.integer(10));
        store.observeIdentityValue(tickets, identityColumn, Common.Value.nullValue(Common.DataType.BIGINT));
        assertEquals(51L, store.nextIdentityValue(tickets, identityColumn));

        SequenceStateStore reopened = new SequenceStateStore(tempDir);
        reopened.ensureDefinitions(snapshot);

        assertEquals(14L, reopened.nextValue(orderSequence));
        assertEquals(52L, reopened.nextIdentityValue(tickets, identityColumn));
        assertTrue(Files.exists(tempDir.resolve("catalog").resolve("sequence-state.snapshot")));
    }

    @Test
    void cyclesDescendingSequencesAndDescendingIdentities() {
        Catalog.CatalogSnapshot snapshot = Catalog.applyChanges(Catalog.bootstrap(new Common.ObjectId(1)), List.of(
                new Catalog.CreateSequenceChange(new Common.ObjectId(30),
                        new Catalog.QualifiedName("public", "descending_seq"),
                        new Catalog.SequenceOptions(5L, -2L, -1L, 5L, 20, true)),
                new Catalog.CreateTableChange(new Common.ObjectId(40),
                        new Catalog.QualifiedName("public", "backfill_ids"),
                        List.of(
                                new Catalog.ColumnDefinition(0, "id", Common.DataType.BIGINT, false, true, true, null,
                                        new Catalog.IdentityDefinition(Catalog.IdentityGeneration.BY_DEFAULT,
                                                new Catalog.SequenceOptions(5L, -2L, -1L, 5L, 20, true))),
                                new Catalog.ColumnDefinition(1, "note", Common.DataType.TEXT, false, false, false, null, null)
                        ))
        ));
        Catalog.SequenceDefinition descending = snapshot.sequence(new Catalog.QualifiedName("public", "descending_seq")).orElseThrow();
        Catalog.TableDefinition backfillIds = snapshot.requireTable(new Catalog.QualifiedName("public", "backfill_ids"));
        Catalog.ColumnDefinition identity = backfillIds.columns().getFirst();

        SequenceStateStore store = new SequenceStateStore(tempDir);
        store.ensureDefinitions(snapshot);

        assertEquals(5L, store.nextValue(descending));
        assertEquals(3L, store.nextValue(descending));
        assertEquals(1L, store.nextValue(descending));
        assertEquals(-1L, store.nextValue(descending));
        assertEquals(5L, store.nextValue(descending));

        assertEquals(5L, store.nextIdentityValue(backfillIds, identity));
        store.observeIdentityValue(backfillIds, identity, Common.Value.bigint(1L));
        assertEquals(-1L, store.nextIdentityValue(backfillIds, identity));
        assertEquals(5L, store.nextIdentityValue(backfillIds, identity));
    }

    @Test
    void rejectsExhaustedZeroIncrementAndNonNumericIdentityValues() {
        Catalog.CatalogSnapshot snapshot = Catalog.applyChanges(Catalog.bootstrap(new Common.ObjectId(1)), List.of(
                new Catalog.CreateSequenceChange(new Common.ObjectId(50),
                        new Catalog.QualifiedName("public", "tiny_seq"),
                        new Catalog.SequenceOptions(1L, 1L, 1L, 2L, 20, false)),
                new Catalog.CreateSequenceChange(new Common.ObjectId(51),
                        new Catalog.QualifiedName("public", "broken_seq"),
                        new Catalog.SequenceOptions(1L, 0L, null, null, 20, false)),
                new Catalog.CreateTableChange(new Common.ObjectId(52),
                        new Catalog.QualifiedName("public", "typed_ids"),
                        List.of(
                                new Catalog.ColumnDefinition(0, "id", Common.DataType.BIGINT, false, true, true, null,
                                        new Catalog.IdentityDefinition(Catalog.IdentityGeneration.BY_DEFAULT,
                                                new Catalog.SequenceOptions(1L, 1L, null, null, 20, false))),
                                new Catalog.ColumnDefinition(1, "note", Common.DataType.TEXT, false, false, false, null, null)
                        ))
        ));
        Catalog.SequenceDefinition tiny = snapshot.sequence(new Catalog.QualifiedName("public", "tiny_seq")).orElseThrow();
        Catalog.SequenceDefinition broken = snapshot.sequence(new Catalog.QualifiedName("public", "broken_seq")).orElseThrow();
        Catalog.TableDefinition typedIds = snapshot.requireTable(new Catalog.QualifiedName("public", "typed_ids"));
        Catalog.ColumnDefinition identity = typedIds.columns().getFirst();

        SequenceStateStore store = new SequenceStateStore(tempDir);
        store.ensureDefinitions(snapshot);

        assertEquals(1L, store.nextValue(tiny));
        Common.DatabaseException exhausted = assertThrows(Common.DatabaseException.class, () -> store.nextValue(tiny));
        assertEquals(Common.ErrorCode.CONSTRAINT_VIOLATION, exhausted.code());

        Common.DatabaseException zeroIncrement = assertThrows(Common.DatabaseException.class, () -> store.nextValue(broken));
        assertEquals(Common.ErrorCode.SEMANTIC_ERROR, zeroIncrement.code());

        Common.DatabaseException wrongType = assertThrows(Common.DatabaseException.class,
                () -> store.observeIdentityValue(typedIds, identity, Common.Value.text("bad")));
        assertEquals(Common.ErrorCode.SEMANTIC_ERROR, wrongType.code());
    }

    @Test
    void rejectsCorruptSequenceStateSnapshots() throws Exception {
        Path snapshotPath = tempDir.resolve("catalog").resolve("sequence-state.snapshot");
        Files.createDirectories(snapshotPath.getParent());

        Files.writeString(snapshotPath, "SEQUENCE_STATE|999");
        Common.DatabaseException wrongHeader = assertThrows(Common.DatabaseException.class,
                () -> new SequenceStateStore(tempDir));
        assertEquals(Common.ErrorCode.STORAGE_ERROR, wrongHeader.code());

        Files.writeString(snapshotPath, String.join(System.lineSeparator(),
                "SEQUENCE_STATE|1",
                "broken-line-without-separator"));
        Common.DatabaseException corruptLine = assertThrows(Common.DatabaseException.class,
                () -> new SequenceStateStore(tempDir));
        assertEquals(Common.ErrorCode.STORAGE_ERROR, corruptLine.code());
    }

    @Test
    void lazilyInitializesMissingKeysAndHandlesBlankOrEmptySnapshots() throws Exception {
        Path snapshotPath = tempDir.resolve("catalog").resolve("sequence-state.snapshot");
        Files.createDirectories(snapshotPath.getParent());
        Files.writeString(snapshotPath, "");

        SequenceStateStore emptyStore = new SequenceStateStore(tempDir);
        Catalog.SequenceDefinition adHocSequence = new Catalog.SequenceDefinition(
                new Common.ObjectId(90), new Catalog.QualifiedName("public", "adhoc_seq"), null);
        assertEquals(1L, emptyStore.nextValue(adHocSequence));
        assertEquals(2L, emptyStore.nextValue(adHocSequence));

        Catalog.ColumnDefinition identityColumn = new Catalog.ColumnDefinition(
                0, "id", Common.DataType.BIGINT, false, true, true, null,
                new Catalog.IdentityDefinition(Catalog.IdentityGeneration.BY_DEFAULT, null));
        Catalog.TableDefinition identityTable = new Catalog.TableDefinition(
                new Common.ObjectId(91), new Catalog.QualifiedName("public", "lazy_ids"),
                List.of(identityColumn), Map.of("id", 0), List.of());
        assertEquals(1L, emptyStore.nextIdentityValue(identityTable, identityColumn));
        assertEquals(2L, emptyStore.nextIdentityValue(identityTable, identityColumn));

        Catalog.ColumnDefinition observedIdentity = new Catalog.ColumnDefinition(
                0, "id", Common.DataType.BIGINT, false, true, true, null,
                new Catalog.IdentityDefinition(Catalog.IdentityGeneration.BY_DEFAULT, null));
        Catalog.TableDefinition observedTable = new Catalog.TableDefinition(
                new Common.ObjectId(92), new Catalog.QualifiedName("public", "observed_ids"),
                List.of(observedIdentity), Map.of("id", 0), List.of());
        emptyStore.observeIdentityValue(observedTable, observedIdentity, Common.Value.bigint(20L));
        assertEquals(21L, emptyStore.nextIdentityValue(observedTable, observedIdentity));

        Files.writeString(snapshotPath, String.join(System.lineSeparator(),
                "SEQUENCE_STATE|1",
                "",
                "SEQ:500|12"));
        SequenceStateStore blankLineStore = new SequenceStateStore(tempDir);
        Catalog.SequenceDefinition preloaded = new Catalog.SequenceDefinition(
                new Common.ObjectId(500), new Catalog.QualifiedName("public", "preloaded"),
                new Catalog.SequenceOptions(1L, 1L, null, null, 20, false));
        assertEquals(12L, blankLineStore.nextValue(preloaded));
    }
}
