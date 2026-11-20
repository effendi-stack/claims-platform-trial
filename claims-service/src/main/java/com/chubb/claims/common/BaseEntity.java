package com.chubb.claims.common;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.util.UUID;

/**
 * IDs are generated in the application (UUID.randomUUID) so an aggregate has its id
 * before it is saved, e.g. to put it in an outbox event. Without this, Spring Data would
 * see a non-null id, assume the row exists, and do a SELECT + merge instead of an INSERT.
 */
@MappedSuperclass
public abstract class BaseEntity implements Persistable<UUID> {

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }
}
