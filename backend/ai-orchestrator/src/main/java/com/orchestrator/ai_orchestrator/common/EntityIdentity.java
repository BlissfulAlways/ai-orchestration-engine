package com.orchestrator.ai_orchestrator.common;

import org.hibernate.Hibernate;

import java.util.UUID;

/**
 * The equality rules shared by every database entity.
 *
 * <ul>
 *   <li>Two entities are equal when they are the same kind of entity AND have the same,
 *       non-null id. Every other field is ignored.</li>
 *   <li>An entity that has no id yet (not saved) is equal only to itself.</li>
 *   <li>The hash code depends only on the entity's class. It must not depend on the id: the
 *       id is assigned when the row is saved, and a hash that changes while the object sits in
 *       a HashSet makes it impossible to find again. The price is that all entities of one
 *       class share one hash bucket, which is fine because this project never keeps large
 *       sets of entities.</li>
 * </ul>
 *
 * <p>{@code Hibernate.getClass} looks through Hibernate's lazy-loading proxy objects, so a
 * proxy and the real entity it stands for still compare as equal.
 */
public final class EntityIdentity {

    private EntityIdentity() {
        // Static utility class: not meant to be instantiated.
    }

    public static boolean sameEntity(Identifiable self, Object other) {
        if (self == other) {
            return true;
        }
        if (!(other instanceof Identifiable that)) {
            return false;
        }
        Class<?> selfClass = Hibernate.getClass(self);
        Class<?> thatClass = Hibernate.getClass(that);
        if (selfClass != thatClass) {
            return false;
        }
        UUID id = self.getId();
        return id != null && id.equals(that.getId());
    }

    public static int hashOf(Identifiable self) {
        return Hibernate.getClass(self).hashCode();
    }
}