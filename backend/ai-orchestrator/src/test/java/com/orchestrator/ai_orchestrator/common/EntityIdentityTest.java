package com.orchestrator.ai_orchestrator.common;

import com.orchestrator.ai_orchestrator.apigateway.domain.Job;
import com.orchestrator.ai_orchestrator.planner.domain.PlannedTask;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityIdentityTest {

    @Test
    void sameIdMeansEqualEvenWhenOtherFieldsDiffer() {
        UUID id = UUID.randomUUID();
        Job first = Job.builder().id(id).goal("first goal").build();
        Job second = Job.builder().id(id).goal("completely different goal").build();

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void differentIdsAreNotEqual() {
        Job first = Job.builder().id(UUID.randomUUID()).build();
        Job second = Job.builder().id(UUID.randomUUID()).build();

        assertNotEquals(first, second);
    }

    @Test
    void anUnsavedEntityIsEqualOnlyToItself() {
        Job unsaved = Job.builder().goal("same text").build();
        Job anotherUnsaved = Job.builder().goal("same text").build();

        assertEquals(unsaved, unsaved);
        assertNotEquals(unsaved, anotherUnsaved);
    }

    @Test
    void hashCodeDoesNotChangeWhenTheIdIsAssigned() {
        Job job = new Job();
        int hashBeforeSave = job.hashCode();

        job.setId(UUID.randomUUID()); // This is what saving the row does.

        assertEquals(hashBeforeSave, job.hashCode());
    }

    @Test
    void anEntityStaysFindableInAHashSetAfterItsIdIsAssigned() {
        Set<Job> jobs = new HashSet<>();
        Job job = new Job();
        jobs.add(job);

        job.setId(UUID.randomUUID());

        assertTrue(jobs.contains(job));
    }

    @Test
    void entitiesOfDifferentClassesWithTheSameIdAreNotEqual() {
        UUID id = UUID.randomUUID();
        Job job = Job.builder().id(id).build();
        PlannedTask task = PlannedTask.builder().id(id).build();

        assertNotEquals(job, task);
    }

    @Test
    void toStringNeverPrintsLongTextFields() {
        Job job = Job.builder().goal("A-VERY-LONG-USER-GOAL").build();

        assertFalse(job.toString().contains("A-VERY-LONG-USER-GOAL"));
    }
}