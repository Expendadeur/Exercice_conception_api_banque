package com.banque.security;

import com.banque.model.EdgeSyncQueue;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EdgeSyncQueueRepository extends JpaRepository<EdgeSyncQueue, Long> {
    List<EdgeSyncQueue> findByStatutSynchronisation(String statut);
}