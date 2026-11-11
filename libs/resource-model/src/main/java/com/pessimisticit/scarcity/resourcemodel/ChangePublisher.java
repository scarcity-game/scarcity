package com.pessimisticit.scarcity.resourcemodel;

/** Publishes a batch. Call inside the writing transaction: delivery happens only on commit. */
public interface ChangePublisher {
    void publish(ChangeBatch batch);
}
