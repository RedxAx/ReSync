package restudio.resync.permissions;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuckPermsManagementServiceTest {
    @Test
    void mergesStoredAndLoadedUsersInStableOrder() {
        UUID first = UUID.fromString("09587536-eb25-40f9-9aae-0dc7f3b49b22");
        UUID second = UUID.fromString("b04ff102-b0f5-4eea-8ca5-786e491ee023");

        List<UUID> users = LuckPermsManagementService.mergeUserIds(List.of(second), List.of(first, second));

        assertEquals(List.of(first, second), users);
    }

    @Test
    void rollsBackCompletedPermissionMutationsInReverseOrder() {
        List<String> events = new ArrayList<>();
        List<Supplier<CompletableFuture<LuckPermsManagementService.Compensated<String>>>> mutations = List.of(
            mutation(events, "first"),
            mutation(events, "second"),
            () -> {
                events.add("apply third");
                return CompletableFuture.failedFuture(new IllegalStateException("failed"));
            }
        );

        CompletionException failure = assertThrows(CompletionException.class,
            () -> LuckPermsManagementService.runCompensating(mutations).join());

        LuckPermsManagementService.CompensationFailure transaction = assertInstanceOf(
            LuckPermsManagementService.CompensationFailure.class, failure.getCause());
        assertFalse(transaction.rollbackFailed());
        assertEquals(List.of("apply first", "apply second", "apply third", "rollback second", "rollback first"), events);
    }

    @Test
    void reportsIncompleteRecoveryWhenCompensationFails() {
        List<Supplier<CompletableFuture<LuckPermsManagementService.Compensated<String>>>> mutations = List.of(
            () -> CompletableFuture.completedFuture(new LuckPermsManagementService.Compensated<>("first",
                () -> CompletableFuture.failedFuture(new IllegalStateException("rollback failed")))),
            () -> CompletableFuture.failedFuture(new IllegalStateException("save failed"))
        );

        CompletionException failure = assertThrows(CompletionException.class,
            () -> LuckPermsManagementService.runCompensating(mutations).join());

        LuckPermsManagementService.CompensationFailure transaction = assertInstanceOf(
            LuckPermsManagementService.CompensationFailure.class, failure.getCause());
        assertTrue(transaction.rollbackFailed());
        assertEquals(List.of("first"), transaction.<String>applied());
    }

    @Test
    void rollsBackPermissionMutationsWhenTheCommitFails() {
        List<String> events = new ArrayList<>();
        List<Supplier<CompletableFuture<LuckPermsManagementService.Compensated<String>>>> mutations = List.of(
            mutation(events, "first"),
            mutation(events, "second")
        );

        CompletionException failure = assertThrows(CompletionException.class, () ->
            LuckPermsManagementService.runCompensating(mutations, applied -> {
                events.add("commit " + String.join(",", applied));
                return CompletableFuture.failedFuture(new IllegalStateException("journal failed"));
            }).join());

        LuckPermsManagementService.CompensationFailure transaction = assertInstanceOf(
            LuckPermsManagementService.CompensationFailure.class, failure.getCause());
        assertFalse(transaction.rollbackFailed());
        assertEquals(List.of("first", "second"), transaction.<String>applied());
        assertEquals(List.of("apply first", "apply second", "commit first,second", "rollback second", "rollback first"), events);
    }

    private Supplier<CompletableFuture<LuckPermsManagementService.Compensated<String>>> mutation(List<String> events, String name) {
        return () -> {
            events.add("apply " + name);
            return CompletableFuture.completedFuture(new LuckPermsManagementService.Compensated<>(name, () -> {
                events.add("rollback " + name);
                return CompletableFuture.completedFuture(null);
            }));
        };
    }
}
