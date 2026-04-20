package dev.daisybase.engine;

import dev.daisybase.common.Common;
import dev.daisybase.txn.Transactions;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

final class PreparedXaStore {
    private final Path directory;

    PreparedXaStore(Path home) {
        this.directory = Objects.requireNonNull(home, "home").resolve("xa-prepared");
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                    "Failed to initialize XA prepare store", exception);
        }
    }

    synchronized void save(PreparedBranch branch) {
        Objects.requireNonNull(branch, "branch");
        Path path = pathFor(branch.xid());
        try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(path))) {
            output.writeObject(branch);
        } catch (IOException exception) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                    "Failed to persist prepared XA branch", exception);
        }
    }

    synchronized PreparedBranch load(EngineApi.XidDescriptor xid) {
        Path path = pathFor(xid);
        if (!Files.exists(path)) {
            return null;
        }
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(path))) {
            return (PreparedBranch) input.readObject();
        } catch (IOException | ClassNotFoundException exception) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                    "Failed to read prepared XA branch", exception);
        }
    }

    synchronized void delete(EngineApi.XidDescriptor xid) {
        try {
            Files.deleteIfExists(pathFor(xid));
        } catch (IOException exception) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                    "Failed to delete prepared XA branch", exception);
        }
    }

    synchronized List<EngineApi.XidDescriptor> recover() {
        try {
            if (!Files.exists(directory)) {
                return List.of();
            }
            List<EngineApi.XidDescriptor> recovered = new ArrayList<>();
            try (var stream = Files.list(directory)) {
                for (Path path : stream.filter(file -> file.getFileName().toString().endsWith(".bin"))
                        .sorted(Comparator.comparing(file -> file.getFileName().toString()))
                        .toList()) {
                    PreparedBranch branch = loadFromPath(path);
                    recovered.add(branch.xid());
                }
            }
            return recovered;
        } catch (IOException exception) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                    "Failed to enumerate prepared XA branches", exception);
        }
    }

    private PreparedBranch loadFromPath(Path path) {
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(path))) {
            return (PreparedBranch) input.readObject();
        } catch (IOException | ClassNotFoundException exception) {
            throw new Common.DatabaseException(Common.ErrorCode.STORAGE_ERROR,
                    "Failed to read prepared XA branch " + path.getFileName(), exception);
        }
    }

    private Path pathFor(EngineApi.XidDescriptor xid) {
        return directory.resolve(fileName(xid));
    }

    private String fileName(EngineApi.XidDescriptor xid) {
        return xid.formatId() + "_"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(xid.globalId()) + "_"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(xid.branchId()) + ".bin";
    }

    record PreparedBranch(EngineApi.XidDescriptor xid, Transactions.PreparedState preparedState)
            implements java.io.Serializable {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        PreparedBranch {
            Objects.requireNonNull(xid, "xid");
            Objects.requireNonNull(preparedState, "preparedState");
        }
    }
}
