package com.cenk.valocase.inventory.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.cenk.valocase.inventory.domain.InventoryItem;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID> {

    List<InventoryItem> findByAccountIdOrderByAcquiredAtDesc(UUID accountId);

    /** The oldest owned instance of a skin for an account, used when selling one. */
    Optional<InventoryItem> findFirstByAccountIdAndSkinIdOrderByAcquiredAtAsc(UUID accountId, String skinId);

    /**
     * Loads the given owned items with a pessimistic write lock so the same item
     * cannot be consumed concurrently (e.g. two upgrades, or upgrade vs sell).
     * Scoped by account so only owned items are returned.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InventoryItem i where i.id in :ids and i.accountId = :accountId")
    List<InventoryItem> findForUpdateByIdInAndAccountId(@Param("ids") Collection<UUID> ids,
                                                        @Param("accountId") UUID accountId);

    List<InventoryItem> findByIdInAndAccountId(Collection<UUID> ids, UUID accountId);
}
